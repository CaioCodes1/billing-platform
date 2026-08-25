package com.caiocodes.billing.invoice.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.caiocodes.billing.config.BillingProperties;
import com.caiocodes.billing.invoice.dto.InvoiceFilter;
import com.caiocodes.billing.invoice.dto.InvoiceResponse;
import com.caiocodes.billing.invoice.entity.Invoice;
import com.caiocodes.billing.invoice.mapper.InvoiceMapper;
import com.caiocodes.billing.invoice.repository.InvoiceRepository;
import com.caiocodes.billing.invoice.repository.InvoiceSpecifications;
import com.caiocodes.billing.outbox.entity.OutboxEventType;
import com.caiocodes.billing.outbox.service.OutboxPublisher;
import com.caiocodes.billing.shared.api.PageResponse;
import com.caiocodes.billing.shared.exception.BusinessRuleException;
import com.caiocodes.billing.shared.exception.ResourceNotFoundException;
import com.caiocodes.billing.subscription.domain.BillingCycle;
import com.caiocodes.billing.subscription.entity.Subscription;
import com.caiocodes.billing.subscription.repository.SubscriptionRepository;

@Service
@PreAuthorize("isAuthenticated()")
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    private final InvoiceRepository repository;
    private final SubscriptionRepository subscriptionRepository;
    private final InvoiceMapper mapper;
    private final BillingProperties properties;
    private final OutboxPublisher outbox;
    private final Clock clock;

    public InvoiceService(InvoiceRepository repository,
                          SubscriptionRepository subscriptionRepository,
                          InvoiceMapper mapper,
                          BillingProperties properties,
                          OutboxPublisher outbox,
                          Clock clock) {
        this.repository = repository;
        this.subscriptionRepository = subscriptionRepository;
        this.mapper = mapper;
        this.properties = properties;
        this.outbox = outbox;
        this.clock = clock;
    }

    // =================================================================
    // Emissão
    // =================================================================

    /**
     * Emite a cobrança da competência corrente da assinatura.
     *
     * <p><strong>É idempotente por contrato.</strong> Chamar dez vezes produz
     * uma cobrança. Isso não é conveniência: o job de faturamento vai ser
     * reiniciado no meio, sofrer retry e rodar em duas réplicas, e nenhuma
     * dessas situações pode virar cobrança dupla.
     *
     * <p>A verificação prévia é deliberada, em vez de simplesmente tentar
     * inserir e capturar a violação do índice único. No Postgres, violar uma
     * constraint <strong>aborta a transação inteira</strong>: qualquer comando
     * seguinte falha com "current transaction is aborted". Num job que processa
     * uma página de assinaturas por transação, capturar a exceção e continuar
     * derrubaria as outras assinaturas da mesma página.
     *
     * <p>O índice único segue sendo a garantia final para a corrida real (duas
     * réplicas no mesmo milissegundo). Quando ele dispara, a transação daquela
     * página é desfeita e a próxima execução do job resolve.
     *
     * @return a cobrança emitida ou já existente; vazio quando a assinatura não
     *         deve gerar cobrança neste momento
     */
    // permitAll porque quem chama isto é o job de faturamento, que roda sem
    // usuário autenticado — não há requisição HTTP por trás. Sem esta linha o
    // ciclo diário morre com AuthenticationCredentialsNotFoundException, e o
    // sintoma só apareceria às 3 da manhã. A alternativa seria autenticar o
    // scheduler como um principal de sistema; para um método sem porta de
    // entrada HTTP, liberar aqui é mais simples e igualmente seguro.
    @PreAuthorize("permitAll()")
    @Transactional
    public Optional<Invoice> issueForCurrentPeriod(Subscription assinatura) {
        if (!assinatura.getStatus().geraCobranca()) {
            // PENDING ainda não começou; SUSPENDED teve o serviço interrompido
            // e por isso para de acumular dívida; CANCELLED acabou.
            log.debug("Assinatura {} em {} não gera cobrança",
                    assinatura.getId(), assinatura.getStatus());
            return Optional.empty();
        }

        if (assinatura.getUnitPrice().compareTo(BigDecimal.ZERO) == 0) {
            // Plano gratuito. Emitir aqui violaria o CHECK amount > 0 — e uma
            // fatura de R$ 0,00 só serviria para poluir a régua de cobrança.
            log.debug("Assinatura {} é de plano gratuito, sem cobrança", assinatura.getId());
            return Optional.empty();
        }

        LocalDate competencia = assinatura.getCurrentPeriodStart();

        Optional<Invoice> jaEmitida =
                repository.findBySubscriptionIdAndPeriodStart(assinatura.getId(), competencia);
        if (jaEmitida.isPresent()) {
            log.debug("Competência {} da assinatura {} já emitida",
                    competencia, assinatura.getId());
            return jaEmitida;
        }

        Invoice cobranca = new Invoice(
                assinatura,
                competencia,
                assinatura.getCurrentPeriodEnd(),
                assinatura.getUnitPrice(),
                BillingCycle.vencimento(competencia, properties.invoice().dueDaysAfterIssue()));

        Invoice salva = repository.saveAndFlush(cobranca);
        log.info("Cobrança {} emitida: assinatura={} competência={} valor={} vence={}",
                salva.getId(), assinatura.getId(), competencia,
                salva.getAmount(), salva.getDueDate());

        // Evento na MESMA transação da cobrança: ou os dois existem, ou nenhum.
        // Nunca se envia um aviso de cobrança que o rollback desfez.
        outbox.publicar("Invoice", salva.getId(), OutboxEventType.INVOICE_ISSUED,
                java.util.Map.of(
                        "customerEmail", assinatura.getCustomer().getEmail(),
                        "customerName", assinatura.getCustomer().getName(),
                        "planName", assinatura.getPlan().getName(),
                        "amount", salva.getAmount().toPlainString(),
                        "dueDate", salva.getDueDate().toString()));

        return Optional.of(salva);
    }

    /** Emissão manual, para operação. Idempotente pelo mesmo caminho. */
    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCIAL')")
    public InvoiceResponse issueForSubscription(UUID subscriptionId) {
        Subscription assinatura = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new ResourceNotFoundException("Assinatura", subscriptionId));

        return issueForCurrentPeriod(assinatura)
                .map(mapper::toResponse)
                .orElseThrow(() -> new BusinessRuleException("SEM_COBRANCA_A_EMITIR",
                        "A assinatura está em %s ou é de plano gratuito."
                                .formatted(assinatura.getStatus())));
    }

    // =================================================================
    // Consulta
    // =================================================================

    @Transactional(readOnly = true)
    public InvoiceResponse findById(UUID id) {
        return mapper.toResponse(buscar(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<InvoiceResponse> list(InvoiceFilter filtro, Pageable pageable) {
        Page<InvoiceResponse> pagina = repository
                .findAll(InvoiceSpecifications.comFiltro(filtro), pageable)
                .map(mapper::toResponse);

        return PageResponse.de(pagina);
    }

    // =================================================================
    // Operações
    // =================================================================

    /**
     * Anula uma cobrança. Só faz sentido antes do pagamento — depois disso o
     * caminho é o estorno, que na fase 6 vira um lançamento em {@code payments}.
     */
    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCIAL')")
    public InvoiceResponse cancel(UUID id) {
        Invoice cobranca = buscar(id);
        cobranca.cancel();
        repository.flush();
        log.info("Cobrança {} cancelada", id);
        return mapper.toResponse(cobranca);
    }

    /** Marca as vencidas. Chamado pelo job diário, sem usuário autenticado. */
    @PreAuthorize("permitAll()")
    @Transactional
    public int markOverdue() {
        LocalDate hoje = LocalDate.now(clock);
        var vencidas = repository.findByStatusInAndDueDateBefore(
                java.util.List.of(
                        com.caiocodes.billing.invoice.entity.InvoiceStatus.PENDING,
                        com.caiocodes.billing.invoice.entity.InvoiceStatus.PARTIALLY_PAID),
                hoje);

        vencidas.forEach(Invoice::markOverdue);
        repository.flush();

        if (!vencidas.isEmpty()) {
            log.info("{} cobranças marcadas como vencidas em {}", vencidas.size(), hoje);
        }
        return vencidas.size();
    }

    private Invoice buscar(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Cobrança", id));
    }
}

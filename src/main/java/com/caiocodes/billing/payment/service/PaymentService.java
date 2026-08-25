package com.caiocodes.billing.payment.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.caiocodes.billing.invoice.entity.Invoice;
import com.caiocodes.billing.invoice.entity.InvoiceStatus;
import com.caiocodes.billing.invoice.repository.InvoiceRepository;
import com.caiocodes.billing.payment.dto.PaymentResponse;
import com.caiocodes.billing.payment.dto.RegisterPaymentRequest;
import com.caiocodes.billing.payment.entity.Payment;
import com.caiocodes.billing.payment.entity.PaymentType;
import com.caiocodes.billing.payment.mapper.PaymentMapper;
import com.caiocodes.billing.payment.repository.PaymentRepository;
import com.caiocodes.billing.outbox.entity.OutboxEventType;
import com.caiocodes.billing.outbox.service.OutboxPublisher;
import com.caiocodes.billing.shared.exception.BusinessRuleException;
import com.caiocodes.billing.shared.exception.ResourceNotFoundException;
import com.caiocodes.billing.subscription.entity.Subscription;
import com.caiocodes.billing.subscription.entity.SubscriptionStatus;

@Service
@PreAuthorize("isAuthenticated()")
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository repository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentMapper mapper;
    private final OutboxPublisher outbox;
    private final Clock clock;

    public PaymentService(PaymentRepository repository,
                          InvoiceRepository invoiceRepository,
                          PaymentMapper mapper,
                          OutboxPublisher outbox,
                          Clock clock) {
        this.repository = repository;
        this.invoiceRepository = invoiceRepository;
        this.mapper = mapper;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Registra um pagamento e recalcula o status da cobrança.
     *
     * <p>Idempotente por {@code idempotencyKey} e por {@code providerRef}: se a
     * chave já foi vista, devolve o lançamento anterior em vez de criar um novo.
     * Sem isso, um retry de rede do PSP cobraria a gentileza de um pagamento
     * fantasma — e num livro-razão imutável não haveria como apagar.
     */
    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCIAL')")
    public PaymentResponse register(UUID invoiceId, RegisterPaymentRequest request) {
        Invoice cobranca = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Cobrança", invoiceId));

        var jaRegistrado = buscarDuplicata(request);
        if (jaRegistrado.isPresent()) {
            log.info("Pagamento já registrado (chave repetida), devolvendo o existente: {}",
                    jaRegistrado.get().getId());
            return mapper.toResponse(jaRegistrado.get());
        }

        if (cobranca.getStatus() == InvoiceStatus.CANCELLED) {
            throw new BusinessRuleException("COBRANCA_CANCELADA",
                    "Não é possível pagar uma cobrança cancelada.");
        }
        if (cobranca.getStatus() == InvoiceStatus.REFUNDED) {
            throw new BusinessRuleException("COBRANCA_ESTORNADA",
                    "Cobrança já estornada. Emita uma nova cobrança se for o caso.");
        }

        Payment pagamento = new Payment(
                cobranca,
                PaymentType.PAYMENT,
                request.method(),
                request.amount(),
                request.paidAt() == null ? OffsetDateTime.now(clock) : request.paidAt(),
                request.providerRef(),
                request.idempotencyKey());

        Payment salvo = repository.saveAndFlush(pagamento);
        recalcularStatus(cobranca);

        log.info("Pagamento {} registrado: cobrança={} valor={} método={}",
                salvo.getId(), invoiceId, salvo.getAmount(), salvo.getMethod());

        return mapper.toResponse(salvo);
    }

    /**
     * Estorna — total ou parcialmente — uma cobrança paga.
     *
     * <p>Não altera nem apaga o pagamento original: lança o contrário. O
     * histórico continua explicando o saldo.
     */
    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCIAL')")
    public PaymentResponse refund(UUID invoiceId, RegisterPaymentRequest request) {
        Invoice cobranca = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Cobrança", invoiceId));

        var jaRegistrado = buscarDuplicata(request);
        if (jaRegistrado.isPresent()) {
            return mapper.toResponse(jaRegistrado.get());
        }

        BigDecimal saldo = saldoDe(invoiceId);
        if (request.amount().compareTo(saldo) > 0) {
            throw new BusinessRuleException("ESTORNO_MAIOR_QUE_O_PAGO",
                    "Estorno de %s excede o valor pago disponível (%s)."
                            .formatted(request.amount(), saldo));
        }

        Payment estorno = new Payment(
                cobranca,
                PaymentType.REFUND,
                request.method(),
                request.amount(),
                request.paidAt() == null ? OffsetDateTime.now(clock) : request.paidAt(),
                request.providerRef(),
                request.idempotencyKey());

        Payment salvo = repository.saveAndFlush(estorno);
        recalcularStatus(cobranca);

        log.info("Estorno {} registrado: cobrança={} valor={}",
                salvo.getId(), invoiceId, salvo.getAmount());

        return mapper.toResponse(salvo);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> listByInvoice(UUID invoiceId) {
        if (!invoiceRepository.existsById(invoiceId)) {
            throw new ResourceNotFoundException("Cobrança", invoiceId);
        }
        return repository.findByInvoiceIdOrderByPaidAtAsc(invoiceId)
                .stream().map(mapper::toResponse).toList();
    }

    // =================================================================
    // O coração da fase: o status é DERIVADO, nunca atribuído à mão
    // =================================================================

    /**
     * Recalcula o status da cobrança a partir da soma dos lançamentos.
     *
     * <p>Nenhum caminho do código faz {@code invoice.setStatus(PAID)} olhando
     * para um pagamento isolado. Sempre se soma o razão inteiro e se compara
     * com o valor devido. Consequência prática: pagamento parcial, pagamento a
     * maior, estorno parcial e estorno total caem todos na mesma regra, sem
     * um {@code if} por cenário.
     */
    private void recalcularStatus(Invoice cobranca) {
        BigDecimal saldo = saldoDe(cobranca.getId());
        InvoiceStatus atual = cobranca.getStatus();

        if (saldo.compareTo(BigDecimal.ZERO) <= 0) {
            // Nada pago, ou tudo estornado.
            if (atual == InvoiceStatus.PAID) {
                // Foi paga e devolvida: o estado registra esse fato.
                cobranca.markRefunded();
            } else if (atual == InvoiceStatus.PARTIALLY_PAID) {
                // Só houve pagamento parcial, agora desfeito: volta a aberta.
                cobranca.reopen(java.time.LocalDate.now(clock));
            }
        } else if (saldo.compareTo(cobranca.getAmount()) >= 0) {
            // Quitada (inclui pagamento a maior, que fica como crédito visível).
            if (atual != InvoiceStatus.PAID) {
                cobranca.markPaid(OffsetDateTime.now(clock));
            }
            // Confirmação vai para o outbox só quando a cobrança fica quitada:
            // avisar a cada parcial encheria a caixa do cliente sem informar
            // nada de novo.
            if (cobranca.getStatus() == InvoiceStatus.PAID) {
                var assinatura = cobranca.getSubscription();
                outbox.publicar("Invoice", cobranca.getId(),
                        OutboxEventType.PAYMENT_CONFIRMED,
                        java.util.Map.of(
                                "customerEmail", assinatura.getCustomer().getEmail(),
                                "customerName", assinatura.getCustomer().getName(),
                                "planName", assinatura.getPlan().getName(),
                                "amount", saldo.toPlainString()));
            }
        } else {
            // Pagou algo, mas não tudo.
            if (atual != InvoiceStatus.PARTIALLY_PAID) {
                cobranca.markPartiallyPaid();
            }
        }

        invoiceRepository.flush();
        reativarAssinaturaSeQuitada(cobranca);
    }

    /**
     * Quitada a dívida, a assinatura suspensa volta a vigorar.
     *
     * <p>Só reativa se <strong>nada</strong> ficou em aberto: pagar uma fatura
     * de três em atraso não devolve o serviço.
     */
    private void reativarAssinaturaSeQuitada(Invoice cobranca) {
        Subscription assinatura = cobranca.getSubscription();
        if (assinatura.getStatus() != SubscriptionStatus.SUSPENDED) {
            return;
        }
        BigDecimal emAberto = invoiceRepository.totalEmAberto(assinatura.getId());
        if (emAberto.compareTo(BigDecimal.ZERO) == 0) {
            assinatura.activate();
            log.info("Assinatura {} reativada: nada em aberto", assinatura.getId());
        }
    }

    /** Soma dos PAYMENT menos os REFUND de uma cobrança. */
    @Transactional(readOnly = true)
    public BigDecimal saldoDe(UUID invoiceId) {
        return repository.saldo(invoiceId);
    }

    private java.util.Optional<Payment> buscarDuplicata(RegisterPaymentRequest request) {
        if (request.idempotencyKey() != null) {
            var porChave = repository.findByIdempotencyKey(request.idempotencyKey());
            if (porChave.isPresent()) {
                return porChave;
            }
        }
        if (request.providerRef() != null && !request.providerRef().isBlank()) {
            return repository.findByProviderRef(request.providerRef());
        }
        return java.util.Optional.empty();
    }
}

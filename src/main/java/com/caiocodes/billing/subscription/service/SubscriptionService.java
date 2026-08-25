package com.caiocodes.billing.subscription.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.caiocodes.billing.customer.entity.Customer;
import com.caiocodes.billing.customer.repository.CustomerRepository;
import com.caiocodes.billing.invoice.service.InvoiceService;
import com.caiocodes.billing.plan.entity.Plan;
import com.caiocodes.billing.plan.repository.PlanRepository;
import com.caiocodes.billing.shared.api.PageResponse;
import com.caiocodes.billing.shared.exception.BusinessRuleException;
import com.caiocodes.billing.shared.exception.ConflictException;
import com.caiocodes.billing.shared.exception.ResourceNotFoundException;
import com.caiocodes.billing.subscription.dto.ChangePlanRequest;
import com.caiocodes.billing.subscription.dto.CreateSubscriptionRequest;
import com.caiocodes.billing.subscription.dto.SubscriptionFilter;
import com.caiocodes.billing.subscription.dto.SubscriptionResponse;
import com.caiocodes.billing.subscription.entity.Subscription;
import com.caiocodes.billing.subscription.entity.SubscriptionStatus;
import com.caiocodes.billing.subscription.mapper.SubscriptionMapper;
import com.caiocodes.billing.subscription.repository.SubscriptionRepository;
import com.caiocodes.billing.subscription.repository.SubscriptionSpecifications;

@Service
@PreAuthorize("isAuthenticated()")
public class SubscriptionService {

    /** Estados que ocupam a vaga do cliente — os mesmos do índice parcial. */
    private static final EnumSet<SubscriptionStatus> OCUPAM_VAGA = EnumSet.of(
            SubscriptionStatus.PENDING,
            SubscriptionStatus.ACTIVE,
            SubscriptionStatus.SUSPENDED);

    private final InvoiceService invoiceService;
    private final SubscriptionRepository repository;
    // Acesso direto aos repositórios dos outros módulos. Alternativa seria
    // chamar CustomerService/PlanService, mas isso aninharia transações e
    // devolveria DTO onde precisamos da entidade gerenciada. Num único
    // deployable, com as entidades no mesmo contexto de persistência, este é
    // o caminho mais simples que continua correto.
    private final CustomerRepository customerRepository;
    private final PlanRepository planRepository;
    private final SubscriptionMapper mapper;
    private final Clock clock;

    public SubscriptionService(SubscriptionRepository repository,
                               CustomerRepository customerRepository,
                               PlanRepository planRepository,
                               InvoiceService invoiceService,
                               SubscriptionMapper mapper,
                               Clock clock) {
        this.invoiceService = invoiceService;
        this.repository = repository;
        this.customerRepository = customerRepository;
        this.planRepository = planRepository;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCIAL')")
    public SubscriptionResponse create(CreateSubscriptionRequest request) {
        LocalDate hoje = LocalDate.now(clock);
        LocalDate inicio = request.startDate() == null ? hoje : request.startDate();

        if (inicio.isBefore(hoje)) {
            // Contratação retroativa geraria faturas de competências passadas
            // já vencidas no ato. Se o negócio precisar disso um dia, será uma
            // operação própria, com emissão controlada — não o fluxo normal.
            throw new BusinessRuleException("INICIO_RETROATIVO",
                    "A data de início não pode ser anterior a hoje (%s).".formatted(hoje));
        }

        Customer cliente = customerRepository.findById(request.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Cliente", request.customerId()));

        if (!cliente.isActive()) {
            throw new BusinessRuleException("CLIENTE_INATIVO",
                    "Cliente desativado não pode contratar assinatura.");
        }

        Plan plano = planRepository.findById(request.planId())
                .orElseThrow(() -> new ResourceNotFoundException("Plano", request.planId()));

        if (!plano.isActive()) {
            throw new BusinessRuleException("PLANO_INATIVO",
                    "O plano '%s' está fora do catálogo.".formatted(plano.getName()));
        }

        // Novamente: mensagem aqui, garantia no índice parcial do banco.
        if (repository.existsByCustomerIdAndStatusIn(cliente.getId(), OCUPAM_VAGA)) {
            throw new ConflictException("ASSINATURA_ATIVA_EXISTENTE",
                    "O cliente já possui uma assinatura em vigor. "
                            + "Cancele a atual antes de contratar outra.");
        }

        Subscription assinatura = repository.saveAndFlush(
                new Subscription(cliente, plano, inicio, hoje));

        // Primeira cobrança na mesma transação da contratação: ou o cliente
        // fica com contrato E fatura, ou com nenhum dos dois. Se ficasse para
        // o job noturno, haveria uma janela em que o contrato existe e ninguém
        // foi cobrado — e um crash nessa janela deixaria o mês em branco.
        //
        // Assinatura PENDING (início futuro) não emite nada agora: quem estiver
        // esperando o ciclo começar não tem competência a cobrar ainda.
        invoiceService.issueForCurrentPeriod(assinatura);

        return mapper.toResponse(assinatura);
    }

    @Transactional(readOnly = true)
    public SubscriptionResponse findById(UUID id) {
        return mapper.toResponse(buscar(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<SubscriptionResponse> list(SubscriptionFilter filtro, Pageable pageable) {
        Page<SubscriptionResponse> pagina = repository
                .findAll(SubscriptionSpecifications.comFiltro(filtro), pageable)
                .map(mapper::toResponse);

        return PageResponse.de(pagina);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCIAL')")
    public SubscriptionResponse cancel(UUID id) {
        Subscription assinatura = buscar(id);
        assinatura.cancel(OffsetDateTime.now(clock));
        repository.flush();
        return mapper.toResponse(assinatura);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCIAL')")
    public SubscriptionResponse suspend(UUID id) {
        Subscription assinatura = buscar(id);
        assinatura.suspend(OffsetDateTime.now(clock));
        repository.flush();
        return mapper.toResponse(assinatura);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCIAL')")
    public SubscriptionResponse reactivate(UUID id) {
        Subscription assinatura = buscar(id);
        assinatura.activate();
        repository.flush();
        return mapper.toResponse(assinatura);
    }

    /**
     * Reprecifica o contrato para o valor atual do plano — a contrapartida
     * explícita do preço congelado.
     */
    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCIAL')")
    public SubscriptionResponse migrateToCurrentPrice(UUID id) {
        Subscription assinatura = buscar(id);
        assinatura.migrateToCurrentPlanPrice();
        repository.flush();
        return mapper.toResponse(assinatura);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCIAL')")
    public SubscriptionResponse changePlan(UUID id, ChangePlanRequest request) {
        Subscription assinatura = buscar(id);

        Plan novoPlano = planRepository.findById(request.planId())
                .orElseThrow(() -> new ResourceNotFoundException("Plano", request.planId()));

        if (!novoPlano.isActive()) {
            throw new BusinessRuleException("PLANO_INATIVO",
                    "O plano '%s' está fora do catálogo.".formatted(novoPlano.getName()));
        }

        assinatura.changePlan(novoPlano);
        repository.flush();
        return mapper.toResponse(assinatura);
    }

    private Subscription buscar(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Assinatura", id));
    }
}

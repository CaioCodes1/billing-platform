package com.caiocodes.billing.plan.service;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.caiocodes.billing.plan.dto.CreatePlanRequest;
import com.caiocodes.billing.plan.dto.PlanFilter;
import com.caiocodes.billing.plan.dto.PlanResponse;
import com.caiocodes.billing.plan.dto.UpdatePlanRequest;
import com.caiocodes.billing.plan.entity.Plan;
import com.caiocodes.billing.plan.mapper.PlanMapper;
import com.caiocodes.billing.plan.repository.PlanRepository;
import com.caiocodes.billing.plan.repository.PlanSpecifications;
import com.caiocodes.billing.shared.api.PageResponse;
import com.caiocodes.billing.shared.exception.BusinessRuleException;
import com.caiocodes.billing.shared.exception.ConflictException;
import com.caiocodes.billing.shared.exception.ResourceNotFoundException;

@Service
@PreAuthorize("isAuthenticated()")
public class PlanService {

    private final PlanRepository repository;
    private final PlanMapper mapper;

    public PlanService(PlanRepository repository, PlanMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public PlanResponse create(CreatePlanRequest request) {
        if (repository.existsByNameIgnoreCase(request.name())) {
            throw new ConflictException("PLANO_JA_CADASTRADO",
                    "Já existe um plano chamado '%s'.".formatted(request.name()));
        }

        Plan plano = new Plan(request.name(), request.description(),
                request.monthlyPrice(), request.userLimit());

        return mapper.toResponse(repository.saveAndFlush(plano));
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public PlanResponse update(UUID id, UpdatePlanRequest request) {
        Plan plano = buscar(id);

        if (!plano.getName().equalsIgnoreCase(request.name())
                && repository.existsByNameIgnoreCase(request.name())) {
            throw new ConflictException("PLANO_JA_CADASTRADO",
                    "Já existe um plano chamado '%s'.".formatted(request.name()));
        }

        // Reajuste de preço é permitido e NÃO afeta quem já assinou: a
        // assinatura copiou o valor na contratação (subscriptions.unit_price).
        // Se algum dia o faturamento passar a ler daqui, este endpoint vira
        // uma reprecificação retroativa da base inteira.
        plano.setName(request.name());
        plano.setDescription(request.description());
        plano.setMonthlyPrice(request.monthlyPrice());
        plano.setUserLimit(request.userLimit());

        repository.flush();
        return mapper.toResponse(plano);
    }

    @Transactional(readOnly = true)
    public PlanResponse findById(UUID id) {
        return mapper.toResponse(buscar(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<PlanResponse> list(PlanFilter filtro, Pageable pageable) {
        Page<PlanResponse> pagina = repository
                .findAll(PlanSpecifications.comFiltro(filtro), pageable)
                .map(mapper::toResponse);

        return PageResponse.de(pagina);
    }

    /**
     * Tira o plano do catálogo — sem tocar em quem já assinou.
     *
     * <p>Diferente de {@code CustomerService#deactivate}, aqui repetir a
     * chamada é erro: desativar um plano é uma decisão comercial que dispara
     * comunicação e relatório. Uma segunda chamada quase sempre significa
     * clique duplo ou script rodando duas vezes, e é melhor o cliente da API
     * saber disso do que receber 204 silencioso.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void deactivate(UUID id) {
        Plan plano = buscar(id);
        if (!plano.isActive()) {
            throw new BusinessRuleException("PLANO_JA_INATIVO",
                    "O plano '%s' já está inativo.".formatted(plano.getName()));
        }
        plano.deactivate();
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public PlanResponse activate(UUID id) {
        Plan plano = buscar(id);
        plano.activate();
        repository.flush();
        return mapper.toResponse(plano);
    }

    private Plan buscar(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Plano", id));
    }
}

package com.caiocodes.billing.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.caiocodes.billing.plan.dto.CreatePlanRequest;
import com.caiocodes.billing.plan.dto.PlanResponse;
import com.caiocodes.billing.plan.dto.UpdatePlanRequest;
import com.caiocodes.billing.plan.entity.Plan;
import com.caiocodes.billing.plan.mapper.PlanMapperImpl;
import com.caiocodes.billing.plan.repository.PlanRepository;
import com.caiocodes.billing.shared.exception.BusinessRuleException;
import com.caiocodes.billing.shared.exception.ConflictException;
import com.caiocodes.billing.shared.exception.ResourceNotFoundException;

@ExtendWith(MockitoExtension.class)
class PlanServiceTest {

    @Mock
    private PlanRepository repository;

    private PlanService service;

    @BeforeEach
    void setUp() {
        service = new PlanService(repository, new PlanMapperImpl());
    }

    private Plan planoProfissional() {
        return new Plan("Profissional", "Até 25 usuários", new BigDecimal("199.90"), 25);
    }

    @Test
    @DisplayName("Cria plano já ativo e em BRL")
    void criaPlano() {
        when(repository.existsByNameIgnoreCase(anyString())).thenReturn(false);
        when(repository.saveAndFlush(any(Plan.class))).thenAnswer(inv -> inv.getArgument(0));

        PlanResponse resposta = service.create(new CreatePlanRequest(
                "Profissional", "Até 25 usuários", new BigDecimal("199.90"), 25));

        assertThat(resposta.name()).isEqualTo("Profissional");
        assertThat(resposta.monthlyPrice()).isEqualByComparingTo("199.90");
        assertThat(resposta.currency()).isEqualTo("BRL");
        assertThat(resposta.active()).isTrue();
    }

    @Test
    @DisplayName("Recusa nome de plano já existente sem gravar")
    void recusaNomeDuplicado() {
        when(repository.existsByNameIgnoreCase("Profissional")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreatePlanRequest(
                "Profissional", null, new BigDecimal("99.00"), 10)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Profissional");

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Reajuste de preço é permitido")
    void reajustaPreco() {
        Plan plano = planoProfissional();
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(plano));

        PlanResponse resposta = service.update(id, new UpdatePlanRequest(
                "Profissional", "Até 25 usuários", new BigDecimal("249.90"), 25));

        assertThat(resposta.monthlyPrice()).isEqualByComparingTo("249.90");
        // Manteve o próprio nome: não faz sentido perguntar ao banco se existe.
        verify(repository, never()).existsByNameIgnoreCase(anyString());
    }

    @Test
    @DisplayName("Recusa renomear para o nome de outro plano")
    void recusaRenomearParaNomeOcupado() {
        Plan plano = planoProfissional();
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(plano));
        when(repository.existsByNameIgnoreCase("Enterprise")).thenReturn(true);

        assertThatThrownBy(() -> service.update(id, new UpdatePlanRequest(
                "Enterprise", null, new BigDecimal("199.90"), 25)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("Desativar duas vezes acusa erro — diferente do cliente")
    void desativarDuasVezesFalha() {
        Plan plano = planoProfissional();
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(plano));

        service.deactivate(id);
        assertThat(plano.isActive()).isFalse();

        assertThatThrownBy(() -> service.deactivate(id))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("já está inativo");
    }

    @Test
    @DisplayName("Buscar plano inexistente devolve erro de recurso não encontrado")
    void buscaInexistente() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Plano");
    }
}

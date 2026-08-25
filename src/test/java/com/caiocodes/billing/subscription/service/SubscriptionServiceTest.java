package com.caiocodes.billing.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.caiocodes.billing.customer.entity.Customer;
import com.caiocodes.billing.customer.repository.CustomerRepository;
import com.caiocodes.billing.invoice.service.InvoiceService;
import com.caiocodes.billing.plan.entity.Plan;
import com.caiocodes.billing.plan.repository.PlanRepository;
import com.caiocodes.billing.shared.exception.BusinessRuleException;
import com.caiocodes.billing.shared.exception.ConflictException;
import com.caiocodes.billing.shared.exception.ResourceNotFoundException;
import com.caiocodes.billing.subscription.dto.CreateSubscriptionRequest;
import com.caiocodes.billing.subscription.dto.SubscriptionResponse;
import com.caiocodes.billing.subscription.entity.Subscription;
import com.caiocodes.billing.subscription.entity.SubscriptionStatus;
import com.caiocodes.billing.subscription.mapper.SubscriptionMapperImpl;
import com.caiocodes.billing.subscription.repository.SubscriptionRepository;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    /** Relógio parado: todo teste sabe exatamente que dia é hoje. */
    private static final LocalDate HOJE = LocalDate.of(2026, 3, 15);
    private static final Clock RELOGIO = Clock.fixed(
            Instant.parse("2026-03-15T10:00:00Z"), ZoneId.of("America/Sao_Paulo"));

    @Mock
    private SubscriptionRepository repository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private PlanRepository planRepository;
    @Mock
    private InvoiceService invoiceService;

    private SubscriptionService service;

    private final UUID idCliente = UUID.randomUUID();
    private final UUID idPlano = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SubscriptionService(repository, customerRepository,
                planRepository, invoiceService, new SubscriptionMapperImpl(), RELOGIO);
    }

    private Customer cliente() {
        return new Customer("Padaria", "padaria@exemplo.com.br", "52998224725", null);
    }

    private Plan plano() {
        return new Plan("Profissional", "25 usuários", new BigDecimal("199.90"), 25);
    }

    private void cenarioFeliz() {
        when(customerRepository.findById(idCliente)).thenReturn(Optional.of(cliente()));
        when(planRepository.findById(idPlano)).thenReturn(Optional.of(plano()));
        when(repository.existsByCustomerIdAndStatusIn(any(), anyCollection())).thenReturn(false);
        when(repository.saveAndFlush(any(Subscription.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("Contrata copiando o preço do plano e já em ACTIVE")
    void contrata() {
        cenarioFeliz();

        SubscriptionResponse resposta = service.create(
                new CreateSubscriptionRequest(idCliente, idPlano, null));

        assertThat(resposta.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(resposta.unitPrice()).isEqualByComparingTo("199.90");
        assertThat(resposta.startDate()).isEqualTo(HOJE);
        assertThat(resposta.billingDay()).isEqualTo((short) 15);
        assertThat(resposta.currentPeriodEnd()).isEqualTo(LocalDate.of(2026, 4, 15));
        assertThat(resposta.nextRenewalDate()).isEqualTo(LocalDate.of(2026, 4, 15));
    }

    @Test
    @DisplayName("Data de início futura faz a assinatura nascer PENDING")
    void inicioFuturoNascePendente() {
        cenarioFeliz();

        SubscriptionResponse resposta = service.create(
                new CreateSubscriptionRequest(idCliente, idPlano, LocalDate.of(2026, 4, 1)));

        assertThat(resposta.status()).isEqualTo(SubscriptionStatus.PENDING);
        assertThat(resposta.billingDay()).isEqualTo((short) 1);
    }

    @Test
    @DisplayName("Recusa data de início retroativa")
    void recusaInicioRetroativo() {
        assertThatThrownBy(() -> service.create(
                new CreateSubscriptionRequest(idCliente, idPlano, HOJE.minusDays(1))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("anterior a hoje");

        // Falhou antes de sequer consultar o cliente.
        verify(customerRepository, never()).findById(any());
    }

    @Test
    @DisplayName("Cliente desativado não contrata")
    void recusaClienteInativo() {
        Customer inativo = cliente();
        inativo.deactivate();
        when(customerRepository.findById(idCliente)).thenReturn(Optional.of(inativo));

        assertThatThrownBy(() -> service.create(
                new CreateSubscriptionRequest(idCliente, idPlano, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("desativado");

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Plano fora do catálogo não pode ser contratado")
    void recusaPlanoInativo() {
        Plan foraDeCatalogo = plano();
        foraDeCatalogo.deactivate();
        when(customerRepository.findById(idCliente)).thenReturn(Optional.of(cliente()));
        when(planRepository.findById(idPlano)).thenReturn(Optional.of(foraDeCatalogo));

        assertThatThrownBy(() -> service.create(
                new CreateSubscriptionRequest(idCliente, idPlano, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("fora do catálogo");
    }

    @Test
    @DisplayName("Cliente com assinatura em vigor recebe 409")
    void recusaSegundaAssinatura() {
        when(customerRepository.findById(idCliente)).thenReturn(Optional.of(cliente()));
        when(planRepository.findById(idPlano)).thenReturn(Optional.of(plano()));
        when(repository.existsByCustomerIdAndStatusIn(any(), anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> service.create(
                new CreateSubscriptionRequest(idCliente, idPlano, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("já possui uma assinatura");

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Cliente inexistente devolve 404")
    void clienteInexistente() {
        when(customerRepository.findById(idCliente)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(
                new CreateSubscriptionRequest(idCliente, idPlano, null)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Cliente");
    }

    @Test
    @DisplayName("Cancelar duas vezes é recusado pela máquina de estados")
    void cancelarDuasVezes() {
        Subscription assinatura = new Subscription(cliente(), plano(), HOJE, HOJE);
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(assinatura));

        service.cancel(id);
        assertThat(assinatura.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(assinatura.getCancelledAt()).isNotNull();

        assertThatThrownBy(() -> service.cancel(id))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("CANCELLED");
    }

    @Test
    @DisplayName("Suspender e reativar preserva o ciclo e limpa o carimbo")
    void suspendeEReativa() {
        Subscription assinatura = new Subscription(cliente(), plano(), HOJE, HOJE);
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(assinatura));

        LocalDate fimOriginal = assinatura.getCurrentPeriodEnd();

        service.suspend(id);
        assertThat(assinatura.getStatus()).isEqualTo(SubscriptionStatus.SUSPENDED);
        assertThat(assinatura.getSuspendedAt()).isNotNull();

        service.reactivate(id);
        assertThat(assinatura.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(assinatura.getSuspendedAt()).isNull();
        // A suspensão não mexeu no ciclo — o cliente não ganha nem perde dias.
        assertThat(assinatura.getCurrentPeriodEnd()).isEqualTo(fimOriginal);
    }

    @Test
    @DisplayName("Reprecificar assinatura cancelada é recusado")
    void naoReprecificaCancelada() {
        Subscription assinatura = new Subscription(cliente(), plano(), HOJE, HOJE);
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(assinatura));

        service.cancel(id);

        assertThatThrownBy(() -> service.migrateToCurrentPrice(id))
                .isInstanceOf(BusinessRuleException.class);
    }
}

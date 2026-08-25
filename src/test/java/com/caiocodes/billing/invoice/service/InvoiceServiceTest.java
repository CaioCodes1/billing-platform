package com.caiocodes.billing.invoice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.caiocodes.billing.config.BillingProperties;
import com.caiocodes.billing.customer.entity.Customer;
import com.caiocodes.billing.invoice.entity.Invoice;
import com.caiocodes.billing.invoice.mapper.InvoiceMapperImpl;
import com.caiocodes.billing.invoice.repository.InvoiceRepository;
import com.caiocodes.billing.outbox.service.OutboxPublisher;
import com.caiocodes.billing.plan.entity.Plan;
import com.caiocodes.billing.subscription.entity.Subscription;
import com.caiocodes.billing.subscription.repository.SubscriptionRepository;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 3, 15);
    private static final Clock RELOGIO = Clock.fixed(
            Instant.parse("2026-03-15T10:00:00Z"), ZoneId.of("America/Sao_Paulo"));

    private static final BillingProperties PROPRIEDADES = new BillingProperties(
            "America/Sao_Paulo",
            new BillingProperties.Invoice(5, 10),
            new BillingProperties.Dunning(15, 30),
            new BillingProperties.Jwt("chave-de-teste-com-no-minimo-32-bytes-ok",
                    java.time.Duration.ofMinutes(15), java.time.Duration.ofDays(7)),
            null);

    @Mock
    private InvoiceRepository repository;
    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private OutboxPublisher outbox;

    private InvoiceService service;

    @BeforeEach
    void setUp() {
        service = new InvoiceService(repository, subscriptionRepository,
                new InvoiceMapperImpl(), PROPRIEDADES, outbox, RELOGIO);
    }

    private Subscription assinatura(String preco) {
        Customer cliente = new Customer("Padaria", "p@exemplo.com.br", "52998224725", null);
        Plan plano = new Plan("Profissional", null, new BigDecimal(preco), 25);
        return new Subscription(cliente, plano, HOJE, HOJE);
    }

    @Test
    @DisplayName("Emite a cobrança da competência corrente com o valor do contrato")
    void emiteCobranca() {
        Subscription assinatura = assinatura("199.90");
        when(repository.findBySubscriptionIdAndPeriodStart(any(), any()))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<Invoice> emitida = service.issueForCurrentPeriod(assinatura);

        assertThat(emitida).isPresent();
        Invoice cobranca = emitida.orElseThrow();
        assertThat(cobranca.getAmount()).isEqualByComparingTo("199.90");
        assertThat(cobranca.getPeriodStart()).isEqualTo(HOJE);
        assertThat(cobranca.getPeriodEnd()).isEqualTo(LocalDate.of(2026, 4, 15));
        // dueDaysAfterIssue = 10
        assertThat(cobranca.getDueDate()).isEqualTo(LocalDate.of(2026, 3, 25));
    }

    // =================================================================
    // Idempotência — a razão de ser desta classe
    // =================================================================
    @Test
    @DisplayName("Competência já emitida devolve a existente, sem gravar de novo")
    void emissaoEhIdempotente() {
        Subscription assinatura = assinatura("199.90");
        Invoice existente = new Invoice(assinatura, HOJE, LocalDate.of(2026, 4, 15),
                new BigDecimal("199.90"), LocalDate.of(2026, 3, 25));

        when(repository.findBySubscriptionIdAndPeriodStart(any(), any()))
                .thenReturn(Optional.of(existente));

        Optional<Invoice> resultado = service.issueForCurrentPeriod(assinatura);

        assertThat(resultado).containsSame(existente);
        // O ponto do teste: não houve segunda gravação. Job reiniciado,
        // retry ou réplica concorrente não geram cobrança dupla.
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Assinatura suspensa não gera cobrança — a dívida para de crescer")
    void suspensaNaoGeraCobranca() {
        Subscription assinatura = assinatura("199.90");
        assinatura.suspend(OffsetDateTime.now(RELOGIO));

        assertThat(service.issueForCurrentPeriod(assinatura)).isEmpty();
        verify(repository, never()).saveAndFlush(any());
        // Nem sequer consultou o banco: o estado já respondeu.
        verify(repository, never()).findBySubscriptionIdAndPeriodStart(any(), any());
    }

    @Test
    @DisplayName("Assinatura pendente não gera cobrança — o ciclo ainda não começou")
    void pendenteNaoGeraCobranca() {
        Customer cliente = new Customer("Padaria", "p@exemplo.com.br", "52998224725", null);
        Plan plano = new Plan("Profissional", null, new BigDecimal("199.90"), 25);
        Subscription futura = new Subscription(cliente, plano, HOJE.plusMonths(1), HOJE);

        assertThat(service.issueForCurrentPeriod(futura)).isEmpty();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Assinatura cancelada não gera cobrança")
    void canceladaNaoGeraCobranca() {
        Subscription assinatura = assinatura("199.90");
        assinatura.cancel(OffsetDateTime.now(RELOGIO));

        assertThat(service.issueForCurrentPeriod(assinatura)).isEmpty();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Plano gratuito não gera cobrança de R$ 0,00")
    void planoGratuitoNaoGeraCobranca() {
        // O CHECK do banco exige amount > 0, e uma fatura zerada só serviria
        // para poluir a régua de inadimplência.
        Subscription gratuita = assinatura("0.00");

        assertThat(service.issueForCurrentPeriod(gratuita)).isEmpty();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("markOverdue vence só o que passou do prazo e estava em aberto")
    void marcaVencidas() {
        Subscription assinatura = assinatura("199.90");
        Invoice atrasada = new Invoice(assinatura, LocalDate.of(2026, 2, 15),
                LocalDate.of(2026, 3, 15), new BigDecimal("199.90"), LocalDate.of(2026, 2, 25));

        when(repository.findByStatusInAndDueDateBefore(any(), any()))
                .thenReturn(java.util.List.of(atrasada));

        int marcadas = service.markOverdue();

        assertThat(marcadas).isEqualTo(1);
        assertThat(atrasada.getStatus())
                .isEqualTo(com.caiocodes.billing.invoice.entity.InvoiceStatus.OVERDUE);
        assertThat(atrasada.diasDeAtrasoEm(HOJE)).isEqualTo(18);
    }
}

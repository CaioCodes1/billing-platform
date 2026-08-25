package com.caiocodes.billing.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.caiocodes.billing.AbstractIntegrationTest;
import com.caiocodes.billing.customer.entity.Customer;
import com.caiocodes.billing.customer.repository.CustomerRepository;
import com.caiocodes.billing.invoice.entity.Invoice;
import com.caiocodes.billing.invoice.entity.InvoiceStatus;
import com.caiocodes.billing.invoice.repository.InvoiceRepository;
import com.caiocodes.billing.invoice.service.InvoiceService;
import com.caiocodes.billing.outbox.entity.OutboxEventType;
import com.caiocodes.billing.outbox.repository.OutboxRepository;
import com.caiocodes.billing.plan.entity.Plan;
import com.caiocodes.billing.plan.repository.PlanRepository;
import com.caiocodes.billing.subscription.entity.Subscription;
import com.caiocodes.billing.subscription.entity.SubscriptionStatus;
import com.caiocodes.billing.subscription.repository.SubscriptionRepository;
import com.caiocodes.billing.support.TestClockConfig;

/**
 * O ciclo de vida completo de uma assinatura, comprimido em milissegundos.
 *
 * <p>Este é o teste mais importante do sistema: ele percorre contratação →
 * renovação → vencimento → suspensão → encerramento com o relógio andando, e é
 * a única forma de verificar que os cinco passos do job conversam entre si.
 *
 * <p>Não usa {@code @Transactional}: o job abre transações próprias por item, e
 * envolvê-lo numa transação de teste esconderia justamente o comportamento que
 * se quer verificar. A limpeza é explícita, no começo de cada teste.
 */
@Import(TestClockConfig.class)
class CicloFaturamentoIT extends AbstractIntegrationTest {

    @Autowired
    private BillingRunner runner;
    @Autowired
    private TestClockConfig.TestClock relogio;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private InvoiceRepository invoiceRepository;
    @Autowired
    private OutboxRepository outboxRepository;
    @Autowired
    private InvoiceService invoiceService;

    /**
     * Limpa antes E depois.
     *
     * <p>Sem {@code @Transactional} não há rollback automático, então o que este
     * teste grava fica no banco e contamina os outros — os agregados do
     * dashboard passam a somar dados alheios e os documentos fixos colidem com
     * o índice único de CPF. Limpar só no início resolveria este teste e
     * quebraria os vizinhos.
     */
    @BeforeEach
    void limparAntes() { limpar(); }

    @AfterEach
    void limparDepois() { limpar(); }

    private void limpar() {
        outboxRepository.deleteAll();
        invoiceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        customerRepository.deleteAll();
        planRepository.deleteAll();
        relogio.definir(java.time.Instant.parse("2026-03-01T09:00:00Z"));
    }

    private Subscription contratar(String email, String documento, String preco) {
        Customer cliente = customerRepository.saveAndFlush(
                new Customer("Cliente " + documento, email, documento, null));
        Plan plano = planRepository.saveAndFlush(
                new Plan("Plano " + documento, null, new BigDecimal(preco), 25));

        LocalDate hoje = LocalDate.now(relogio);
        Subscription assinatura = subscriptionRepository.saveAndFlush(
                new Subscription(cliente, plano, hoje, hoje));
        invoiceService.issueForCurrentPeriod(assinatura);
        return assinatura;
    }

    @Test
    @DisplayName("Da contratação ao encerramento: os cinco passos em sequência")
    void cicloDeVidaCompleto() {

        Subscription assinatura = contratar("ciclo@exemplo.com.br", "52998224725", "199.90");

        // --- Dia 0: contratada, primeira cobrança emitida ---
        assertThat(assinatura.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(invoiceRepository.findBySubscriptionIdOrderByPeriodStartDesc(
                assinatura.getId())).hasSize(1);

        // --- Dia 12: passou do vencimento (10 dias), mas ainda não da tolerância ---
        relogio.avancarDias(12);
        runner.executarCicloDiario();

        Invoice primeira = invoiceRepository.findBySubscriptionIdOrderByPeriodStartDesc(
                assinatura.getId()).get(0);
        assertThat(primeira.getStatus()).isEqualTo(InvoiceStatus.OVERDUE);
        assertThat(subscriptionRepository.findById(assinatura.getId()).orElseThrow()
                .getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);

        // --- Dia 27: 15+ dias de atraso, a assinatura é suspensa ---
        relogio.avancarDias(15);
        runner.executarCicloDiario();

        Subscription suspensa = subscriptionRepository.findById(assinatura.getId()).orElseThrow();
        assertThat(suspensa.getStatus()).isEqualTo(SubscriptionStatus.SUSPENDED);
        assertThat(suspensa.getSuspendedAt()).isNotNull();

        // Suspensa não acumula mais dívida: continua com uma cobrança só.
        int cobrancasNaSuspensao = invoiceRepository
                .findBySubscriptionIdOrderByPeriodStartDesc(assinatura.getId()).size();

        relogio.avancarDias(20);
        runner.executarCicloDiario();
        assertThat(invoiceRepository.findBySubscriptionIdOrderByPeriodStartDesc(
                assinatura.getId())).hasSize(cobrancasNaSuspensao);

        // --- Dia 62: 30+ dias suspensa, encerrada ---
        relogio.avancarDias(15);
        runner.executarCicloDiario();

        assertThat(subscriptionRepository.findById(assinatura.getId()).orElseThrow()
                .getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);

        // --- Os eventos foram para o outbox, na ordem certa ---
        List<OutboxEventType> eventos = outboxRepository.findAll().stream()
                .map(m -> m.getEventType()).toList();

        assertThat(eventos)
                .contains(OutboxEventType.INVOICE_ISSUED)
                .contains(OutboxEventType.SUBSCRIPTION_SUSPENDED)
                .contains(OutboxEventType.SUBSCRIPTION_CANCELLED);
    }

    @Test
    @DisplayName("Renovação emite a competência seguinte, uma única vez")
    void renovacaoEmiteProximaCompetencia() {

        Subscription assinatura = contratar("renova@exemplo.com.br", "11144477735", "99.90");

        // A renovação é emitida com 5 dias de antecedência (generate-days-ahead).
        relogio.avancarDias(27);
        runner.executarCicloDiario();

        List<Invoice> cobrancas = invoiceRepository
                .findBySubscriptionIdOrderByPeriodStartDesc(assinatura.getId());
        assertThat(cobrancas).hasSize(2);

        // Competências distintas e contíguas: sem buraco, sem sobreposição.
        assertThat(cobrancas.get(0).getPeriodStart())
                .isEqualTo(cobrancas.get(1).getPeriodEnd());

        // Rodar o ciclo de novo no mesmo dia não emite uma terceira.
        runner.executarCicloDiario();
        assertThat(invoiceRepository.findBySubscriptionIdOrderByPeriodStartDesc(
                assinatura.getId())).hasSize(2);
    }

    @Test
    @DisplayName("Assinatura futura é ativada e faturada quando o dia chega")
    void ativacaoDePendente() {

        Customer cliente = customerRepository.saveAndFlush(
                new Customer("Futuro", "futuro@exemplo.com.br", "11222333000181", null));
        Plan plano = planRepository.saveAndFlush(
                new Plan("Futuro", null, new BigDecimal("49.90"), 5));

        LocalDate hoje = LocalDate.now(relogio);
        Subscription futura = subscriptionRepository.saveAndFlush(
                new Subscription(cliente, plano, hoje.plusDays(10), hoje));

        assertThat(futura.getStatus()).isEqualTo(SubscriptionStatus.PENDING);
        assertThat(invoiceRepository.findBySubscriptionIdOrderByPeriodStartDesc(
                futura.getId())).isEmpty();

        relogio.avancarDias(10);
        runner.executarCicloDiario();

        Subscription ativa = subscriptionRepository.findById(futura.getId()).orElseThrow();
        assertThat(ativa.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(invoiceRepository.findBySubscriptionIdOrderByPeriodStartDesc(
                futura.getId())).hasSize(1);
    }

    @Test
    @DisplayName("O ciclo é seguro para rodar duas vezes no mesmo dia")
    void cicloEhIdempotente() {

        Subscription assinatura = contratar("idem@exemplo.com.br", "52998224725", "199.90");

        relogio.avancarDias(30);

        var primeiro = runner.executarCicloDiario();
        var segundo = runner.executarCicloDiario();

        // A segunda execução não encontra mais nada a fazer.
        assertThat(segundo.emitidas()).isZero();
        assertThat(segundo.vencidas()).isZero();
        assertThat(primeiro.emitidas()).isPositive();

        // E o número de cobranças não mudou.
        assertThat(invoiceRepository.findBySubscriptionIdOrderByPeriodStartDesc(
                assinatura.getId())).hasSize(2);
    }
}

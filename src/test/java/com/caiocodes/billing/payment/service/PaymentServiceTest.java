package com.caiocodes.billing.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.caiocodes.billing.customer.entity.Customer;
import com.caiocodes.billing.invoice.entity.Invoice;
import com.caiocodes.billing.invoice.entity.InvoiceStatus;
import com.caiocodes.billing.invoice.repository.InvoiceRepository;
import com.caiocodes.billing.payment.dto.RegisterPaymentRequest;
import com.caiocodes.billing.payment.entity.Payment;
import com.caiocodes.billing.payment.entity.PaymentMethod;
import com.caiocodes.billing.payment.entity.PaymentType;
import com.caiocodes.billing.payment.mapper.PaymentMapperImpl;
import com.caiocodes.billing.payment.repository.PaymentRepository;
import com.caiocodes.billing.outbox.service.OutboxPublisher;
import com.caiocodes.billing.plan.entity.Plan;
import com.caiocodes.billing.shared.exception.BusinessRuleException;
import com.caiocodes.billing.subscription.entity.Subscription;
import com.caiocodes.billing.subscription.entity.SubscriptionStatus;

/**
 * O que estes testes provam: o status da cobrança é <strong>derivado</strong>
 * da soma do razão, e não atribuído olhando um pagamento isolado. É por isso
 * que pagamento parcial, pagamento a maior, estorno parcial e estorno total
 * caem todos na mesma regra.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 3, 20);
    private static final Clock RELOGIO = Clock.fixed(
            Instant.parse("2026-03-20T10:00:00Z"), ZoneId.of("America/Sao_Paulo"));

    @Mock
    private PaymentRepository repository;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private OutboxPublisher outbox;

    private PaymentService service;

    private final UUID idCobranca = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PaymentService(repository, invoiceRepository,
                new PaymentMapperImpl(), outbox, RELOGIO);
    }

    private Invoice cobrancaDe(String valor, LocalDate vencimento) {
        Customer cliente = new Customer("Padaria", "p@exemplo.com.br", "52998224725", null);
        Plan plano = new Plan("Profissional", null, new BigDecimal(valor), 25);
        Subscription assinatura = new Subscription(cliente, plano,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 1));
        return new Invoice(assinatura, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1),
                new BigDecimal(valor), vencimento);
    }

    private RegisterPaymentRequest pagamentoDe(String valor) {
        return new RegisterPaymentRequest(
                PaymentMethod.PIX, new BigDecimal(valor), null, null, null);
    }

    private void prepararCobranca(Invoice cobranca, String saldoResultante) {
        when(invoiceRepository.findById(idCobranca)).thenReturn(Optional.of(cobranca));
        when(repository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.saldo(any())).thenReturn(new BigDecimal(saldoResultante));
    }

    // =================================================================
    // Derivação do status
    // =================================================================

    @Test
    @DisplayName("Pagamento do valor cheio quita a cobrança")
    void pagamentoTotalQuita() {
        Invoice cobranca = cobrancaDe("199.90", HOJE.plusDays(5));
        prepararCobranca(cobranca, "199.90");

        service.register(idCobranca, pagamentoDe("199.90"));

        assertThat(cobranca.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(cobranca.getPaidAt()).isNotNull();
    }

    @Test
    @DisplayName("Pagamento parcial deixa a cobrança como PARTIALLY_PAID")
    void pagamentoParcial() {
        Invoice cobranca = cobrancaDe("199.90", HOJE.plusDays(5));
        prepararCobranca(cobranca, "100.00");

        service.register(idCobranca, pagamentoDe("100.00"));

        assertThat(cobranca.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
        assertThat(cobranca.getPaidAt()).isNull();
    }

    @Test
    @DisplayName("Dois pagamentos parciais que somam o total quitam")
    void doisParciaisQuitam() {
        Invoice cobranca = cobrancaDe("199.90", HOJE.plusDays(5));

        when(invoiceRepository.findById(idCobranca)).thenReturn(Optional.of(cobranca));
        when(repository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.saldo(any()))
                .thenReturn(new BigDecimal("100.00"))
                .thenReturn(new BigDecimal("199.90"));

        service.register(idCobranca, pagamentoDe("100.00"));
        assertThat(cobranca.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);

        service.register(idCobranca, pagamentoDe("99.90"));
        assertThat(cobranca.getStatus()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    @DisplayName("Pagamento a maior quita e o excedente fica visível como crédito")
    void pagamentoAMaior() {
        Invoice cobranca = cobrancaDe("199.90", HOJE.plusDays(5));
        prepararCobranca(cobranca, "250.00");

        service.register(idCobranca, pagamentoDe("250.00"));

        // O dinheiro a mais não é perdido nem rejeitado: o razão registra o
        // que entrou, e a diferença aparece no saldo.
        assertThat(cobranca.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(service.saldoDe(idCobranca)).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("Cobrança vencida que é paga vai direto para PAID")
    void vencidaQuePagaQuita() {
        Invoice cobranca = cobrancaDe("199.90", HOJE.minusDays(10));
        cobranca.markOverdue();
        prepararCobranca(cobranca, "199.90");

        service.register(idCobranca, pagamentoDe("199.90"));

        assertThat(cobranca.getStatus()).isEqualTo(InvoiceStatus.PAID);
    }

    // =================================================================
    // Estorno: lançar o contrário, nunca apagar
    // =================================================================

    @Test
    @DisplayName("Estorno total de cobrança paga a marca como REFUNDED")
    void estornoTotal() {
        Invoice cobranca = cobrancaDe("199.90", HOJE.plusDays(5));
        cobranca.markPaid(OffsetDateTime.now(RELOGIO));

        when(invoiceRepository.findById(idCobranca)).thenReturn(Optional.of(cobranca));
        when(repository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        // saldo antes do estorno (validação) e depois (recálculo)
        when(repository.saldo(any()))
                .thenReturn(new BigDecimal("199.90"))
                .thenReturn(BigDecimal.ZERO);

        service.refund(idCobranca, pagamentoDe("199.90"));

        assertThat(cobranca.getStatus()).isEqualTo(InvoiceStatus.REFUNDED);
    }

    @Test
    @DisplayName("Estorno parcial de cobrança paga volta para PARTIALLY_PAID")
    void estornoParcial() {
        Invoice cobranca = cobrancaDe("199.90", HOJE.plusDays(5));
        cobranca.markPaid(OffsetDateTime.now(RELOGIO));

        when(invoiceRepository.findById(idCobranca)).thenReturn(Optional.of(cobranca));
        when(repository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.saldo(any()))
                .thenReturn(new BigDecimal("199.90"))
                .thenReturn(new BigDecimal("99.90"));

        service.refund(idCobranca, pagamentoDe("100.00"));

        assertThat(cobranca.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
    }

    @Test
    @DisplayName("Estorno de pagamento parcial reabre a cobrança conforme o vencimento")
    void estornoDeParcialReabre() {
        Invoice cobranca = cobrancaDe("199.90", HOJE.plusDays(5));
        cobranca.markPartiallyPaid();

        when(invoiceRepository.findById(idCobranca)).thenReturn(Optional.of(cobranca));
        when(repository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.saldo(any()))
                .thenReturn(new BigDecimal("100.00"))
                .thenReturn(BigDecimal.ZERO);

        service.refund(idCobranca, pagamentoDe("100.00"));

        // Dentro do prazo → PENDING. Se estivesse vencida, voltaria OVERDUE.
        assertThat(cobranca.getStatus()).isEqualTo(InvoiceStatus.PENDING);
    }

    @Test
    @DisplayName("Estorno maior que o valor pago é recusado")
    void estornoMaiorQueOPago() {
        Invoice cobranca = cobrancaDe("199.90", HOJE.plusDays(5));
        cobranca.markPaid(OffsetDateTime.now(RELOGIO));

        when(invoiceRepository.findById(idCobranca)).thenReturn(Optional.of(cobranca));
        when(repository.saldo(any())).thenReturn(new BigDecimal("199.90"));

        assertThatThrownBy(() -> service.refund(idCobranca, pagamentoDe("500.00")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("excede");

        verify(repository, never()).saveAndFlush(any());
    }

    // =================================================================
    // Idempotência
    // =================================================================

    @Test
    @DisplayName("Mesma idempotencyKey devolve o lançamento anterior, sem duplicar")
    void idempotenciaPorChave() {
        UUID chave = UUID.randomUUID();
        Invoice cobranca = cobrancaDe("199.90", HOJE.plusDays(5));
        Payment anterior = new Payment(cobranca, PaymentType.PAYMENT, PaymentMethod.PIX,
                new BigDecimal("199.90"), OffsetDateTime.now(RELOGIO), null, chave);

        when(invoiceRepository.findById(idCobranca)).thenReturn(Optional.of(cobranca));
        when(repository.findByIdempotencyKey(chave)).thenReturn(Optional.of(anterior));

        service.register(idCobranca, new RegisterPaymentRequest(
                PaymentMethod.PIX, new BigDecimal("199.90"), null, null, chave));

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Mesmo providerRef devolve o lançamento anterior — webhook reentregue")
    void idempotenciaPorProviderRef() {
        Invoice cobranca = cobrancaDe("199.90", HOJE.plusDays(5));
        Payment anterior = new Payment(cobranca, PaymentType.PAYMENT, PaymentMethod.PIX,
                new BigDecimal("199.90"), OffsetDateTime.now(RELOGIO), "E2026-XYZ", null);

        when(invoiceRepository.findById(idCobranca)).thenReturn(Optional.of(cobranca));
        when(repository.findByProviderRef("E2026-XYZ")).thenReturn(Optional.of(anterior));

        service.register(idCobranca, new RegisterPaymentRequest(
                PaymentMethod.PIX, new BigDecimal("199.90"), null, "E2026-XYZ", null));

        verify(repository, never()).saveAndFlush(any());
    }

    // =================================================================
    // Efeito colateral no contrato
    // =================================================================

    @Test
    @DisplayName("Quitar tudo reativa a assinatura suspensa")
    void quitarReativaAssinatura() {
        Invoice cobranca = cobrancaDe("199.90", HOJE.minusDays(20));
        cobranca.markOverdue();
        Subscription assinatura = cobranca.getSubscription();
        assinatura.suspend(OffsetDateTime.now(RELOGIO));

        prepararCobranca(cobranca, "199.90");
        when(invoiceRepository.totalEmAberto(any())).thenReturn(BigDecimal.ZERO);

        service.register(idCobranca, pagamentoDe("199.90"));

        assertThat(assinatura.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(assinatura.getSuspendedAt()).isNull();
    }

    @Test
    @DisplayName("Quitar uma de várias em atraso NÃO reativa a assinatura")
    void quitarUmaDeVariasNaoReativa() {
        Invoice cobranca = cobrancaDe("199.90", HOJE.minusDays(20));
        cobranca.markOverdue();
        Subscription assinatura = cobranca.getSubscription();
        assinatura.suspend(OffsetDateTime.now(RELOGIO));

        prepararCobranca(cobranca, "199.90");
        // Ainda restam 399,80 em aberto de outras competências.
        when(invoiceRepository.totalEmAberto(any())).thenReturn(new BigDecimal("399.80"));

        service.register(idCobranca, pagamentoDe("199.90"));

        assertThat(assinatura.getStatus()).isEqualTo(SubscriptionStatus.SUSPENDED);
    }

    @Test
    @DisplayName("Cobrança cancelada não aceita pagamento")
    void canceladaNaoAceitaPagamento() {
        Invoice cobranca = cobrancaDe("199.90", HOJE.plusDays(5));
        cobranca.cancel();
        when(invoiceRepository.findById(idCobranca)).thenReturn(Optional.of(cobranca));

        assertThatThrownBy(() -> service.register(idCobranca, pagamentoDe("199.90")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cancelada");
    }
}

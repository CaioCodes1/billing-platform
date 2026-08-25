package com.caiocodes.billing.subscription.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import com.caiocodes.billing.customer.entity.Customer;
import com.caiocodes.billing.plan.entity.Plan;
import com.caiocodes.billing.shared.exception.BusinessRuleException;
import com.caiocodes.billing.subscription.domain.BillingCycle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Contrato entre um cliente e um plano.
 *
 * <p>Não há {@code setter} público para nada que importe. Toda mudança passa por
 * um método com nome de ação ({@link #activate()}, {@link #suspend()},
 * {@link #cancel()}), e cada um consulta {@link SubscriptionStatus#podeIrPara}
 * antes de mexer. Um {@code setStatus(CANCELLED)} solto tornaria possível
 * cancelar duas vezes, ressuscitar cancelada e suspender quem nunca começou.
 */
@Entity
@Table(name = "subscriptions")
@Getter
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // LAZY nas duas pontas: a listagem de assinaturas quase nunca precisa do
    // cliente inteiro, e EAGER traria um JOIN em toda query, inclusive nas que
    // só contam registros. Quem precisa do cliente pede com @EntityGraph.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    /**
     * Cópia do preço do plano no ato da contratação — não uma referência.
     * É isto que faz reajuste de plano valer só para contratos novos.
     */
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(nullable = false, length = 3)
    private String currency = "BRL";

    /** Dia contratado, 1..31. Ver {@link BillingCycle}. */
    @Column(name = "billing_day", nullable = false)
    private short billingDay;

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    @Column(name = "current_period_start", nullable = false)
    private LocalDate currentPeriodStart;

    @Column(name = "current_period_end", nullable = false)
    private LocalDate currentPeriodEnd;

    @Column(name = "next_renewal_date", nullable = false)
    private LocalDate nextRenewalDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionStatus status;

    @Column(name = "suspended_at")
    private OffsetDateTime suspendedAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected Subscription() {
        // exigido pelo JPA
    }

    /**
     * @param inicio data de início; se for futura a assinatura nasce PENDING
     */
    public Subscription(Customer customer, Plan plan, LocalDate inicio, LocalDate hoje) {
        this.customer = customer;
        this.plan = plan;
        // A cópia acontece aqui, uma única vez na vida da assinatura.
        this.unitPrice = plan.getMonthlyPrice();
        this.currency = plan.getCurrency();

        this.startDate = inicio;
        this.billingDay = (short) BillingCycle.diaDeCobranca(inicio);
        this.currentPeriodStart = inicio;
        this.currentPeriodEnd = BillingCycle.fimDoPeriodo(inicio, this.billingDay);
        this.nextRenewalDate = this.currentPeriodEnd;

        this.status = inicio.isAfter(hoje)
                ? SubscriptionStatus.PENDING
                : SubscriptionStatus.ACTIVE;
    }

    // ------------------------------------------------------------------
    // Transições
    // ------------------------------------------------------------------

    public void activate() {
        exigirTransicao(SubscriptionStatus.ACTIVE);
        this.status = SubscriptionStatus.ACTIVE;
        this.suspendedAt = null;
    }

    public void suspend(OffsetDateTime quando) {
        exigirTransicao(SubscriptionStatus.SUSPENDED);
        this.status = SubscriptionStatus.SUSPENDED;
        this.suspendedAt = quando;
    }

    public void cancel(OffsetDateTime quando) {
        exigirTransicao(SubscriptionStatus.CANCELLED);
        this.status = SubscriptionStatus.CANCELLED;
        this.cancelledAt = quando;
    }

    private void exigirTransicao(SubscriptionStatus destino) {
        if (!status.podeIrPara(destino)) {
            throw new BusinessRuleException("TRANSICAO_INVALIDA",
                    "Assinatura em %s não pode ir para %s.".formatted(status, destino));
        }
    }

    // ------------------------------------------------------------------
    // Ciclo
    // ------------------------------------------------------------------

    /**
     * Avança para o próximo período. Chamado pelo job de faturamento depois de
     * emitir a fatura da competência corrente.
     */
    public void advanceCycle() {
        this.currentPeriodStart = this.currentPeriodEnd;
        this.currentPeriodEnd = BillingCycle.fimDoPeriodo(this.currentPeriodStart, this.billingDay);
        this.nextRenewalDate = this.currentPeriodEnd;
    }

    /**
     * Move o contrato para o preço atual do plano.
     *
     * <p>Existe porque o preço é congelado: sem uma ação explícita, um cliente
     * antigo ficaria no valor de sempre para sempre. Deixar isso como operação
     * nomeada — em vez de efeito colateral de editar o plano — é justamente o
     * ponto: reprecificar contrato é decisão comercial e fica auditável.
     */
    public void migrateToCurrentPlanPrice() {
        if (status == SubscriptionStatus.CANCELLED) {
            throw new BusinessRuleException("ASSINATURA_CANCELADA",
                    "Não é possível reprecificar uma assinatura cancelada.");
        }
        this.unitPrice = plan.getMonthlyPrice();
        this.currency = plan.getCurrency();
    }

    public void changePlan(Plan novoPlano) {
        if (status == SubscriptionStatus.CANCELLED) {
            throw new BusinessRuleException("ASSINATURA_CANCELADA",
                    "Não é possível trocar o plano de uma assinatura cancelada.");
        }
        this.plan = novoPlano;
        this.unitPrice = novoPlano.getMonthlyPrice();
        this.currency = novoPlano.getCurrency();
    }

    public boolean isActive() {
        return status == SubscriptionStatus.ACTIVE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Subscription outra)) {
            return false;
        }
        return id != null && Objects.equals(id, outra.id);
    }

    @Override
    public int hashCode() {
        return Subscription.class.hashCode();
    }
}

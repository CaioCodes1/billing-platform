package com.caiocodes.billing.invoice.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import com.caiocodes.billing.shared.exception.BusinessRuleException;
import com.caiocodes.billing.subscription.entity.Subscription;

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
 * Cobrança de uma competência.
 *
 * <p>A identidade de negócio de uma fatura é o par
 * <strong>(assinatura, período)</strong> — não o id. É isso que o índice único
 * {@code uq_invoices_competencia} expressa, e é o que torna a emissão
 * repetível sem cobrar o cliente duas vezes.
 *
 * <p>O valor é copiado de {@code subscription.unitPrice}, que por sua vez já é
 * cópia do preço do plano. Duas cópias em série, de propósito: o plano pode
 * reajustar sem mexer no contrato, e o contrato pode ser reprecificado sem
 * mexer nas faturas já emitidas.
 */
@Entity
@Table(name = "invoices")
@Getter
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscription_id", nullable = false, updatable = false)
    private Subscription subscription;

    @Column(name = "period_start", nullable = false, updatable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false, updatable = false)
    private LocalDate periodEnd;

    @Column(nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvoiceStatus status = InvoiceStatus.PENDING;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected Invoice() {
        // exigido pelo JPA
    }

    public Invoice(Subscription subscription, LocalDate periodStart, LocalDate periodEnd,
                   BigDecimal amount, LocalDate dueDate) {
        this.subscription = subscription;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.amount = amount;
        this.currency = subscription.getCurrency();
        this.dueDate = dueDate;
        this.status = InvoiceStatus.PENDING;
    }

    // ------------------------------------------------------------------
    // Transições
    // ------------------------------------------------------------------

    /** Marca como vencida. Chamado pelo job diário. */
    public void markOverdue() {
        exigirTransicao(InvoiceStatus.OVERDUE);
        this.status = InvoiceStatus.OVERDUE;
    }

    public void markPartiallyPaid() {
        exigirTransicao(InvoiceStatus.PARTIALLY_PAID);
        this.status = InvoiceStatus.PARTIALLY_PAID;
        this.paidAt = null;
    }

    public void markPaid(OffsetDateTime quando) {
        exigirTransicao(InvoiceStatus.PAID);
        this.status = InvoiceStatus.PAID;
        // O CHECK do banco exige paid_at preenchido quando o status é PAID.
        // Carimbar aqui, junto da transição, torna impossível esquecer.
        this.paidAt = quando;
    }

    /**
     * Devolve a cobrança ao estado de aberta depois de um estorno que zerou o
     * saldo. Se já passou do vencimento volta como vencida, senão como
     * pendente — quem decide é o calendário, não o caminho percorrido.
     */
    public void reopen(LocalDate hoje) {
        InvoiceStatus destino = dueDate.isBefore(hoje)
                ? InvoiceStatus.OVERDUE
                : InvoiceStatus.PENDING;
        exigirTransicao(destino);
        this.status = destino;
        this.paidAt = null;
    }

    public void markRefunded() {
        exigirTransicao(InvoiceStatus.REFUNDED);
        this.status = InvoiceStatus.REFUNDED;
    }

    public void cancel() {
        exigirTransicao(InvoiceStatus.CANCELLED);
        this.status = InvoiceStatus.CANCELLED;
    }

    private void exigirTransicao(InvoiceStatus destino) {
        if (!status.podeIrPara(destino)) {
            throw new BusinessRuleException("TRANSICAO_INVALIDA",
                    "Cobrança em %s não pode ir para %s.".formatted(status, destino));
        }
    }

    // ------------------------------------------------------------------
    // Consultas de domínio
    // ------------------------------------------------------------------

    public boolean isVencidaEm(LocalDate data) {
        return status.emAberto() && dueDate.isBefore(data);
    }

    /** Dias de atraso em relação à data informada; zero se não estiver vencida. */
    public long diasDeAtrasoEm(LocalDate data) {
        if (!status.emAberto() || !dueDate.isBefore(data)) {
            return 0;
        }
        return java.time.temporal.ChronoUnit.DAYS.between(dueDate, data);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Invoice outra)) {
            return false;
        }
        return id != null && Objects.equals(id, outra.id);
    }

    @Override
    public int hashCode() {
        return Invoice.class.hashCode();
    }
}

package com.caiocodes.billing.payment.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import com.caiocodes.billing.invoice.entity.Invoice;

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
 * Um lançamento no livro-razão de uma cobrança.
 *
 * <p><strong>Imutável por construção e por trigger.</strong> Não há
 * {@code setter}, não há método de mudança, e o banco recusa {@code UPDATE} e
 * {@code DELETE} nesta tabela. Para desfazer, lança-se o contrário
 * ({@link PaymentType#REFUND}).
 *
 * <p>Por que assim: o status de uma cobrança é <em>derivado</em> da soma dos
 * lançamentos. Se um pagamento pudesse ser editado, o histórico deixaria de
 * explicar o saldo — e a pergunta "por que esta fatura está como paga?"
 * passaria a não ter resposta auditável. Com o razão imutável, o saldo é
 * sempre reconstruível a partir dos fatos.
 */
@Entity
@Table(name = "payments")
@Getter
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false)
    private Invoice invoice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private PaymentType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private PaymentMethod method;

    /** Sempre positivo. O sinal vem de {@link #type}. */
    @Column(nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "paid_at", nullable = false, updatable = false)
    private OffsetDateTime paidAt;

    /**
     * Identificador da transação no provedor de pagamento. Único quando
     * presente: um webhook reentregue pelo PSP não vira pagamento duplicado.
     */
    @Column(name = "provider_ref", updatable = false)
    private String providerRef;

    /**
     * Chave enviada pelo cliente da API. Única quando presente: clique duplo
     * no front ou retry de rede não vira pagamento duplicado.
     */
    @Column(name = "idempotency_key", updatable = false)
    private UUID idempotencyKey;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Payment() {
        // exigido pelo JPA
    }

    public Payment(Invoice invoice, PaymentType type, PaymentMethod method,
                   BigDecimal amount, OffsetDateTime paidAt,
                   String providerRef, UUID idempotencyKey) {
        this.invoice = invoice;
        this.type = type;
        this.method = method;
        this.amount = amount;
        this.paidAt = paidAt;
        this.providerRef = providerRef;
        this.idempotencyKey = idempotencyKey;
    }

    /** O valor com sinal, para somatórios. */
    public BigDecimal valorComSinal() {
        return type == PaymentType.REFUND ? amount.negate() : amount;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Payment outro)) {
            return false;
        }
        return id != null && Objects.equals(id, outro.id);
    }

    @Override
    public int hashCode() {
        return Payment.class.hashCode();
    }
}

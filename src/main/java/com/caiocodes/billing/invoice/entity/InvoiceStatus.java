package com.caiocodes.billing.invoice.entity;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Estados de uma cobrança.
 *
 * <pre>
 *   PENDING ⇄ OVERDUE
 *      │  ╲     │
 *      │   ╲    │
 *      ↓    ↘   ↓
 *   PARTIALLY_PAID ──→ PAID ──→ REFUNDED
 *      │           │       │
 *      └───────────┴───────┴──→ CANCELLED   (terminal)
 * </pre>
 *
 * <p>Repare que {@code OVERDUE} não é terminal e volta para pago: vencer é uma
 * situação, não um fim. Quem pagou com atraso paga a mesma fatura.
 */
public enum InvoiceStatus {

    /** Emitida, dentro do prazo. */
    PENDING,

    /** Recebeu pagamento, mas a soma ainda não cobre o valor. */
    PARTIALLY_PAID,

    /** Quitada. */
    PAID,

    /** Passou do vencimento sem quitação. Alimenta a régua de inadimplência. */
    OVERDUE,

    /** Anulada antes de ser paga — não entra em faturamento nem em inadimplência. */
    CANCELLED,

    /** Foi paga e depois devolvida (estorno PIX, chargeback de cartão). */
    REFUNDED;

    private static final Map<InvoiceStatus, Set<InvoiceStatus>> PERMITIDAS;

    static {
        Map<InvoiceStatus, Set<InvoiceStatus>> mapa = new EnumMap<>(InvoiceStatus.class);
        mapa.put(PENDING, EnumSet.of(PARTIALLY_PAID, PAID, OVERDUE, CANCELLED));
        // PARTIALLY_PAID volta a PENDING quando o pagamento parcial é estornado:
        // a fatura não recebeu nada e não passou do prazo, então é como se
        // nunca tivesse sido tocada.
        mapa.put(PARTIALLY_PAID, EnumSet.of(PENDING, PAID, OVERDUE, CANCELLED));
        mapa.put(OVERDUE, EnumSet.of(PARTIALLY_PAID, PAID, CANCELLED));
        // PAID → PARTIALLY_PAID cobre o estorno parcial: devolveu-se parte do
        // dinheiro, então a fatura deixou de estar quitada mas continua com
        // saldo. REFUNDED fica para o estorno que zera o saldo.
        mapa.put(PAID, EnumSet.of(PARTIALLY_PAID, REFUNDED));
        mapa.put(CANCELLED, EnumSet.noneOf(InvoiceStatus.class));
        mapa.put(REFUNDED, EnumSet.noneOf(InvoiceStatus.class));
        PERMITIDAS = Collections.unmodifiableMap(mapa);
    }

    public boolean podeIrPara(InvoiceStatus destino) {
        return PERMITIDAS.get(this).contains(destino);
    }

    /** Estados em que ainda se espera dinheiro do cliente. */
    public boolean emAberto() {
        return this == PENDING || this == PARTIALLY_PAID || this == OVERDUE;
    }

    /** Estados que contam como receita realizada no dashboard. */
    public boolean contaComoRecebido() {
        return this == PAID;
    }
}

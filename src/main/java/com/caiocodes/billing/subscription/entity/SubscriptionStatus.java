package com.caiocodes.billing.subscription.entity;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Estados de uma assinatura e as transições permitidas entre eles.
 *
 * <p>As transições ficam declaradas aqui, e não espalhadas em {@code if} pelo
 * service, por um motivo concreto: com a tabela abaixo, "reativar uma assinatura
 * cancelada" é impossível de escrever por engano — não existe caminho. Espalhado
 * em condicionais, bastaria um método novo esquecer uma verificação.
 *
 * <pre>
 *   PENDING ──→ ACTIVE ⇄ SUSPENDED
 *      │           │         │
 *      └───────────┴─────────┴──→ CANCELLED   (terminal)
 * </pre>
 */
public enum SubscriptionStatus {

    /** Contratada com data de início futura; ainda não gera cobrança. */
    PENDING,

    /** Em vigor: gera cobrança a cada ciclo. */
    ACTIVE,

    /** Inadimplente. O serviço parou, então a dívida também para de crescer. */
    SUSPENDED,

    /** Encerrada. Único estado que libera o cliente para assinar de novo. */
    CANCELLED;

    private static final Map<SubscriptionStatus, Set<SubscriptionStatus>> PERMITIDAS;

    static {
        Map<SubscriptionStatus, Set<SubscriptionStatus>> mapa =
                new EnumMap<>(SubscriptionStatus.class);
        mapa.put(PENDING, EnumSet.of(ACTIVE, CANCELLED));
        mapa.put(ACTIVE, EnumSet.of(SUSPENDED, CANCELLED));
        mapa.put(SUSPENDED, EnumSet.of(ACTIVE, CANCELLED));
        mapa.put(CANCELLED, EnumSet.noneOf(SubscriptionStatus.class));
        PERMITIDAS = Collections.unmodifiableMap(mapa);
    }

    public boolean podeIrPara(SubscriptionStatus destino) {
        return PERMITIDAS.get(this).contains(destino);
    }

    /** Estados em que a assinatura ocupa a vaga única do cliente. */
    public boolean ocupaVaga() {
        return this != CANCELLED;
    }

    /** Só assinatura ACTIVE gera cobrança — suspensa não acumula dívida. */
    public boolean geraCobranca() {
        return this == ACTIVE;
    }
}

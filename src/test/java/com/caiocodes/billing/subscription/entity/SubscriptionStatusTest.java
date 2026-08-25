package com.caiocodes.billing.subscription.entity;

import static com.caiocodes.billing.subscription.entity.SubscriptionStatus.ACTIVE;
import static com.caiocodes.billing.subscription.entity.SubscriptionStatus.CANCELLED;
import static com.caiocodes.billing.subscription.entity.SubscriptionStatus.PENDING;
import static com.caiocodes.billing.subscription.entity.SubscriptionStatus.SUSPENDED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class SubscriptionStatusTest {

    @ParameterizedTest(name = "{0} → {1} permitido")
    @CsvSource({
            "PENDING,   ACTIVE",
            "PENDING,   CANCELLED",
            "ACTIVE,    SUSPENDED",
            "ACTIVE,    CANCELLED",
            "SUSPENDED, ACTIVE",
            "SUSPENDED, CANCELLED"
    })
    void transicoesPermitidas(SubscriptionStatus de, SubscriptionStatus para) {
        assertThat(de.podeIrPara(para)).isTrue();
    }

    @ParameterizedTest(name = "{0} → {1} recusado")
    @CsvSource({
            // Cancelada é terminal: nenhum caminho de volta.
            "CANCELLED, ACTIVE",
            "CANCELLED, SUSPENDED",
            "CANCELLED, PENDING",
            "CANCELLED, CANCELLED",
            // Não se suspende quem nunca começou.
            "PENDING,   SUSPENDED",
            // Nem se volta para pendente depois de vigorar.
            "ACTIVE,    PENDING",
            "SUSPENDED, PENDING",
            // Repetir o próprio estado também não é transição.
            "ACTIVE,    ACTIVE",
            "SUSPENDED, SUSPENDED"
    })
    void transicoesRecusadas(SubscriptionStatus de, SubscriptionStatus para) {
        assertThat(de.podeIrPara(para)).isFalse();
    }

    @Test
    @DisplayName("Cancelada não vai para lugar nenhum")
    void canceladaEhTerminal() {
        assertThat(Arrays.stream(SubscriptionStatus.values()))
                .noneMatch(CANCELLED::podeIrPara);
    }

    @Test
    @DisplayName("Todo estado é alcançável a partir de PENDING")
    void todosAlcancaveis() {
        // Guarda contra criar um estado novo e esquecer de ligá-lo ao grafo.
        assertThat(PENDING.podeIrPara(ACTIVE)).isTrue();
        assertThat(ACTIVE.podeIrPara(SUSPENDED)).isTrue();
        assertThat(SUSPENDED.podeIrPara(CANCELLED)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(SubscriptionStatus.class)
    @DisplayName("Só CANCELLED libera a vaga do cliente")
    void ocupaVaga(SubscriptionStatus status) {
        assertThat(status.ocupaVaga()).isEqualTo(status != CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(SubscriptionStatus.class)
    @DisplayName("Só ACTIVE gera cobrança — suspensa não acumula dívida")
    void geraCobranca(SubscriptionStatus status) {
        assertThat(status.geraCobranca()).isEqualTo(status == ACTIVE);
    }
}

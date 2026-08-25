package com.caiocodes.billing.subscription.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BillingCycleTest {

    @Nested
    @DisplayName("Data dentro do mês")
    class DataNoMes {

        @ParameterizedTest(name = "dia {2} em {0}/{1} → {3}")
        @CsvSource({
                // ano, mês, dia contratado, data esperada
                "2026,  1, 31, 2026-01-31",  // mês de 31: sai inteiro
                "2026,  2, 31, 2026-02-28",  // fevereiro comum: limita a 28
                "2028,  2, 31, 2028-02-29",  // fevereiro bissexto: limita a 29
                "2026,  4, 31, 2026-04-30",  // mês de 30: limita a 30
                "2026,  2, 15, 2026-02-15",  // dia baixo: nunca é limitado
                "2026,  6,  1, 2026-06-01"
        })
        void limitaAoUltimoDiaDoMes(int ano, int mes, int dia, LocalDate esperada) {
            assertThat(BillingCycle.dataNoMes(YearMonth.of(ano, mes), dia)).isEqualTo(esperada);
        }

        @ParameterizedTest
        @CsvSource({"0", "32", "-1"})
        @DisplayName("Recusa dia fora de 1..31")
        void recusaDiaInvalido(int dia) {
            assertThatThrownBy(() -> BillingCycle.dataNoMes(YearMonth.of(2026, 3), dia))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("O bug do dia 31")
    class BugDoDia31 {

        @Test
        @DisplayName("Janeiro/31 fecha em fevereiro/28")
        void janeiroFechaEmFevereiro() {
            assertThat(BillingCycle.fimDoPeriodo(LocalDate.of(2026, 1, 31), 31))
                    .isEqualTo(LocalDate.of(2026, 2, 28));
        }

        @Test
        @DisplayName("Fevereiro/28 fecha em março/31 — o dia contratado volta")
        void marcoVoltaParaODia31() {
            // Este é o teste que justifica a existência da classe inteira.
            // Somando mês sobre a data limitada daria 28/03 e o cliente teria
            // virado "do dia 28" para sempre.
            assertThat(BillingCycle.fimDoPeriodo(LocalDate.of(2026, 2, 28), 31))
                    .isEqualTo(LocalDate.of(2026, 3, 31));
        }

        @Test
        @DisplayName("Doze ciclos a partir de 31/01 não perdem o dia 31")
        void dozeCiclosPreservamODia() {
            int diaContratado = 31;
            LocalDate corrente = LocalDate.of(2026, 1, 31);

            List<LocalDate> ciclos = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                corrente = BillingCycle.proximoInicio(corrente, diaContratado);
                ciclos.add(corrente);
            }

            assertThat(ciclos).containsExactly(
                    LocalDate.of(2026, 2, 28),  // fevereiro limita
                    LocalDate.of(2026, 3, 31),  // e março devolve o dia 31
                    LocalDate.of(2026, 4, 30),
                    LocalDate.of(2026, 5, 31),
                    LocalDate.of(2026, 6, 30),
                    LocalDate.of(2026, 7, 31),
                    LocalDate.of(2026, 8, 31),
                    LocalDate.of(2026, 9, 30),
                    LocalDate.of(2026, 10, 31),
                    LocalDate.of(2026, 11, 30),
                    LocalDate.of(2026, 12, 31),
                    LocalDate.of(2027, 1, 31));

            // Todo mês com 31 dias cobrou no dia 31: a limitação nunca virou
            // permanente.
            assertThat(ciclos).filteredOn(d -> d.lengthOfMonth() == 31)
                    .allMatch(d -> d.getDayOfMonth() == 31);
        }

        @Test
        @DisplayName("Dia 30 também sobrevive a fevereiro")
        void dia30SobreviveAFevereiro() {
            LocalDate fevereiro = BillingCycle.fimDoPeriodo(LocalDate.of(2026, 1, 30), 30);
            assertThat(fevereiro).isEqualTo(LocalDate.of(2026, 2, 28));

            assertThat(BillingCycle.fimDoPeriodo(fevereiro, 30))
                    .isEqualTo(LocalDate.of(2026, 3, 30));
        }
    }

    @Nested
    @DisplayName("Períodos e vencimento")
    class Periodos {

        @Test
        @DisplayName("O próximo período começa exatamente onde o anterior termina")
        void periodosSaoContiguos() {
            LocalDate inicio = LocalDate.of(2026, 3, 15);
            LocalDate fim = BillingCycle.fimDoPeriodo(inicio, 15);

            // Sem buraco e sem sobreposição: o fim de um é o começo do outro.
            assertThat(BillingCycle.proximoInicio(inicio, 15)).isEqualTo(fim);
        }

        @Test
        @DisplayName("Vencimento é o início da competência mais o prazo")
        void vencimento() {
            assertThat(BillingCycle.vencimento(LocalDate.of(2026, 3, 15), 10))
                    .isEqualTo(LocalDate.of(2026, 3, 25));
        }

        @Test
        @DisplayName("Dia de cobrança sai da data de início")
        void diaDeCobranca() {
            assertThat(BillingCycle.diaDeCobranca(LocalDate.of(2026, 1, 31))).isEqualTo(31);
            assertThat(BillingCycle.diaDeCobranca(LocalDate.of(2026, 6, 5))).isEqualTo(5);
        }
    }
}

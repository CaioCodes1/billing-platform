package com.caiocodes.billing.subscription.domain;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Aritmética do ciclo de cobrança.
 *
 * <p><strong>O problema que esta classe existe para resolver.</strong> Um cliente
 * assina dia 31/01. O ingênuo seria guardar a data do período e ir somando mês:
 *
 * <pre>
 *   31/01 .plusMonths(1) → 28/02   (o Java já limita corretamente)
 *   28/02 .plusMonths(1) → 28/03   ← e aqui o cliente virou "do dia 28"
 *   28/03 .plusMonths(1) → 28/04      para sempre
 * </pre>
 *
 * <p>O erro não é o {@code plusMonths}, que está certo. O erro é usar a data
 * <em>limitada</em> como base do próximo cálculo — a limitação vira permanente.
 *
 * <p>A correção é guardar o dia contratado (1..31) separado da data corrente e
 * recalcular sempre a partir dele:
 *
 * <pre>
 *   dia 31, fevereiro → 28/02
 *   dia 31, março     → 31/03   ← volta ao dia certo
 * </pre>
 *
 * <p>Todos os métodos são puros: mesma entrada, mesma saída, sem relógio e sem
 * banco. É o que torna esta classe testável sem subir nada.
 */
public final class BillingCycle {

    private BillingCycle() {
    }

    /**
     * O dia de cobrança contratado, extraído da data de início.
     */
    public static int diaDeCobranca(LocalDate inicio) {
        return inicio.getDayOfMonth();
    }

    /**
     * A data de cobrança dentro de um mês, limitada ao último dia dele.
     *
     * @param mes        mês de referência
     * @param diaDesejado dia contratado, de 1 a 31
     */
    public static LocalDate dataNoMes(YearMonth mes, int diaDesejado) {
        if (diaDesejado < 1 || diaDesejado > 31) {
            throw new IllegalArgumentException(
                    "dia de cobrança deve estar entre 1 e 31, recebido: " + diaDesejado);
        }
        return mes.atDay(Math.min(diaDesejado, mes.lengthOfMonth()));
    }

    /**
     * Fim do período que começa em {@code inicioDoPeriodo}.
     *
     * <p>É a data de cobrança do mês seguinte — sempre recalculada a partir do
     * dia contratado, nunca do dia limitado do mês anterior.
     */
    public static LocalDate fimDoPeriodo(LocalDate inicioDoPeriodo, int diaDeCobranca) {
        YearMonth proximoMes = YearMonth.from(inicioDoPeriodo).plusMonths(1);
        return dataNoMes(proximoMes, diaDeCobranca);
    }

    /**
     * Avança um ciclo: dado o início do período atual, devolve o início do
     * próximo. Por construção, é o mesmo valor que {@link #fimDoPeriodo} — o
     * período novo começa exatamente onde o anterior termina, sem buraco nem
     * sobreposição de um dia.
     */
    public static LocalDate proximoInicio(LocalDate inicioDoPeriodo, int diaDeCobranca) {
        return fimDoPeriodo(inicioDoPeriodo, diaDeCobranca);
    }

    /**
     * Vencimento da fatura de um período.
     *
     * @param inicioDoPeriodo início da competência cobrada
     * @param diasParaVencer  prazo de pagamento, vindo de configuração
     */
    public static LocalDate vencimento(LocalDate inicioDoPeriodo, int diasParaVencer) {
        return inicioDoPeriodo.plusDays(diasParaVencer);
    }
}

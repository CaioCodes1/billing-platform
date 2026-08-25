package com.caiocodes.billing.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * O agendamento — e só ele. A lógica está em {@link BillingRunner}.
 *
 * <p><strong>Por que ShedLock.</strong> {@code @Scheduled} dispara em
 * <em>toda</em> instância da aplicação. Com duas réplicas, o faturamento roda
 * duas vezes no mesmo minuto. O índice único de {@code invoices} já impede a
 * cobrança dupla, mas o trabalho seria feito em dobro, os logs ficariam
 * ilegíveis e as duas execuções disputariam as mesmas linhas. O ShedLock elege
 * uma instância por execução, usando a própria tabela {@code shedlock} como
 * árbitro — sem Redis, sem ZooKeeper, sem infraestrutura nova.
 *
 * <p>{@code lockAtMostFor} é a rede de segurança: se a instância que ganhou o
 * lock morrer no meio, o lock expira sozinho e a próxima execução assume. Deve
 * ser confortavelmente maior que a duração normal do job.
 *
 * <p>{@code lockAtLeastFor} evita que duas instâncias com relógios levemente
 * diferentes rodem em sequência imediata.
 */
@Component
@ConditionalOnProperty(name = "billing.scheduler.enabled", havingValue = "true",
        matchIfMissing = true)
public class BillingScheduler {

    private final BillingRunner runner;

    public BillingScheduler(BillingRunner runner) {
        this.runner = runner;
    }

    /**
     * Todo dia às 03:00 no fuso configurado — janela de baixa carga, e depois
     * da virada do dia para que "vencido hoje" já esteja correto.
     */
    @Scheduled(cron = "${billing.scheduler.cron:0 0 3 * * *}", zone = "${billing.timezone}")
    @SchedulerLock(name = "cicloDiarioDeFaturamento",
            lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void cicloDiario() {
        runner.executarCicloDiario();
    }
}

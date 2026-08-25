package com.caiocodes.billing.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Relógio da aplicação, como dependência injetável.
 *
 * <p>Nenhum service deste projeto chama {@code LocalDate.now()} direto. Dois
 * motivos, e o segundo é o que dói:
 *
 * <ol>
 *   <li><strong>Testabilidade.</strong> "A assinatura suspensa há 15 dias é
 *       cancelada" só é testável em segundos se der para dizer que dia é hoje.
 *       Com {@code now()} embutido, o teste vira {@code Thread.sleep} ou
 *       maquiagem de dados.</li>
 *   <li><strong>Fuso.</strong> {@code LocalDate.now()} usa o fuso do sistema
 *       operacional. Em container isso costuma ser UTC — e às 22h de Brasília
 *       já é o dia seguinte em UTC. Uma fatura venceria um dia antes para o
 *       cliente. Aqui o fuso vem de {@code billing.timezone} e é o mesmo em
 *       qualquer máquina.</li>
 * </ol>
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock(BillingProperties properties) {
        return Clock.system(properties.zone());
    }
}

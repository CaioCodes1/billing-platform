package com.caiocodes.billing.config;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulerConfig {

    /**
     * O lock vive na tabela {@code shedlock}, criada pela migration V5.
     *
     * <p>{@code usingDbTime()} faz a comparação de tempo acontecer no banco, e
     * não em cada JVM. É o detalhe que importa: com relógios levemente
     * dessincronizados entre réplicas, duas instâncias podem discordar sobre se
     * o lock expirou — e as duas rodarem. Com o tempo do banco há um relógio só.
     */
    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}

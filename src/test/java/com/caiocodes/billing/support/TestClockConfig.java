package com.caiocodes.billing.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Relógio que o teste controla.
 *
 * <p>Sem isto, testar "assinatura suspensa há 15 dias é cancelada" exigiria
 * esperar 15 dias ou maquiar datas no banco. Com um relógio ajustável, o ciclo
 * de um ano inteiro roda em milissegundos e de forma determinística.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

    @Bean
    @Primary
    TestClock testClock() {
        return new TestClock(
                Instant.parse("2026-03-01T09:00:00Z"),
                ZoneId.of("America/Sao_Paulo"));
    }

    /** {@link Clock} mutável: o teste avança o tempo quando quer. */
    public static class TestClock extends Clock {

        private Instant instante;
        private final ZoneId zona;

        public TestClock(Instant instante, ZoneId zona) {
            this.instante = instante;
            this.zona = zona;
        }

        @Override
        public ZoneId getZone() {
            return zona;
        }

        @Override
        public Clock withZone(ZoneId outra) {
            return new TestClock(instante, outra);
        }

        @Override
        public Instant instant() {
            return instante;
        }

        public void avancarDias(long dias) {
            this.instante = instante.plus(Duration.ofDays(dias));
        }

        public void definir(Instant novo) {
            this.instante = novo;
        }
    }
}

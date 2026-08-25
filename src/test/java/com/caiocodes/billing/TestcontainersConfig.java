package com.caiocodes.billing;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Postgres real para os testes de integração.
 *
 * <p>Por que não H2: metade das garantias deste sistema é do banco, não do
 * código — índice único parcial, {@code CHECK}, trigger de append-only,
 * {@code SKIP LOCKED}. O H2 não implementa índice único parcial, então um
 * teste que passasse nele estaria justamente deixando de verificar a regra
 * "um cliente, uma assinatura ativa".
 *
 * <p>O container é um {@code @Bean}: o Spring cuida do ciclo de vida e o cache
 * de contexto de teste faz com que todas as classes que importam esta
 * configuração compartilhem o mesmo container, em vez de subir um por classe.
 * {@code @ServiceConnection} aponta o datasource para ele sem nenhuma
 * propriedade escrita à mão.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    }
}

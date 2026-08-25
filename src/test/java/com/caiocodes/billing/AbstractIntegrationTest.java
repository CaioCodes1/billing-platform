package com.caiocodes.billing;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base dos testes de integração: sobe o contexto inteiro contra o Postgres
 * do {@link TestcontainersConfig}, com as migrations do Flyway já aplicadas.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {
}

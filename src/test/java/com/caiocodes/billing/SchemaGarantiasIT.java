package com.caiocodes.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Testa as garantias que moram no banco, não no código.
 *
 * <p>Estes testes existem porque as três regras mais importantes do sistema —
 * uma assinatura ativa por cliente, uma cobrança por competência e pagamento
 * imutável — não são verificáveis por teste unitário de service: elas só
 * valem se a constraint estiver de fato no schema. Um refactor que apague o
 * índice quebra aqui, não em produção.
 */
class SchemaGarantiasIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Flyway aplicou todas as migrations")
    void migrationsAplicadas() {
        Integer aplicadas = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);

        assertThat(aplicadas).isEqualTo(5);
    }

    @Test
    @DisplayName("Cliente não pode ocupar duas vagas de assinatura ao mesmo tempo")
    void umaAssinaturaAtivaPorCliente() {
        UUID cliente = criarCliente();
        UUID plano = criarPlano();
        criarAssinatura(cliente, plano, "ACTIVE");

        // O índice único parcial uq_subscriptions_active_slot é o que resolve
        // a corrida entre duas requisições simultâneas. Sem ele, um if no
        // service deixaria as duas passarem.
        assertThatThrownBy(() -> criarAssinatura(cliente, plano, "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Assinatura SUSPENDED continua ocupando a vaga do cliente")
    void suspensoNaoLiberaVaga() {
        UUID cliente = criarCliente();
        UUID plano = criarPlano();
        criarAssinatura(cliente, plano, "SUSPENDED");

        // Regra de negócio: o inadimplente não escapa da dívida abrindo
        // assinatura nova. Só o cancelamento libera a vaga.
        assertThatThrownBy(() -> criarAssinatura(cliente, plano, "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Cancelamento libera o cliente para assinar de novo")
    void canceladoLiberaVaga() {
        UUID cliente = criarCliente();
        UUID plano = criarPlano();
        criarAssinatura(cliente, plano, "CANCELLED");

        assertThatCode(() -> criarAssinatura(cliente, plano, "ACTIVE"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A mesma competência não pode ser cobrada duas vezes")
    void emissaoIdempotente() {
        UUID assinatura = criarAssinatura(criarCliente(), criarPlano(), "ACTIVE");
        LocalDate competencia = LocalDate.of(2026, 9, 1);
        criarCobranca(assinatura, competencia);

        // É esta constraint que torna o job de faturamento seguro para rodar
        // duas vezes: reinício no meio, retry, ou duas réplicas subindo juntas.
        assertThatThrownBy(() -> criarCobranca(assinatura, competencia))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Pagamento não pode ser alterado nem apagado")
    void pagamentoEhAppendOnly() {
        UUID assinatura = criarAssinatura(criarCliente(), criarPlano(), "ACTIVE");
        UUID cobranca = criarCobranca(assinatura, LocalDate.of(2026, 9, 1));
        UUID pagamento = criarPagamento(cobranca, "PAYMENT", "99.90");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payments SET amount = 1 WHERE id = ?", pagamento))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbc.update("DELETE FROM payments WHERE id = ?", pagamento))
                .isInstanceOf(DataAccessException.class);

        // O caminho correto para desfazer é o lançamento contrário.
        assertThatCode(() -> criarPagamento(cobranca, "REFUND", "99.90"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Estorno gravado com valor negativo é recusado")
    void valorSemprePositivo() {
        UUID assinatura = criarAssinatura(criarCliente(), criarPlano(), "ACTIVE");
        UUID cobranca = criarCobranca(assinatura, LocalDate.of(2026, 9, 1));

        // Quem dá o sinal é a coluna type. Permitir valor negativo abriria a
        // porta para "estorno positivo" passar despercebido na soma.
        assertThatThrownBy(() -> criarPagamento(cobranca, "REFUND", "-10.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private UUID criarCliente() {
        UUID id = UUID.randomUUID();
        String sufixo = id.toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO customers (id, name, email, document, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """, id, "Cliente " + sufixo, sufixo + "@teste.local", documentoAleatorio());
        return id;
    }

    private UUID criarPlano() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO plans (id, name, monthly_price, user_limit)
                VALUES (?, ?, 99.90, 10)
                """, id, "Plano " + id.toString().substring(0, 8));
        return id;
    }

    private UUID criarAssinatura(UUID cliente, UUID plano, String status) {
        UUID id = UUID.randomUUID();
        LocalDate inicio = LocalDate.of(2026, 9, 1);
        jdbc.update("""
                INSERT INTO subscriptions (
                    id, customer_id, plan_id, unit_price, billing_day, start_date,
                    current_period_start, current_period_end, next_renewal_date,
                    status, suspended_at, cancelled_at)
                VALUES (?, ?, ?, 99.90, 1, ?, ?, ?, ?, ?,
                        CASE WHEN ? = 'SUSPENDED' THEN now() END,
                        CASE WHEN ? = 'CANCELLED' THEN now() END)
                """,
                id, cliente, plano, inicio, inicio, inicio.plusMonths(1),
                inicio.plusMonths(1), status, status, status);
        return id;
    }

    private UUID criarCobranca(UUID assinatura, LocalDate competencia) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO invoices (id, subscription_id, period_start, period_end,
                                      amount, due_date)
                VALUES (?, ?, ?, ?, 99.90, ?)
                """, id, assinatura, competencia, competencia.plusMonths(1),
                competencia.plusDays(10));
        return id;
    }

    private UUID criarPagamento(UUID cobranca, String tipo, String valor) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payments (id, invoice_id, type, method, amount, paid_at)
                VALUES (?, ?, ?, 'PIX', CAST(? AS NUMERIC), now())
                """, id, cobranca, tipo, valor);
        return id;
    }

    /** CPF de 11 dígitos, único por execução. Formato só — sem dígito verificador. */
    private String documentoAleatorio() {
        long n = Math.abs(UUID.randomUUID().getMostSignificantBits() % 100_000_000_000L);
        return String.format("%011d", n);
    }
}

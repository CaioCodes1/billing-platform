package com.caiocodes.billing.dashboard.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.caiocodes.billing.invoice.entity.Invoice;

/**
 * Consultas agregadas do dashboard.
 *
 * <p>Tudo em SQL nativo e agregado <strong>no banco</strong>. A alternativa —
 * carregar as cobranças e somar em Java — funcionaria com mil registros e
 * derrubaria a aplicação com um milhão. Relatório é o caso em que o banco faz
 * o trabalho e a aplicação só apresenta.
 *
 * <p>Estende {@code Repository} puro, sem {@code JpaRepository}: este é um
 * repositório de leitura, e expor {@code save}/{@code delete} aqui seria
 * convidar escrita por um caminho que não passa por regra nenhuma.
 */
public interface DashboardRepository extends Repository<Invoice, UUID> {

    /**
     * Faturado por mês de competência.
     *
     * <p>Canceladas ficam de fora: cobrança anulada nunca foi receita.
     */
    @Query(value = """
            SELECT EXTRACT(YEAR FROM i.period_start)::int  AS ano,
                   EXTRACT(MONTH FROM i.period_start)::int AS mes,
                   COALESCE(SUM(i.amount), 0)              AS total,
                   COUNT(*)                                AS quantidade
            FROM invoices i
            WHERE i.status <> 'CANCELLED'
              AND EXTRACT(YEAR FROM i.period_start) = :ano
            GROUP BY 1, 2
            ORDER BY 2
            """, nativeQuery = true)
    List<Object[]> faturadoPorMes(@Param("ano") int ano);

    /**
     * Recebido por mês de <strong>pagamento</strong>, líquido de estornos.
     *
     * <p>O agrupamento é por {@code paid_at}, não pela competência da cobrança:
     * dinheiro que entrou em abril referente a março é caixa de abril. É essa
     * distinção que faz o relatório servir para fluxo de caixa.
     */
    @Query(value = """
            SELECT EXTRACT(YEAR FROM p.paid_at)::int  AS ano,
                   EXTRACT(MONTH FROM p.paid_at)::int AS mes,
                   COALESCE(SUM(CASE WHEN p.type = 'REFUND' THEN -p.amount
                                     ELSE p.amount END), 0) AS total
            FROM payments p
            WHERE EXTRACT(YEAR FROM p.paid_at) = :ano
            GROUP BY 1, 2
            ORDER BY 2
            """, nativeQuery = true)
    List<Object[]> recebidoPorMes(@Param("ano") int ano);

    @Query(value = """
            SELECT COALESCE(SUM(CASE WHEN p.type = 'REFUND' THEN -p.amount
                                     ELSE p.amount END), 0)
            FROM payments p
            """, nativeQuery = true)
    BigDecimal totalRecebido();

    @Query(value = """
            SELECT COALESCE(SUM(i.amount), 0) FROM invoices i
            WHERE i.status IN ('PENDING', 'PARTIALLY_PAID', 'OVERDUE')
            """, nativeQuery = true)
    BigDecimal totalEmAberto();

    @Query(value = """
            SELECT COALESCE(SUM(i.amount), 0) FROM invoices i
            WHERE i.status = 'OVERDUE'
            """, nativeQuery = true)
    BigDecimal totalVencido();

    @Query(value = "SELECT COUNT(*) FROM subscriptions WHERE status = :status",
            nativeQuery = true)
    long contarAssinaturasPor(@Param("status") String status);

    /**
     * Receita recorrente mensal (MRR): a soma do valor <em>contratado</em> das
     * assinaturas em vigor.
     *
     * <p>Usa {@code unit_price} da assinatura, não o preço do plano — senão um
     * reajuste de catálogo inflaria o MRR de contratos que ainda pagam o valor
     * antigo, e o número deixaria de descrever a realidade.
     */
    @Query(value = """
            SELECT COALESCE(SUM(s.unit_price), 0) FROM subscriptions s
            WHERE s.status = 'ACTIVE'
            """, nativeQuery = true)
    BigDecimal receitaRecorrenteMensal();

    @Query(value = """
            SELECT COUNT(DISTINCT s.customer_id)
            FROM invoices i JOIN subscriptions s ON s.id = i.subscription_id
            WHERE i.status = 'OVERDUE'
            """, nativeQuery = true)
    long contarClientesInadimplentes();

    @Query(value = """
            SELECT c.id                          AS customer_id,
                   c.name                        AS customer_name,
                   c.email                       AS customer_email,
                   COUNT(i.id)                   AS overdue_invoices,
                   COALESCE(SUM(i.amount), 0)    AS overdue_amount,
                   MIN(i.due_date)               AS oldest_due_date,
                   (:hoje - MIN(i.due_date))     AS days_late
            FROM invoices i
            JOIN subscriptions s ON s.id = i.subscription_id
            JOIN customers c     ON c.id = s.customer_id
            WHERE i.status = 'OVERDUE'
            GROUP BY c.id, c.name, c.email
            ORDER BY MIN(i.due_date)
            """, nativeQuery = true)
    List<Object[]> clientesInadimplentes(@Param("hoje") LocalDate hoje);
}

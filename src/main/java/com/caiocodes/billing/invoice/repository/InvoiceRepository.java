package com.caiocodes.billing.invoice.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.caiocodes.billing.invoice.entity.Invoice;
import com.caiocodes.billing.invoice.entity.InvoiceStatus;

public interface InvoiceRepository
        extends JpaRepository<Invoice, UUID>, JpaSpecificationExecutor<Invoice> {

    /**
     * A consulta que torna a emissão idempotente.
     *
     * <p>Antes de inserir, o serviço pergunta se a competência já foi emitida.
     * O índice único continua sendo a garantia final, mas confiar só nele teria
     * um custo: no Postgres, violar constraint <strong>aborta a transação
     * inteira</strong> — não dá para capturar a exceção e seguir trabalhando na
     * mesma transação. Num job que processa uma página de assinaturas por
     * transação, isso derrubaria as outras assinaturas da página junto.
     */
    Optional<Invoice> findBySubscriptionIdAndPeriodStart(UUID subscriptionId,
                                                         LocalDate periodStart);

    boolean existsBySubscriptionIdAndPeriodStart(UUID subscriptionId, LocalDate periodStart);

    List<Invoice> findBySubscriptionIdOrderByPeriodStartDesc(UUID subscriptionId);

    /** Alimenta o passo "vencer" do job diário. */
    List<Invoice> findByStatusInAndDueDateBefore(Collection<InvoiceStatus> status,
                                                 LocalDate data);

    /**
     * Total em aberto de uma assinatura — usado para decidir se a reativação
     * pode acontecer.
     */
    @Query("""
            SELECT COALESCE(SUM(i.amount), 0)
            FROM Invoice i
            WHERE i.subscription.id = :subscriptionId
              AND i.status IN (
                  com.caiocodes.billing.invoice.entity.InvoiceStatus.PENDING,
                  com.caiocodes.billing.invoice.entity.InvoiceStatus.PARTIALLY_PAID,
                  com.caiocodes.billing.invoice.entity.InvoiceStatus.OVERDUE)
            """)
    java.math.BigDecimal totalEmAberto(@Param("subscriptionId") UUID subscriptionId);

    /**
     * Passo 3 do job: assinaturas com cobrança vencida além do prazo.
     *
     * <p>Query nativa porque a diferença entre datas em dias é expressão de SQL,
     * não de JPQL. A projeção devolve só o id e o atraso — o job não precisa da
     * cobrança inteira para decidir suspender.
     */
    @Query(value = """
            SELECT i.subscription_id AS subscriptionId,
                   MAX(:hoje - i.due_date) AS diasEmAtraso
            FROM invoices i
            JOIN subscriptions s ON s.id = i.subscription_id
            WHERE i.status = 'OVERDUE'
              AND s.status = 'ACTIVE'
              AND (:hoje - i.due_date) >= :diasDeTolerancia
            GROUP BY i.subscription_id
            """, nativeQuery = true)
    List<OverdueProjection> assinaturasParaSuspender(
            @Param("hoje") LocalDate hoje,
            @Param("diasDeTolerancia") int diasDeTolerancia);

    @Override
    @EntityGraph(attributePaths = {"subscription", "subscription.customer", "subscription.plan"})
    Page<Invoice> findAll(Specification<Invoice> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"subscription", "subscription.customer", "subscription.plan"})
    Optional<Invoice> findById(UUID id);
}

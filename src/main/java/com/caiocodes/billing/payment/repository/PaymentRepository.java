package com.caiocodes.billing.payment.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.caiocodes.billing.payment.entity.Payment;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findByInvoiceIdOrderByPaidAtAsc(UUID invoiceId);

    Optional<Payment> findByIdempotencyKey(UUID idempotencyKey);

    Optional<Payment> findByProviderRef(String providerRef);

    /**
     * Saldo de uma cobrança: soma dos PAYMENT menos a dos REFUND.
     *
     * <p>Feito em SQL, e não carregando os lançamentos para somar em memória,
     * por dois motivos: uma cobrança com muitos lançamentos parciais não
     * precisa vir inteira para a JVM, e a soma passa a ser um único número
     * consistente dentro da transação.
     */
    @Query("""
            SELECT COALESCE(SUM(
                CASE WHEN p.type = com.caiocodes.billing.payment.entity.PaymentType.REFUND
                     THEN -p.amount ELSE p.amount END), 0)
            FROM Payment p
            WHERE p.invoice.id = :invoiceId
            """)
    BigDecimal saldo(@Param("invoiceId") UUID invoiceId);
}

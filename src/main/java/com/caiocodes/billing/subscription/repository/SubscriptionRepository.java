package com.caiocodes.billing.subscription.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.caiocodes.billing.subscription.entity.Subscription;
import com.caiocodes.billing.subscription.entity.SubscriptionStatus;

public interface SubscriptionRepository
        extends JpaRepository<Subscription, UUID>, JpaSpecificationExecutor<Subscription> {

    /**
     * A vaga única do cliente. Serve para a mensagem de erro; a garantia é o
     * índice parcial {@code uq_subscriptions_active_slot}.
     */
    boolean existsByCustomerIdAndStatusIn(UUID customerId, Collection<SubscriptionStatus> status);

    // ------------------------------------------------------------------
    // @EntityGraph: o remédio para o N+1
    // ------------------------------------------------------------------
    // customer e plan são LAZY. Sem o grafo, listar 20 assinaturas e serializar
    // o nome do cliente de cada uma dispara 1 query da página + 20 do cliente
    // + 20 do plano = 41 idas ao banco. Com ele, é uma só, com JOIN.
    //
    // Vale porque as duas associações são ...ToOne. Com coleção, join fetch +
    // paginação faria o Hibernate paginar em memória — aí a cura seria pior.

    // ------------------------------------------------------------------
    // Consultas do job diário
    // ------------------------------------------------------------------
    // Todas devolvem Page, nunca List: com milhares de assinaturas, carregar
    // tudo de uma vez é OOM na hora errada. O job processa uma página por
    // transação — falha na página 40 não desfaz as 39 anteriores.

    /** Passo 0: assinaturas futuras cujo dia chegou. */
    Page<Subscription> findByStatusAndStartDateLessThanEqual(
            SubscriptionStatus status, LocalDate data, Pageable pageable);

    /** Passo 1: assinaturas com renovação próxima, a faturar. */
    Page<Subscription> findByStatusAndNextRenewalDateLessThanEqual(
            SubscriptionStatus status, LocalDate limite, Pageable pageable);

    /** Passo 4: suspensas há tempo demais, a encerrar. */
    Page<Subscription> findByStatusAndSuspendedAtBefore(
            SubscriptionStatus status, OffsetDateTime limite, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"customer", "plan"})
    Page<Subscription> findAll(Specification<Subscription> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"customer", "plan"})
    Optional<Subscription> findById(UUID id);
}

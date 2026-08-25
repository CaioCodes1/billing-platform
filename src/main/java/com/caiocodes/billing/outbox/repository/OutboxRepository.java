package com.caiocodes.billing.outbox.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.caiocodes.billing.outbox.entity.OutboxMessage;

public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {

    /**
     * Reserva um lote de mensagens para este worker.
     *
     * <p><strong>{@code FOR UPDATE SKIP LOCKED} é o coração disto.</strong> Sem
     * ele, duas réplicas leriam as mesmas linhas e mandariam o e-mail duas
     * vezes. Com {@code FOR UPDATE} sozinho, a segunda réplica ficaria
     * bloqueada esperando a primeira — serializando o despacho. O
     * {@code SKIP LOCKED} faz a segunda simplesmente <em>pular</em> as linhas
     * travadas e pegar as próximas: as duas trabalham em paralelo, em conjuntos
     * disjuntos, sem coordenação externa.
     *
     * <p>É o mesmo mecanismo que bancos de fila usam por baixo. Aqui ele sai de
     * graça, sem trazer RabbitMQ ou Kafka para o projeto.
     */
    @Query(value = """
            SELECT * FROM outbox_messages
            WHERE status = 'PENDING' AND next_attempt_at <= :agora
            ORDER BY next_attempt_at
            LIMIT :limite
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxMessage> reservarLote(@Param("agora") OffsetDateTime agora,
                                     @Param("limite") int limite);

    long countByStatus(com.caiocodes.billing.outbox.entity.OutboxStatus status);
}

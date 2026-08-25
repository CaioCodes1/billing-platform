package com.caiocodes.billing.outbox.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Um evento a despachar, gravado na mesma transação do dado que o originou.
 *
 * <p>O problema que isto resolve: mandar e-mail dentro de {@code @Transactional}
 * tem dois defeitos. O SMTP lento segura a conexão do banco aberta; e um
 * rollback depois do envio deixa o cliente com um aviso de cobrança que não
 * existe — e não dá para "des-enviar".
 *
 * <p>Com o outbox, o evento e o dado vão para o banco juntos: ou os dois
 * existem, ou nenhum existe. Um worker separado lê os pendentes e despacha. O
 * e-mail passa a poder falhar e ser retentado sem arrastar a regra de negócio.
 */
@Entity
@Table(name = "outbox_messages")
@Getter
public class OutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 50, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 60, updatable = false)
    private OutboxEventType eventType;

    /** Snapshot do que o e-mail precisa, em JSONB. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(nullable = false)
    private short attempts = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    protected OutboxMessage() {
        // exigido pelo JPA
    }

    public OutboxMessage(String aggregateType, UUID aggregateId,
                         OutboxEventType eventType, String payload,
                         OffsetDateTime agora) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.nextAttemptAt = agora;
    }

    public void marcarEnviado(OffsetDateTime agora) {
        this.status = OutboxStatus.SENT;
        this.sentAt = agora;
        this.lastError = null;
    }

    /**
     * Registra a falha e reagenda com backoff exponencial (1, 2, 4, 8... min).
     *
     * <p>Depois de {@code maxTentativas}, para de tentar e vira FAILED. Insistir
     * para sempre num endereço inválido só enche a fila e atrasa o que é
     * entregável.
     */
    public void registrarFalha(String erro, int maxTentativas, OffsetDateTime agora) {
        this.attempts++;
        this.lastError = erro == null ? "erro sem mensagem"
                : erro.substring(0, Math.min(erro.length(), 1000));

        if (attempts >= maxTentativas) {
            this.status = OutboxStatus.FAILED;
        } else {
            long minutos = (long) Math.pow(2, attempts - 1);
            this.nextAttemptAt = agora.plusMinutes(minutos);
        }
    }
}

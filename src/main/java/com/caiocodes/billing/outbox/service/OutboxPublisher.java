package com.caiocodes.billing.outbox.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.caiocodes.billing.outbox.entity.OutboxEventType;
import com.caiocodes.billing.outbox.entity.OutboxMessage;
import com.caiocodes.billing.outbox.repository.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Grava eventos no outbox.
 *
 * <p>{@code Propagation.MANDATORY} não é decoração: obriga que exista uma
 * transação em andamento. Publicar fora de transação derrotaria o propósito do
 * padrão — o evento poderia existir sem o dado que o originou. Se alguém
 * chamar isto de fora, o Spring recusa em vez de deixar passar.
 */
@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxPublisher(OutboxRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publicar(String aggregateType, UUID aggregateId,
                         OutboxEventType tipo, Map<String, Object> dados) {
        try {
            repository.save(new OutboxMessage(
                    aggregateType,
                    aggregateId,
                    tipo,
                    objectMapper.writeValueAsString(dados),
                    OffsetDateTime.now(clock)));

            log.debug("Evento {} enfileirado para {} {}", tipo, aggregateType, aggregateId);
        } catch (JsonProcessingException e) {
            // Falhar aqui derruba a transação de negócio junto — o que é
            // correto: um evento que não serializa é bug de programação, não
            // condição de runtime a ser tolerada.
            throw new IllegalStateException(
                    "Não foi possível serializar o payload do evento " + tipo, e);
        }
    }
}

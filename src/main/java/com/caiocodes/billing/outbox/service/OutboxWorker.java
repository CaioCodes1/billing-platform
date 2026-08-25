package com.caiocodes.billing.outbox.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.caiocodes.billing.outbox.entity.OutboxMessage;
import com.caiocodes.billing.outbox.repository.OutboxRepository;

/**
 * Consome a fila do outbox e despacha os e-mails.
 *
 * <p>Diferente do job de faturamento, este <strong>não</strong> usa ShedLock —
 * e é de propósito. O faturamento precisa de uma execução só porque cria dados;
 * o despacho de e-mail se beneficia de várias réplicas trabalhando em paralelo.
 * O {@code FOR UPDATE SKIP LOCKED} da consulta já garante que cada mensagem vá
 * para um worker só. Travar a execução inteira desperdiçaria capacidade.
 */
@Component
@ConditionalOnProperty(name = "billing.outbox.enabled", havingValue = "true",
        matchIfMissing = true)
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private static final int TAMANHO_DO_LOTE = 50;
    private static final int MAX_TENTATIVAS = 5;

    private final OutboxRepository repository;
    private final EmailDispatcher dispatcher;
    private final Clock clock;

    public OutboxWorker(OutboxRepository repository, EmailDispatcher dispatcher, Clock clock) {
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${billing.outbox.interval:PT30S}")
    public void despacharPendentes() {
        int enviados = processarLote();
        if (enviados > 0) {
            log.info("{} mensagens do outbox despachadas", enviados);
        }
    }

    /**
     * Um lote por transação.
     *
     * <p>A transação precisa envolver a consulta e a marcação, porque é ela que
     * segura os locks do {@code SKIP LOCKED}. Se terminasse antes do despacho,
     * outra réplica poderia pegar as mesmas linhas.
     */
    @Transactional
    public int processarLote() {
        OffsetDateTime agora = OffsetDateTime.now(clock);
        List<OutboxMessage> lote = repository.reservarLote(agora, TAMANHO_DO_LOTE);

        int enviados = 0;
        for (OutboxMessage mensagem : lote) {
            try {
                dispatcher.despachar(mensagem);
                mensagem.marcarEnviado(agora);
                enviados++;
            } catch (Exception e) {
                // Falha de envio é esperada (SMTP fora do ar, caixa cheia).
                // Registra, reagenda com backoff e segue para a próxima.
                mensagem.registrarFalha(e.getMessage(), MAX_TENTATIVAS, agora);
                log.warn("Falha ao despachar evento {} (tentativa {}): {}",
                        mensagem.getId(), mensagem.getAttempts(), e.getMessage());
            }
        }
        return enviados;
    }
}

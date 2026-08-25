package com.caiocodes.billing.scheduler;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.caiocodes.billing.invoice.service.InvoiceService;
import com.caiocodes.billing.outbox.entity.OutboxEventType;
import com.caiocodes.billing.outbox.service.OutboxPublisher;
import com.caiocodes.billing.subscription.entity.Subscription;
import com.caiocodes.billing.subscription.repository.SubscriptionRepository;

/**
 * As operações por assinatura do ciclo diário, cada uma na sua transação.
 *
 * <p><strong>Duas armadilhas do Spring/JPA moram nesta classe.</strong>
 *
 * <p><strong>1. Proxy.</strong> {@code @Transactional} só tem efeito quando a
 * chamada <em>entra</em> no bean de fora. Se o {@link BillingRunner} chamasse
 * {@code this.renovarEFaturar(...)}, a chamada seria interna, o proxy não seria
 * atravessado e não haveria transação — silenciosamente. Por isso este é um
 * bean separado, que o runner recebe injetado (isto é, recebe o proxy).
 *
 * <p><strong>2. Entidade detached.</strong> Os métodos recebem o <em>id</em>, e
 * não a entidade. Parece burocracia e não é: o runner carrega as assinaturas
 * numa consulta que abre e fecha sua própria transação, então o objeto que ele
 * segura está <strong>detached</strong>. Chamar {@code assinatura.activate()}
 * num objeto detached altera a memória e nada mais — nenhum {@code UPDATE} é
 * emitido, e o job "funciona" sem mudar coisa alguma no banco. Recarregar por
 * id dentro da transação devolve uma entidade gerenciada, e aí o dirty checking
 * faz o trabalho.
 */
@Component
public class BillingItemProcessor {

    private static final Logger log = LoggerFactory.getLogger(BillingItemProcessor.class);

    private final SubscriptionRepository repository;
    private final InvoiceService invoiceService;
    private final OutboxPublisher outbox;
    private final Clock clock;

    public BillingItemProcessor(SubscriptionRepository repository,
                                InvoiceService invoiceService,
                                OutboxPublisher outbox,
                                Clock clock) {
        this.repository = repository;
        this.invoiceService = invoiceService;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Passo 0: assinatura futura cujo dia chegou entra em vigor e é faturada. */
    @Transactional
    public void ativarEFaturar(UUID id) {
        Subscription assinatura = repository.findById(id).orElseThrow();
        assinatura.activate();
        repository.flush();
        invoiceService.issueForCurrentPeriod(assinatura);
        log.info("Assinatura {} ativada e faturada", id);
    }

    /**
     * Passo 1: avança o ciclo e emite a competência nova.
     *
     * <p>A ordem importa: avança primeiro, emite depois. A competência a cobrar
     * é a que está começando; a anterior já foi faturada quando entrou em vigor.
     */
    @Transactional
    public void renovarEFaturar(UUID id) {
        Subscription assinatura = repository.findById(id).orElseThrow();
        assinatura.advanceCycle();
        repository.flush();
        invoiceService.issueForCurrentPeriod(assinatura);
    }

    /** Passo 3: suspende uma assinatura inadimplente. */
    @Transactional
    public void suspender(UUID id, long diasEmAtraso) {
        Subscription assinatura = repository.findById(id).orElseThrow();
        if (!assinatura.isActive()) {
            return;
        }
        assinatura.suspend(OffsetDateTime.now(clock));
        publicar(assinatura, OutboxEventType.SUBSCRIPTION_SUSPENDED);
        log.info("Assinatura {} suspensa: {} dias de atraso", id, diasEmAtraso);
    }

    /** Passo 4: suspensa há tempo demais é encerrada. */
    @Transactional
    public void encerrar(UUID id) {
        Subscription assinatura = repository.findById(id).orElseThrow();
        assinatura.cancel(OffsetDateTime.now(clock));
        publicar(assinatura, OutboxEventType.SUBSCRIPTION_CANCELLED);
        log.info("Assinatura {} encerrada após o prazo de suspensão", id);
    }

    private void publicar(Subscription assinatura, OutboxEventType tipo) {
        outbox.publicar("Subscription", assinatura.getId(), tipo, Map.of(
                "customerEmail", assinatura.getCustomer().getEmail(),
                "customerName", assinatura.getCustomer().getName(),
                "planName", assinatura.getPlan().getName()));
    }
}

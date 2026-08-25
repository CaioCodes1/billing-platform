package com.caiocodes.billing.scheduler;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.caiocodes.billing.config.BillingProperties;
import com.caiocodes.billing.invoice.repository.InvoiceRepository;
import com.caiocodes.billing.invoice.service.InvoiceService;
import com.caiocodes.billing.outbox.entity.OutboxEventType;
import com.caiocodes.billing.outbox.service.OutboxPublisher;
import com.caiocodes.billing.subscription.entity.Subscription;
import com.caiocodes.billing.subscription.entity.SubscriptionStatus;
import com.caiocodes.billing.subscription.repository.SubscriptionRepository;

/**
 * A lógica do ciclo diário, separada do agendamento.
 *
 * <p>{@link BillingScheduler} sabe <em>quando</em> rodar e como não rodar duas
 * vezes; esta classe sabe <em>o que</em> fazer. Dá para testar o ciclo inteiro
 * chamando {@link #executarCicloDiario()} direto, sem esperar cron.
 *
 * <p><strong>Uma transação por item, não uma por execução.</strong> Com milhares
 * de assinaturas, uma transação única seguraria conexão por minutos e uma falha
 * na última desfaria todo o trabalho. Quem abre as transações é o
 * {@link BillingItemProcessor} — ver lá o porquê de ser um bean separado.
 */
@Component
public class BillingRunner {

    private static final Logger log = LoggerFactory.getLogger(BillingRunner.class);
    private static final int TAMANHO_DA_PAGINA = 100;
    private static final int MAX_VOLTAS = 1000;

    private final SubscriptionRepository subscriptionRepository;
    private final InvoiceRepository invoiceRepository;
    private final BillingItemProcessor processor;
    private final InvoiceService invoiceService;
    private final BillingProperties properties;
    private final OutboxPublisher outbox;
    private final Clock clock;

    public BillingRunner(SubscriptionRepository subscriptionRepository,
                         InvoiceRepository invoiceRepository,
                         BillingItemProcessor processor,
                         InvoiceService invoiceService,
                         BillingProperties properties,
                         OutboxPublisher outbox,
                         Clock clock) {
        this.subscriptionRepository = subscriptionRepository;
        this.invoiceRepository = invoiceRepository;
        this.processor = processor;
        this.invoiceService = invoiceService;
        this.properties = properties;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Roda os cinco passos na ordem. Devolve um resumo para log e para teste. */
    public Resultado executarCicloDiario() {
        LocalDate hoje = LocalDate.now(clock);
        log.info("=== Ciclo de faturamento de {} ===", hoje);

        Resultado resultado = new Resultado(
                ativarPendentes(hoje),
                emitirRenovacoes(hoje),
                invoiceService.markOverdue(),
                suspenderInadimplentes(hoje),
                encerrarSuspensasAntigas());

        log.info("=== Ciclo concluído: {} ===", resultado);
        return resultado;
    }

    /** Passo 0 — assinaturas futuras cujo dia chegou entram em vigor. */
    public int ativarPendentes(LocalDate hoje) {
        return processarPaginado(
                pagina -> subscriptionRepository.findByStatusAndStartDateLessThanEqual(
                        SubscriptionStatus.PENDING, hoje, pagina),
                s -> processor.ativarEFaturar(s.getId()));
    }

    /** Passo 1 — emitir a competência seguinte de quem está renovando. */
    public int emitirRenovacoes(LocalDate hoje) {
        LocalDate limite = hoje.plusDays(properties.invoice().generateDaysAhead());
        return processarPaginado(
                pagina -> subscriptionRepository.findByStatusAndNextRenewalDateLessThanEqual(
                        SubscriptionStatus.ACTIVE, limite, pagina),
                s -> processor.renovarEFaturar(s.getId()));
    }

    /**
     * Passo 3 — suspender quem está em atraso além da tolerância.
     *
     * <p>Sem {@code @Transactional} aqui de propósito: este método é chamado por
     * {@link #executarCicloDiario()}, que está no mesmo bean. Chamada interna
     * não atravessa o proxy, então a anotação seria decoração — e o
     * {@code OutboxPublisher}, que exige transação, estouraria. Cada suspensão
     * abre a sua no {@link BillingItemProcessor}.
     */
    public int suspenderInadimplentes(LocalDate hoje) {
        var alvos = invoiceRepository.assinaturasParaSuspender(
                hoje, properties.dunning().suspendAfterDays());

        int suspensas = 0;
        for (var alvo : alvos) {
            var assinatura = subscriptionRepository.findById(alvo.getSubscriptionId());
            if (assinatura.isEmpty() || !assinatura.get().isActive()) {
                continue;
            }
            try {
                processor.suspender(alvo.getSubscriptionId(), alvo.getDiasEmAtraso());
                suspensas++;
            } catch (RuntimeException e) {
                log.error("Falha ao suspender assinatura {}: {}",
                        alvo.getSubscriptionId(), e.getMessage());
            }
        }
        return suspensas;
    }

    /** Passo 4 — encerrar suspensas antigas, para o ciclo ter fim. */
    public int encerrarSuspensasAntigas() {
        OffsetDateTime limite = OffsetDateTime.now(clock)
                .minusDays(properties.dunning().cancelAfterDays());

        return processarPaginado(
                pagina -> subscriptionRepository.findByStatusAndSuspendedAtBefore(
                        SubscriptionStatus.SUSPENDED, limite, pagina),
                s -> processor.encerrar(s.getId()));
    }

    /**
     * Percorre páginas pedindo sempre a <strong>primeira</strong>.
     *
     * <p>Parece errado e é o correto aqui: cada item processado muda de status e
     * sai do critério de busca. Avançar o número da página pularia registros,
     * porque o conjunto encolhe embaixo do cursor. O limite de voltas evita laço
     * infinito caso algum item não saia do critério.
     */
    private int processarPaginado(Function<Pageable, Page<Subscription>> busca,
                                  Consumer<Subscription> acao) {
        Pageable primeira = PageRequest.of(0, TAMANHO_DA_PAGINA, Sort.by("createdAt"));
        int processados = 0;

        for (int volta = 0; volta < MAX_VOLTAS; volta++) {
            Page<Subscription> pagina = busca.apply(primeira);
            if (pagina.isEmpty()) {
                break;
            }
            int antes = processados;
            for (Subscription assinatura : pagina) {
                try {
                    acao.accept(assinatura);
                    processados++;
                } catch (RuntimeException e) {
                    // Uma assinatura problemática não derruba o ciclo das
                    // outras. Loga e segue; a próxima execução tenta de novo.
                    log.error("Falha ao processar assinatura {}: {}",
                            assinatura.getId(), e.getMessage());
                }
            }
            // Nenhum item saiu do critério nesta volta: sair evita laço eterno.
            if (processados == antes) {
                break;
            }
        }
        return processados;
    }

    public record Resultado(int ativadas, int emitidas, int vencidas,
                            int suspensas, int encerradas) {

        @Override
        public String toString() {
            return "ativadas=%d emitidas=%d vencidas=%d suspensas=%d encerradas=%d"
                    .formatted(ativadas, emitidas, vencidas, suspensas, encerradas);
        }
    }
}

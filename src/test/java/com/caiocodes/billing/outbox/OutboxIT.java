package com.caiocodes.billing.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.caiocodes.billing.AbstractIntegrationTest;
import com.caiocodes.billing.outbox.entity.OutboxEventType;
import com.caiocodes.billing.outbox.entity.OutboxMessage;
import com.caiocodes.billing.outbox.entity.OutboxStatus;
import com.caiocodes.billing.outbox.repository.OutboxRepository;
import com.caiocodes.billing.outbox.service.EmailDispatcher;
import com.caiocodes.billing.outbox.service.OutboxPublisher;
import com.caiocodes.billing.outbox.service.OutboxWorker;

/**
 * O caminho completo do outbox: publicar na transação do dado, despachar num
 * worker separado, e lidar com falha de envio sem perder a mensagem.
 *
 * <p>O {@link EmailDispatcher} é substituído por um mock — o objetivo aqui é a
 * mecânica da fila, não o conteúdo do e-mail (que tem teste unitário próprio).
 */
class OutboxIT extends AbstractIntegrationTest {

    @Autowired
    private OutboxPublisher publisher;
    @Autowired
    private OutboxWorker worker;
    @Autowired
    private OutboxRepository repository;
    @Autowired
    private TransactionTemplate transacao;

    @MockitoBean
    private EmailDispatcher dispatcher;

    @BeforeEach
    void limpar() {
        repository.deleteAll();
    }

    private UUID publicar() {
        // O publisher exige transação (Propagation.MANDATORY), então o teste
        // precisa abrir uma — o que já prova que a exigência está valendo.
        return transacao.execute(status -> {
            UUID id = UUID.randomUUID();
            publisher.publicar("Invoice", id, OutboxEventType.INVOICE_ISSUED, Map.of(
                    "customerEmail", "cliente@exemplo.com.br",
                    "customerName", "Padaria",
                    "planName", "Profissional",
                    "amount", "199.90",
                    "dueDate", "2026-09-10"));
            return id;
        });
    }

    @Test
    @DisplayName("Evento publicado nasce PENDING e vira SENT depois do despacho")
    void cicloFeliz() throws Exception {
        publicar();

        OutboxMessage pendente = repository.findAll().get(0);
        assertThat(pendente.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(pendente.getAttempts()).isZero();
        assertThat(pendente.getSentAt()).isNull();

        int enviados = worker.processarLote();

        assertThat(enviados).isEqualTo(1);
        verify(dispatcher).despachar(any(OutboxMessage.class));

        OutboxMessage despachada = repository.findAll().get(0);
        assertThat(despachada.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(despachada.getSentAt()).isNotNull();
    }

    @Test
    @DisplayName("Falha de envio não perde a mensagem: registra o erro e reagenda")
    void falhaReagenda() throws Exception {
        publicar();
        doThrow(new RuntimeException("SMTP fora do ar"))
                .when(dispatcher).despachar(any(OutboxMessage.class));

        OffsetDateTime antes = repository.findAll().get(0).getNextAttemptAt();
        int enviados = worker.processarLote();

        assertThat(enviados).isZero();

        OutboxMessage falhou = repository.findAll().get(0);
        // Continua PENDING — a mensagem não some porque o SMTP caiu.
        assertThat(falhou.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(falhou.getAttempts()).isEqualTo((short) 1);
        assertThat(falhou.getLastError()).contains("SMTP fora do ar");
        // E o backoff empurrou a próxima tentativa para o futuro.
        assertThat(falhou.getNextAttemptAt()).isAfter(antes);
    }

    @Test
    @DisplayName("Mensagem reagendada não é reprocessada antes da hora")
    void respeitaOBackoff() throws Exception {
        publicar();
        doThrow(new RuntimeException("SMTP fora do ar"))
                .when(dispatcher).despachar(any(OutboxMessage.class));

        worker.processarLote();
        // A segunda passagem não deve nem tentar: next_attempt_at está no futuro.
        int tentativasNaSegundaRodada = worker.processarLote();

        assertThat(tentativasNaSegundaRodada).isZero();
        assertThat(repository.findAll().get(0).getAttempts()).isEqualTo((short) 1);
    }

    @Test
    @DisplayName("Depois de 5 tentativas a mensagem vira FAILED e para de tentar")
    void desisteAposOLimite() {
        publicar();
        OutboxMessage mensagem = repository.findAll().get(0);
        OffsetDateTime agora = OffsetDateTime.now();

        // Insistir para sempre num endereço inválido só enche a fila e atrasa
        // o que é entregável.
        for (int i = 0; i < 5; i++) {
            mensagem.registrarFalha("endereço inexistente", 5, agora);
        }
        repository.saveAndFlush(mensagem);

        assertThat(mensagem.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(worker.processarLote()).isZero();
    }

    @Test
    @DisplayName("O backoff é exponencial: 1, 2, 4, 8 minutos")
    void backoffExponencial() {
        publicar();
        OutboxMessage mensagem = repository.findAll().get(0);
        OffsetDateTime base = OffsetDateTime.now();

        mensagem.registrarFalha("erro", 10, base);
        assertThat(mensagem.getNextAttemptAt()).isEqualTo(base.plusMinutes(1));

        mensagem.registrarFalha("erro", 10, base);
        assertThat(mensagem.getNextAttemptAt()).isEqualTo(base.plusMinutes(2));

        mensagem.registrarFalha("erro", 10, base);
        assertThat(mensagem.getNextAttemptAt()).isEqualTo(base.plusMinutes(4));

        mensagem.registrarFalha("erro", 10, base);
        assertThat(mensagem.getNextAttemptAt()).isEqualTo(base.plusMinutes(8));
    }
}

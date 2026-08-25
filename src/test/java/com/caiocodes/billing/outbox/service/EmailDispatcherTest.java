package com.caiocodes.billing.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.caiocodes.billing.outbox.entity.OutboxEventType;
import com.caiocodes.billing.outbox.entity.OutboxMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class EmailDispatcherTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    private JavaMailSender mailSender;

    private EmailDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new EmailDispatcher(mailSender, JSON);
    }

    private OutboxMessage evento(OutboxEventType tipo, Map<String, Object> dados) throws Exception {
        return new OutboxMessage("Invoice", UUID.randomUUID(), tipo,
                JSON.writeValueAsString(dados), OffsetDateTime.now());
    }

    private SimpleMailMessage capturar() {
        ArgumentCaptor<SimpleMailMessage> captor =
                ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("Cobrança emitida: assunto, destinatário e valores no corpo")
    void cobrancaEmitida() throws Exception {
        dispatcher.despachar(evento(OutboxEventType.INVOICE_ISSUED, Map.of(
                "customerEmail", "cliente@exemplo.com.br",
                "customerName", "Padaria do Bairro",
                "planName", "Profissional",
                "amount", "199.90",
                "dueDate", "2026-09-10")));

        SimpleMailMessage mensagem = capturar();
        assertThat(mensagem.getTo()).containsExactly("cliente@exemplo.com.br");
        assertThat(mensagem.getSubject()).isEqualTo("Nova cobrança disponível");
        assertThat(mensagem.getText())
                .contains("Padaria do Bairro")
                .contains("Profissional")
                .contains("199.90")
                .contains("2026-09-10");
    }

    @Test
    @DisplayName("Pagamento confirmado agradece e cita o valor")
    void pagamentoConfirmado() throws Exception {
        dispatcher.despachar(evento(OutboxEventType.PAYMENT_CONFIRMED, Map.of(
                "customerEmail", "cliente@exemplo.com.br",
                "customerName", "Padaria",
                "amount", "199.90")));

        SimpleMailMessage mensagem = capturar();
        assertThat(mensagem.getSubject()).isEqualTo("Pagamento confirmado");
        assertThat(mensagem.getText()).contains("199.90").contains("Obrigado");
    }

    @Test
    @DisplayName("Suspensão explica como reativar")
    void assinaturaSuspensa() throws Exception {
        dispatcher.despachar(evento(OutboxEventType.SUBSCRIPTION_SUSPENDED, Map.of(
                "customerEmail", "cliente@exemplo.com.br",
                "customerName", "Padaria",
                "planName", "Profissional")));

        SimpleMailMessage mensagem = capturar();
        assertThat(mensagem.getSubject()).contains("suspensa");
        // O e-mail precisa dizer o que fazer, não só que algo deu errado.
        assertThat(mensagem.getText()).contains("Regularize");
    }

    @Test
    @DisplayName("Encerramento avisa que dá para voltar")
    void assinaturaEncerrada() throws Exception {
        dispatcher.despachar(evento(OutboxEventType.SUBSCRIPTION_CANCELLED, Map.of(
                "customerEmail", "cliente@exemplo.com.br",
                "customerName", "Padaria",
                "planName", "Profissional")));

        SimpleMailMessage mensagem = capturar();
        assertThat(mensagem.getSubject()).isEqualTo("Assinatura encerrada");
        assertThat(mensagem.getText()).contains("contratar novamente");
    }

    @Test
    @DisplayName("Evento sem destinatário falha alto, em vez de mandar para o vazio")
    void semDestinatario() throws Exception {
        // O worker captura isso, registra a falha e reagenda. Falhar em silêncio
        // deixaria a mensagem eternamente PENDING sem ninguém entender por quê.
        OutboxMessage semEmail = evento(OutboxEventType.PAYMENT_CONFIRMED,
                Map.of("customerName", "Padaria", "amount", "10.00"));

        assertThatThrownBy(() -> dispatcher.despachar(semEmail))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("customerEmail");
    }

    @Test
    @DisplayName("O corpo vem do payload gravado, não de consulta ao banco")
    void corpoVemDoPayload() throws Exception {
        // O e-mail deve descrever o que aconteceu QUANDO aconteceu. Reconsultar
        // o banco no envio traria o estado atual e produziria mensagens
        // contraditórias — "sua cobrança venceu" depois de o cliente já ter pago.
        dispatcher.despachar(evento(OutboxEventType.INVOICE_ISSUED, Map.of(
                "customerEmail", "a@b.com",
                "customerName", "Nome Do Momento",
                "planName", "Plano Do Momento",
                "amount", "1.00",
                "dueDate", "2020-01-01")));

        assertThat(capturar().getText())
                .contains("Nome Do Momento")
                .contains("Plano Do Momento");
    }
}

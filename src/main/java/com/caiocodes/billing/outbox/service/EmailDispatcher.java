package com.caiocodes.billing.outbox.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.caiocodes.billing.outbox.entity.OutboxEventType;
import com.caiocodes.billing.outbox.entity.OutboxMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Traduz um evento do outbox em e-mail e o envia.
 *
 * <p>O corpo é montado a partir do <strong>payload gravado no evento</strong>,
 * não de uma consulta ao banco no momento do envio. É de propósito: o e-mail
 * deve descrever o que aconteceu quando aconteceu. Reconsultar traria o estado
 * atual e produziria mensagens contraditórias — "sua cobrança venceu" chegando
 * depois de o cliente já ter pago.
 */
@Component
public class EmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);

    private final JavaMailSender mailSender;
    private final ObjectMapper objectMapper;

    public EmailDispatcher(JavaMailSender mailSender, ObjectMapper objectMapper) {
        this.mailSender = mailSender;
        this.objectMapper = objectMapper;
    }

    public void despachar(OutboxMessage evento) throws Exception {
        Map<String, Object> dados = objectMapper.readValue(
                evento.getPayload(), new TypeReference<>() {
                });

        String destinatario = (String) dados.get("customerEmail");
        if (destinatario == null || destinatario.isBlank()) {
            throw new IllegalStateException("Evento sem customerEmail no payload");
        }

        SimpleMailMessage mensagem = new SimpleMailMessage();
        mensagem.setTo(destinatario);
        mensagem.setFrom("financeiro@billing.local");
        mensagem.setSubject(evento.getEventType().assunto());
        mensagem.setText(corpo(evento.getEventType(), dados));

        mailSender.send(mensagem);
        log.info("E-mail {} enviado para {}", evento.getEventType(), destinatario);
    }

    private String corpo(OutboxEventType tipo, Map<String, Object> d) {
        String nome = String.valueOf(d.getOrDefault("customerName", "cliente"));

        return switch (tipo) {
            case INVOICE_ISSUED -> """
                    Olá, %s.

                    Uma nova cobrança do plano %s foi emitida.

                    Valor: R$ %s
                    Vencimento: %s

                    Equipe financeira
                    """.formatted(nome, d.get("planName"), d.get("amount"), d.get("dueDate"));

            case PAYMENT_CONFIRMED -> """
                    Olá, %s.

                    Recebemos seu pagamento de R$ %s. Obrigado!

                    Equipe financeira
                    """.formatted(nome, d.get("amount"));

            case SUBSCRIPTION_SUSPENDED -> """
                    Olá, %s.

                    Sua assinatura do plano %s foi suspensa por falta de pagamento.
                    Regularize as cobranças em aberto para reativá-la automaticamente.

                    Equipe financeira
                    """.formatted(nome, d.get("planName"));

            case SUBSCRIPTION_CANCELLED -> """
                    Olá, %s.

                    Sua assinatura do plano %s foi encerrada.
                    Você pode contratar novamente quando quiser.

                    Equipe financeira
                    """.formatted(nome, d.get("planName"));
        };
    }
}

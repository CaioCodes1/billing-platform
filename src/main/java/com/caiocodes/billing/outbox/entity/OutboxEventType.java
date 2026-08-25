package com.caiocodes.billing.outbox.entity;

/**
 * Os eventos que geram e-mail. O enunciado pedia três; o quarto
 * ({@code SUBSCRIPTION_CANCELLED}) veio junto com o passo de encerramento.
 */
public enum OutboxEventType {

    INVOICE_ISSUED("Nova cobrança disponível"),
    PAYMENT_CONFIRMED("Pagamento confirmado"),
    SUBSCRIPTION_SUSPENDED("Assinatura suspensa por inadimplência"),
    SUBSCRIPTION_CANCELLED("Assinatura encerrada");

    private final String assunto;

    OutboxEventType(String assunto) {
        this.assunto = assunto;
    }

    public String assunto() {
        return assunto;
    }
}

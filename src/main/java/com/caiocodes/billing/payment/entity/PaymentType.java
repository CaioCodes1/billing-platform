package com.caiocodes.billing.payment.entity;

/**
 * Natureza de um lançamento no livro-razão.
 *
 * <p>O valor em {@code payments.amount} é <strong>sempre positivo</strong>;
 * quem dá o sinal é este campo. A alternativa — permitir valor negativo —
 * abriria a classe de bug "estorno gravado positivo", que passa despercebida
 * numa soma e só aparece no fechamento contábil.
 */
public enum PaymentType {

    /** Entrada de dinheiro. */
    PAYMENT(1),

    /** Devolução: estorno PIX, chargeback de cartão, cancelamento de boleto pago. */
    REFUND(-1);

    private final int sinal;

    PaymentType(int sinal) {
        this.sinal = sinal;
    }

    public int sinal() {
        return sinal;
    }
}

package com.caiocodes.billing.payment.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.caiocodes.billing.payment.entity.PaymentMethod;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Registro de um pagamento recebido")
public record RegisterPaymentRequest(

        @NotNull(message = "método de pagamento é obrigatório")
        PaymentMethod method,

        @Schema(example = "199.90")
        @NotNull(message = "valor é obrigatório")
        @DecimalMin(value = "0.01", message = "valor deve ser maior que zero")
        @Digits(integer = 15, fraction = 4,
                message = "valor deve ter no máximo {fraction} casas decimais")
        BigDecimal amount,

        @Schema(description = "Quando o pagamento aconteceu de fato no provedor. "
                + "Omitido, assume agora.")
        OffsetDateTime paidAt,

        @Schema(description = "Id da transação no provedor (PSP). Único: um webhook "
                + "reentregue não vira pagamento duplicado.")
        String providerRef,

        @Schema(description = "Chave de idempotência do cliente da API. Única: "
                + "clique duplo ou retry de rede não vira pagamento duplicado.")
        UUID idempotencyKey) {
}

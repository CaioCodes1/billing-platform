package com.caiocodes.billing.subscription.dto;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Dados para contratação de uma assinatura")
public record CreateSubscriptionRequest(

        @NotNull(message = "cliente é obrigatório")
        UUID customerId,

        @NotNull(message = "plano é obrigatório")
        UUID planId,

        @Schema(description = "Data de início. Omitida, assume hoje. "
                + "Se for futura, a assinatura nasce PENDING. "
                + "O dia dela vira o dia de cobrança de todos os ciclos.",
                example = "2026-09-01")
        LocalDate startDate) {
}

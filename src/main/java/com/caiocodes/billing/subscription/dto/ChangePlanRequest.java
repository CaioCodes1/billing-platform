package com.caiocodes.billing.subscription.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Troca de plano de uma assinatura")
public record ChangePlanRequest(

        @NotNull(message = "plano é obrigatório")
        UUID planId) {
}

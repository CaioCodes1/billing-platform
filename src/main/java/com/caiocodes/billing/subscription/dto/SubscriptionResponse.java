package com.caiocodes.billing.subscription.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.caiocodes.billing.subscription.entity.SubscriptionStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Representação pública de uma assinatura")
public record SubscriptionResponse(
        UUID id,
        CustomerSummary customer,
        PlanSummary plan,

        @Schema(description = "Valor congelado na contratação — pode diferir do "
                + "preço atual do plano")
        BigDecimal unitPrice,
        String currency,

        @Schema(description = "Dia contratado (1..31). Meses curtos são ajustados "
                + "sem perder o dia original")
        short billingDay,

        LocalDate startDate,
        LocalDate currentPeriodStart,
        LocalDate currentPeriodEnd,
        LocalDate nextRenewalDate,
        SubscriptionStatus status,
        OffsetDateTime suspendedAt,
        OffsetDateTime cancelledAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * Resumos aninhados em vez do cliente e do plano inteiros: o consumidor de
     * uma assinatura quer saber de quem ela é, não receber o cadastro completo
     * duplicado em cada item da listagem.
     */
    public record CustomerSummary(UUID id, String name, String email) {
    }

    public record PlanSummary(UUID id, String name, BigDecimal monthlyPrice) {
    }
}

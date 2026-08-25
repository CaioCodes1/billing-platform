package com.caiocodes.billing.subscription.dto;

import java.util.UUID;

import com.caiocodes.billing.subscription.entity.SubscriptionStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Filtros de busca de assinaturas")
public record SubscriptionFilter(
        UUID customerId,
        UUID planId,
        SubscriptionStatus status) {
}

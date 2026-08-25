package com.caiocodes.billing.plan.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Representação pública de um plano")
public record PlanResponse(
        UUID id,
        String name,
        String description,
        BigDecimal monthlyPrice,
        String currency,
        Integer userLimit,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}

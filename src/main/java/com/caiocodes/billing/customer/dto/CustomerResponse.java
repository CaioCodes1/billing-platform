package com.caiocodes.billing.customer.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.caiocodes.billing.customer.entity.CustomerStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Representação pública de um cliente")
public record CustomerResponse(
        UUID id,
        String name,
        String email,
        String document,
        String phone,
        CustomerStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}

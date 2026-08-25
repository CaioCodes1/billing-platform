package com.caiocodes.billing.payment.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.caiocodes.billing.payment.entity.PaymentMethod;
import com.caiocodes.billing.payment.entity.PaymentType;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Lançamento no livro-razão de uma cobrança")
public record PaymentResponse(
        UUID id,
        UUID invoiceId,
        PaymentType type,
        PaymentMethod method,

        @Schema(description = "Sempre positivo; o sinal vem do campo type")
        BigDecimal amount,

        OffsetDateTime paidAt,
        String providerRef,
        UUID idempotencyKey,
        OffsetDateTime createdAt) {
}

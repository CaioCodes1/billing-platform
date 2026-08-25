package com.caiocodes.billing.invoice.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.caiocodes.billing.invoice.entity.InvoiceStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Representação pública de uma cobrança")
public record InvoiceResponse(
        UUID id,
        SubscriptionSummary subscription,

        @Schema(description = "Início da competência cobrada")
        LocalDate periodStart,
        LocalDate periodEnd,

        BigDecimal amount,
        String currency,
        LocalDate dueDate,
        InvoiceStatus status,
        OffsetDateTime paidAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * Traz o cliente junto porque quem olha uma cobrança quase sempre quer
     * saber de quem ela é — e sem isso a tela de inadimplência precisaria de
     * uma requisição por linha.
     */
    public record SubscriptionSummary(
            UUID id,
            UUID customerId,
            String customerName,
            String planName) {
    }
}

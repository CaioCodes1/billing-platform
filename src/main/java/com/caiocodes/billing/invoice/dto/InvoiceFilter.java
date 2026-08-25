package com.caiocodes.billing.invoice.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.caiocodes.billing.invoice.entity.InvoiceStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Filtros de busca de cobranças")
public record InvoiceFilter(
        UUID subscriptionId,

        @Schema(description = "Todas as cobranças de um cliente, de qualquer assinatura")
        UUID customerId,

        InvoiceStatus status,

        @Schema(description = "Vencimento a partir de", example = "2026-09-01")
        LocalDate dueFrom,

        @Schema(description = "Vencimento até", example = "2026-09-30")
        LocalDate dueTo,

        @Schema(description = "Apenas cobranças em aberto (PENDING, PARTIALLY_PAID, OVERDUE)")
        Boolean openOnly) {
}

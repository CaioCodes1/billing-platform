package com.caiocodes.billing.plan.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Filtros de busca de planos")
public record PlanFilter(

        @Schema(description = "Busca parcial, sem diferenciar maiúsculas")
        String name,

        @Schema(description = "Só planos ativos, só inativos, ou ambos se omitido")
        Boolean active,

        @Schema(description = "Preço mensal mínimo", example = "50.00")
        BigDecimal minPrice,

        @Schema(description = "Preço mensal máximo", example = "500.00")
        BigDecimal maxPrice) {
}

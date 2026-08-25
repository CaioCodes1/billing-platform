package com.caiocodes.billing.customer.dto;

import com.caiocodes.billing.customer.entity.CustomerStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Filtros da listagem. Todos opcionais e combináveis com E lógico.
 */
@Schema(description = "Filtros de busca de clientes")
public record CustomerFilter(

        @Schema(description = "Busca parcial, sem diferenciar maiúsculas")
        String name,

        @Schema(description = "Busca parcial, sem diferenciar maiúsculas")
        String email,

        @Schema(description = "CPF ou CNPJ exato; pontuação é descartada")
        String document,

        CustomerStatus status) {

    public CustomerFilter {
        document = document == null ? null : document.replaceAll("\\D", "");
    }

    public boolean vazio() {
        return name == null && email == null && document == null && status == null;
    }
}

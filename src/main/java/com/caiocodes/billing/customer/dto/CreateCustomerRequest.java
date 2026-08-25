package com.caiocodes.billing.customer.dto;

import com.caiocodes.billing.shared.validation.Document;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Dados para cadastro de um cliente")
public record CreateCustomerRequest(

        @Schema(example = "Padaria do Bairro Ltda")
        @NotBlank(message = "nome é obrigatório")
        @Size(max = 150, message = "nome deve ter no máximo {max} caracteres")
        String name,

        @Schema(example = "financeiro@padaria.com.br")
        @NotBlank(message = "e-mail é obrigatório")
        @Email(message = "e-mail inválido")
        @Size(max = 255, message = "e-mail deve ter no máximo {max} caracteres")
        String email,

        @Schema(description = "CPF ou CNPJ. Pontuação é aceita e descartada.",
                example = "12345678000195")
        @NotBlank(message = "documento é obrigatório")
        @Document
        String document,

        @Schema(example = "11987654321")
        @Size(max = 20, message = "telefone deve ter no máximo {max} caracteres")
        String phone) {

    /**
     * Normaliza na borda, antes de qualquer validação ou persistência: assim
     * "12.345.678/0001-95" e "12345678000195" são o mesmo cliente, e o índice
     * único do banco de fato impede a duplicata.
     */
    public CreateCustomerRequest {
        document = somenteDigitos(document);
        email = email == null ? null : email.trim();
        name = name == null ? null : name.trim();
        phone = somenteDigitos(phone);
    }

    private static String somenteDigitos(String valor) {
        return valor == null ? null : valor.replaceAll("\\D", "");
    }
}

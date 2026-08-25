package com.caiocodes.billing.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Atualização de cliente.
 *
 * <p>Não inclui {@code document} nem {@code status} de propósito: o documento é
 * imutável (ver {@code Customer#document}) e o status muda por endpoint próprio
 * — desativar um cliente é uma ação de negócio, não a edição de um campo.
 */
@Schema(description = "Dados editáveis de um cliente")
public record UpdateCustomerRequest(

        @NotBlank(message = "nome é obrigatório")
        @Size(max = 150, message = "nome deve ter no máximo {max} caracteres")
        String name,

        @NotBlank(message = "e-mail é obrigatório")
        @Email(message = "e-mail inválido")
        @Size(max = 255, message = "e-mail deve ter no máximo {max} caracteres")
        String email,

        @Size(max = 20, message = "telefone deve ter no máximo {max} caracteres")
        String phone) {

    public UpdateCustomerRequest {
        name = name == null ? null : name.trim();
        email = email == null ? null : email.trim();
        phone = phone == null ? null : phone.replaceAll("\\D", "");
    }
}

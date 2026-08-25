package com.caiocodes.billing.plan.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Atualização de plano — inclusive do preço.
 *
 * <p>Alterar {@code monthlyPrice} aqui é seguro por construção: a assinatura
 * copia o preço na contratação, então o reajuste vale só para contratos novos.
 * Sem essa cópia, este endpoint reprecificaria a base inteira.
 *
 * <p>{@code active} não entra: ativar e desativar são ações de negócio com
 * endpoint próprio, não a edição de um campo booleano.
 */
@Schema(description = "Dados editáveis de um plano")
public record UpdatePlanRequest(

        @NotBlank(message = "nome é obrigatório")
        @Size(max = 100, message = "nome deve ter no máximo {max} caracteres")
        String name,

        String description,

        @NotNull(message = "valor mensal é obrigatório")
        @DecimalMin(value = "0.00", message = "valor mensal não pode ser negativo")
        @Digits(integer = 15, fraction = 4,
                message = "valor mensal deve ter no máximo {fraction} casas decimais")
        BigDecimal monthlyPrice,

        @NotNull(message = "limite de usuários é obrigatório")
        @Min(value = 1, message = "limite de usuários deve ser no mínimo {value}")
        Integer userLimit) {

    public UpdatePlanRequest {
        name = name == null ? null : name.trim();
        description = description == null || description.isBlank() ? null : description.trim();
    }
}

package com.caiocodes.billing.config;

import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Parâmetros de negócio, tipados e validados no boot.
 *
 * <p>Ler {@code @Value("${billing.dunning.suspend-after-days}")} espalhado pelos
 * services custa caro de duas formas: um erro de digitação só aparece quando a
 * linha executa (podendo ser semanas depois, dentro do job noturno), e não há um
 * lugar único que responda "quais são os prazos deste sistema?". Aqui um valor
 * ausente ou inválido derruba a aplicação no boot.
 */
@Validated
@ConfigurationProperties(prefix = "billing")
public record BillingProperties(

        @NotBlank String timezone,
        @NotNull Invoice invoice,
        @NotNull Dunning dunning,
        @NotNull Jwt jwt,
        Bootstrap bootstrap) {

    public record Jwt(
            /** Chave HS256. Mínimo 32 bytes, senão o Nimbus recusa. */
            @NotBlank @Size(min = 32, message = "a chave JWT precisa de ao menos {min} bytes")
            String secret,
            @NotNull Duration accessTtl,
            @NotNull Duration refreshTtl) {
    }

    /** Admin criado no primeiro boot, se a tabela de usuários estiver vazia. */
    public record Bootstrap(String adminEmail, String adminPassword) {
    }

    public record Invoice(
            /** Dias de antecedência com que a fatura do próximo período é emitida. */
            @Min(0) int generateDaysAhead,
            /** Prazo de pagamento, contado do início da competência. */
            @Min(1) int dueDaysAfterIssue) {
    }

    public record Dunning(
            /** Dias em OVERDUE até suspender a assinatura. */
            @Min(1) int suspendAfterDays,
            /** Dias em SUSPENDED até cancelar de vez. */
            @Min(1) int cancelAfterDays) {
    }

    public ZoneId zone() {
        return ZoneId.of(timezone);
    }
}

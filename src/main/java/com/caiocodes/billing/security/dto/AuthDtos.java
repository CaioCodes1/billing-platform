package com.caiocodes.billing.security.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** DTOs de autenticação, agrupados por serem pequenos e sempre usados juntos. */
public final class AuthDtos {

    private AuthDtos() {
    }

    @Schema(description = "Credenciais de acesso")
    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {
    }

    @Schema(description = "Par de tokens emitido no login ou na renovação")
    public record TokenResponse(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresInSeconds) {

        public static TokenResponse de(String access, String refresh, long segundos) {
            return new TokenResponse(access, refresh, "Bearer", segundos);
        }
    }

    @Schema(description = "Renovação de sessão")
    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    @Schema(description = "Criação de usuário interno")
    public record CreateUserRequest(
            @NotBlank @Size(max = 150) String name,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 72,
                    message = "senha deve ter entre {min} e {max} caracteres")
            String password,
            @NotEmpty(message = "informe ao menos um papel")
            List<String> roles) {
    }

    @Schema(description = "Usuário interno. Nunca inclui senha nem hash.")
    public record UserResponse(
            UUID id,
            String name,
            String email,
            boolean enabled,
            List<String> roles,
            OffsetDateTime createdAt) {
    }
}

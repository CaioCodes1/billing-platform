package com.caiocodes.billing.security.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.caiocodes.billing.security.dto.AuthDtos.CreateUserRequest;
import com.caiocodes.billing.security.dto.AuthDtos.LoginRequest;
import com.caiocodes.billing.security.dto.AuthDtos.RefreshRequest;
import com.caiocodes.billing.security.dto.AuthDtos.TokenResponse;
import com.caiocodes.billing.security.dto.AuthDtos.UserResponse;
import com.caiocodes.billing.security.service.AuthService;
import com.caiocodes.billing.security.service.UserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Autenticação", description = "Login, renovação de sessão e usuários internos")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    @PostMapping("/login")
    @Operation(summary = "Autentica e emite o par de tokens")
    @ApiResponse(responseCode = "401", description = "Credenciais inválidas")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Renova a sessão",
            description = "Rotaciona o refresh token. Reapresentar um token já "
                    + "rotacionado revoga a família inteira por segurança.")
    @ApiResponse(responseCode = "401", description = "Refresh inválido, expirado ou reutilizado")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @Operation(summary = "Encerra a sessão do refresh informado")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // Usuários internos — só ADMIN (a regra está no UserService)
    // ------------------------------------------------------------------

    @PostMapping("/users")
    @Operation(summary = "Cria um usuário interno")
    @ApiResponse(responseCode = "403", description = "Requer perfil ADMIN")
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(201).body(userService.create(request));
    }

    @GetMapping("/users")
    @Operation(summary = "Lista os usuários internos")
    public List<UserResponse> listUsers() {
        return userService.list();
    }

    @DeleteMapping("/users/{id}")
    @Operation(summary = "Desativa um usuário e derruba suas sessões")
    public ResponseEntity<Void> disableUser(@PathVariable UUID id) {
        userService.disable(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/enable")
    @Operation(summary = "Reativa um usuário")
    public UserResponse enableUser(@PathVariable UUID id) {
        return userService.enable(id);
    }
}

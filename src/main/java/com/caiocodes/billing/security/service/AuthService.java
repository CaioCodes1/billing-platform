package com.caiocodes.billing.security.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.caiocodes.billing.config.BillingProperties;
import com.caiocodes.billing.security.dto.AuthDtos.LoginRequest;
import com.caiocodes.billing.security.dto.AuthDtos.TokenResponse;
import com.caiocodes.billing.security.entity.RefreshToken;
import com.caiocodes.billing.security.entity.User;
import com.caiocodes.billing.security.repository.RefreshTokenRepository;
import com.caiocodes.billing.security.repository.UserRepository;
import com.caiocodes.billing.shared.exception.UnauthorizedException;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshRepository;
    private final RefreshTokenRevoker revoker;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final BillingProperties properties;
    private final Clock clock;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshRepository,
                       RefreshTokenRevoker revoker,
                       TokenService tokenService,
                       PasswordEncoder passwordEncoder,
                       BillingProperties properties,
                       Clock clock) {
        this.userRepository = userRepository;
        this.refreshRepository = refreshRepository;
        this.revoker = revoker;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        User usuario = userRepository.findByEmailIgnoreCase(request.email())
                .orElse(null);

        // Mensagem idêntica para e-mail inexistente e senha errada. Diferenciar
        // as duas entregaria de graça um verificador de e-mails cadastrados.
        if (usuario == null
                || !usuario.isEnabled()
                || !passwordEncoder.matches(request.password(), usuario.getPasswordHash())) {
            log.warn("Falha de autenticação para {}", request.email());
            throw new UnauthorizedException("CREDENCIAIS_INVALIDAS",
                    "E-mail ou senha inválidos.");
        }

        return emitirPar(usuario, UUID.randomUUID());
    }

    /**
     * Renova a sessão com rotação e detecção de reuso.
     *
     * <p>Cada refresh usado é invalidado e substituído por outro. Se um token
     * <em>já rotacionado</em> voltar a ser apresentado, só há duas explicações:
     * o cliente guardou um token velho por engano, ou alguém copiou a
     * credencial. Como não dá para distinguir, revoga-se a família inteira e
     * todos precisam fazer login de novo. É a resposta segura.
     */
    @Transactional
    public TokenResponse refresh(String refreshTokenCru) {
        OffsetDateTime agora = OffsetDateTime.now(clock);
        String hash = tokenService.hash(refreshTokenCru);

        RefreshToken guardado = refreshRepository.findByTokenHash(hash)
                .orElseThrow(() -> new UnauthorizedException("REFRESH_INVALIDO",
                        "Sessão inválida. Faça login novamente."));

        if (guardado.getReplacedById() != null) {
            // Em transação PRÓPRIA: a exceção logo abaixo marcaria esta para
            // rollback e a revogação seria desfeita. Ver RefreshTokenRevoker.
            revoker.revogarFamilia(guardado.getFamilyId(), agora);
            throw new UnauthorizedException("REFRESH_REUTILIZADO",
                    "Sessão encerrada por segurança. Faça login novamente.");
        }

        if (!guardado.isUsable(agora)) {
            throw new UnauthorizedException("REFRESH_EXPIRADO",
                    "Sessão expirada. Faça login novamente.");
        }

        User usuario = guardado.getUser();
        if (!usuario.isEnabled()) {
            throw new UnauthorizedException("USUARIO_DESATIVADO", "Usuário desativado.");
        }

        String refreshCru = tokenService.gerarRefreshTokenCru();
        RefreshToken emitido = persistirRefresh(usuario, guardado.getFamilyId(), refreshCru);

        // Encadeia o antigo ao novo e grava explicitamente. É este vínculo que
        // permite detectar o reuso depois: um token com replaced_by_id
        // preenchido que volta a ser apresentado só pode ter sido copiado.
        guardado.replaceBy(emitido.getId(), agora);
        refreshRepository.saveAndFlush(guardado);

        return TokenResponse.de(
                tokenService.gerarAccessToken(usuario),
                refreshCru,
                properties.jwt().accessTtl().toSeconds());
    }

    @Transactional
    public void logout(String refreshTokenCru) {
        refreshRepository.findByTokenHash(tokenService.hash(refreshTokenCru))
                .ifPresent(t -> t.revoke(OffsetDateTime.now(clock)));
    }

    /** Encerra todas as sessões de um usuário — troca de senha, suspeita, etc. */
    @Transactional
    public void logoutTudo(UUID userId) {
        revoker.revogarTodosDoUsuario(userId, OffsetDateTime.now(clock));
    }

    private TokenResponse emitirPar(User usuario, UUID familyId) {
        String refreshCru = tokenService.gerarRefreshTokenCru();
        persistirRefresh(usuario, familyId, refreshCru);

        return TokenResponse.de(
                tokenService.gerarAccessToken(usuario),
                refreshCru,
                properties.jwt().accessTtl().toSeconds());
    }

    /** Grava o refresh como hash e devolve a entidade, para poder encadeá-la. */
    private RefreshToken persistirRefresh(User usuario, UUID familyId, String refreshCru) {
        return refreshRepository.saveAndFlush(new RefreshToken(
                usuario,
                tokenService.hash(refreshCru),
                familyId,
                OffsetDateTime.now(clock).plus(properties.jwt().refreshTtl())));
    }
}

package com.caiocodes.billing.security.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.stereotype.Service;

import com.caiocodes.billing.config.BillingProperties;
import com.caiocodes.billing.security.entity.Role;
import com.caiocodes.billing.security.entity.User;

/**
 * Emite os dois tipos de token, que são coisas bem diferentes.
 *
 * <p><strong>Access token</strong> — JWT assinado, curto (15 min), carrega os
 * papéis. É <em>stateless</em>: o servidor não guarda nada e não consegue
 * invalidá-lo antes de expirar. Por isso é curto.
 *
 * <p><strong>Refresh token</strong> — string aleatória opaca, sem significado.
 * Não é JWT de propósito: como fica guardado no banco, não há vantagem em ser
 * auto-contido, e sendo opaco não vaza nenhuma informação se for interceptado.
 * O banco guarda só o hash.
 */
@Service
public class TokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JwtEncoder encoder;
    private final BillingProperties properties;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, BillingProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public String gerarAccessToken(User usuario) {
        Instant agora = Instant.now(clock);
        List<String> papeis = usuario.getRoles().stream().map(Role::getName).toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("billing-platform")
                .subject(usuario.getId().toString())
                .issuedAt(agora)
                .expiresAt(agora.plus(properties.jwt().accessTtl()))
                .claim("email", usuario.getEmail())
                .claim("name", usuario.getName())
                // Os papéis vão no token: assim autorizar não custa uma ida ao
                // banco por requisição. O preço é que revogar um papel só vale
                // quando o access token expirar — daí os 15 minutos.
                .claim("roles", papeis)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** Token opaco de 256 bits, seguro para uso como credencial. */
    public String gerarRefreshTokenCru() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256 em hex. Sem sal e sem BCrypt de propósito: o token já é aleatório
     * de 256 bits, então não há dicionário a proteger e a verificação precisa
     * ser uma busca por índice, não um laço comparando hash a hash.
     */
    public String hash(String tokenCru) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(tokenCru.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível nesta JVM", e);
        }
    }
}

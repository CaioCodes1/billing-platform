package com.caiocodes.billing.config;

import java.nio.charset.StandardCharsets;

import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import jakarta.annotation.PostConstruct;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    private static final String SEGREDO_PADRAO =
            "desenvolvimento-apenas-troque-esta-chave-em-producao-32b";

    private final BillingProperties properties;
    private final Environment environment;

    public SecurityConfig(BillingProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    /**
     * Falha no boot se o segredo de desenvolvimento vazar para produção.
     *
     * <p>Um default existe para o {@code mvn spring-boot:run} funcionar sem
     * configuração nenhuma. O risco desse tipo de conveniência é ela chegar em
     * produção calada — então aqui ela grita.
     */
    @PostConstruct
    void validarSegredoEmProducao() {
        boolean producao = java.util.Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (producao && SEGREDO_PADRAO.equals(properties.jwt().secret())) {
            throw new IllegalStateException(
                    "JWT_SECRET não foi definido: o perfil prod não sobe com a chave padrão.");
        }
        if (SEGREDO_PADRAO.equals(properties.jwt().secret())) {
            log.warn("Usando a chave JWT de desenvolvimento. NÃO use isto em produção.");
        }
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter conversor)
            throws Exception {
        return http
                // API stateless com cliente não-browser: não há cookie de sessão
                // para um ataque CSRF sequestrar.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // O próprio refresh token é a credencial destas três
                        // rotas — exigir um access token para renovar sessão ou
                        // sair seria circular: quem tem o access expirado não
                        // conseguiria nem encerrar a sessão.
                        .requestMatchers("/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/docs/**", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        // Tudo o mais exige autenticação. A autorização fina
                        // (quem pode o quê) fica em @PreAuthorize no service —
                        // assim a regra vale mesmo se a rota mudar.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(conversor)))
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    /** Lê a claim {@code roles} e a converte em authorities {@code ROLE_*}. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter conversor = new JwtAuthenticationConverter();
        conversor.setJwtGrantedAuthoritiesConverter(authorities);
        return conversor;
    }

    private SecretKeySpec chave() {
        return new SecretKeySpec(
                properties.jwt().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(chave()));
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(chave())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * BCrypt com custo 10 (padrão). O custo é deliberadamente alto para tornar
     * força bruta cara — é o único algoritmo aqui em que ser lento é a feature.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

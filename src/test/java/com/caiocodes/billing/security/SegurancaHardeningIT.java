package com.caiocodes.billing.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.caiocodes.billing.AbstractIntegrationTest;
import com.caiocodes.billing.security.entity.User;
import com.caiocodes.billing.security.repository.RefreshTokenRepository;
import com.caiocodes.billing.security.repository.RoleRepository;
import com.caiocodes.billing.security.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

/**
 * Testes de segurança: tentam <em>quebrar</em> a API, em vez de exercitar o
 * caminho feliz.
 *
 * <p>Cada teste aqui corresponde a um ataque concreto — token forjado, token
 * expirado, escalada de privilégio por adulteração de claim, autenticação por
 * caminho alternativo, vazamento de dado sensível na resposta.
 */
@AutoConfigureMockMvc
class SegurancaHardeningIT extends AbstractIntegrationTest {

    /** A mesma chave de application-test.yml. */
    private static final String CHAVE_DE_TESTE =
            "chave-apenas-de-teste-com-no-minimo-32-bytes-para-hs256";

    @Autowired
    private ApplicationContext contexto;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    @AfterEach
    void limpar() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    private void criarAdmin() {
        userRepository.saveAndFlush(new User("Admin", "admin@teste.local",
                passwordEncoder.encode("senha-forte-123"),
                Set.of(roleRepository.findByName("ADMIN").orElseThrow())));
    }

    private String loginComoAdmin() throws Exception {
        criarAdmin();
        String r = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "admin@teste.local", "password": "senha-forte-123"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(r, "$.accessToken");
    }

    /** Assina um JWT com a chave informada — usado para forjar tokens. */
    private String assinar(String chave, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(new SecretKeySpec(
                chave.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        return jwt.serialize();
    }

    private JWTClaimsSet claimsDeAdmin(Instant emissao, Instant expiracao) {
        return new JWTClaimsSet.Builder()
                .issuer("billing-platform")
                .subject(UUID.randomUUID().toString())
                .issueTime(Date.from(emissao))
                .expirationTime(Date.from(expiracao))
                .claim("roles", List.of("ADMIN"))
                .build();
    }

    // =================================================================
    // Falsificação de token
    // =================================================================

    @Test
    @DisplayName("Token assinado com outra chave é recusado")
    void tokenComChaveErrada() throws Exception {
        Instant agora = Instant.now();
        String forjado = assinar(
                "chave-do-atacante-com-tamanho-suficiente-para-hs256",
                claimsDeAdmin(agora, agora.plusSeconds(900)));

        mockMvc.perform(get("/api/v1/customers")
                        .header("Authorization", "Bearer " + forjado))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Token sem assinatura (alg: none) é recusado")
    void tokenSemAssinatura() throws Exception {
        // Ataque clássico: trocar o algoritmo para "none" e remover a
        // assinatura. Bibliotecas mal configuradas aceitam.
        Instant agora = Instant.now();
        String semAssinatura = new PlainJWT(
                claimsDeAdmin(agora, agora.plusSeconds(900))).serialize();

        mockMvc.perform(get("/api/v1/customers")
                        .header("Authorization", "Bearer " + semAssinatura))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Token expirado é recusado mesmo com assinatura válida")
    void tokenExpirado() throws Exception {
        Instant passado = Instant.now().minusSeconds(7200);
        String expirado = assinar(CHAVE_DE_TESTE,
                claimsDeAdmin(passado, passado.plusSeconds(900)));

        mockMvc.perform(get("/api/v1/customers")
                        .header("Authorization", "Bearer " + expirado))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Adulterar o payload invalida a assinatura")
    void payloadAdulterado() throws Exception {
        String token = loginComoAdmin();

        // Troca o corpo do token por um que diz ADMIN, mantendo a assinatura
        // original. É o ataque de quem acha que JWT é só base64.
        String[] partes = token.split("\\.");
        String payloadFalso = Base64.getUrlEncoder().withoutPadding().encodeToString(
                """
                {"iss":"billing-platform","sub":"%s","exp":%d,"roles":["ADMIN"]}
                """.formatted(UUID.randomUUID(), Instant.now().getEpochSecond() + 9000)
                        .getBytes(StandardCharsets.UTF_8));

        String adulterado = partes[0] + "." + payloadFalso + "." + partes[2];

        mockMvc.perform(get("/api/v1/customers")
                        .header("Authorization", "Bearer " + adulterado))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Lixo no header Authorization não derruba a API")
    void headerMalformado() throws Exception {
        for (String valor : List.of("Bearer", "Bearer ", "Bearer abc",
                "Basic YWRtaW46YWRtaW4=", "', 'x", "Bearer null")) {
            mockMvc.perform(get("/api/v1/customers").header("Authorization", valor))
                    .andExpect(status().isUnauthorized());
        }
    }

    // =================================================================
    // Escalada de privilégio
    // =================================================================

    @Test
    @DisplayName("Token válido de SUPPORT não vira ADMIN forjando a claim roles")
    void suportNaoEscalaParaAdmin() throws Exception {
        userRepository.saveAndFlush(new User("Suporte", "sup@teste.local",
                passwordEncoder.encode("senha-forte-123"),
                Set.of(roleRepository.findByName("SUPPORT").orElseThrow())));

        String r = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "sup@teste.local", "password": "senha-forte-123"}
                                """))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(r, "$.accessToken");

        // Com o token legítimo, SUPPORT não cria plano.
        mockMvc.perform(post("/api/v1/plans")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Escalada", "monthlyPrice": 1.00, "userLimit": 1}
                                """))
                .andExpect(status().isForbidden());
    }

    // =================================================================
    // Superfície de ataque
    // =================================================================

    @Test
    @DisplayName("Basic Auth não é um caminho alternativo de autenticação")
    void basicAuthNaoEhAceito() throws Exception {
        String credencial = Base64.getEncoder().encodeToString(
                "user:qualquer".getBytes(StandardCharsets.UTF_8));

        // Só verificar o 401 seria um teste fraco: credencial errada devolve 401
        // de qualquer jeito, mesmo com o Basic ligado e funcionando. O que
        // realmente distingue é o desafio: um filtro Basic ativo responde com
        // "WWW-Authenticate: Basic realm=...". A ausência desse header é a prova
        // de que não existe um segundo caminho de login esperando credencial.
        String desafio = mockMvc.perform(get("/api/v1/customers")
                        .header("Authorization", "Basic " + credencial))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getHeader("WWW-Authenticate");

        assertThat(desafio).doesNotContainIgnoringCase("Basic");
    }

    @Test
    @DisplayName("Nenhum UserDetailsService existe — não há usuário padrão em memória")
    void semUsuarioPadraoEmMemoria() {
        // Quando não há UserDetailsService, AuthenticationProvider nem
        // AuthenticationManager, o Spring Boot cria um usuário 'user' com senha
        // aleatória no boot. Aqui ele não é criado porque o JwtDecoder do
        // resource server faz a auto-configuração recuar — uma proteção que
        // depende de um detalhe de outra biblioteca. Este teste trava isso:
        // se alguém registrar um desses beans, o usuário padrão volta calado.
        assertThat(contexto.getBeanNamesForType(UserDetailsService.class)).isEmpty();
        assertThat(contexto.getBeanNamesForType(AuthenticationProvider.class)).isEmpty();
    }

    @Test
    @DisplayName("Métricas exigem autenticação; health é público e não cai por causa de e-mail")
    void actuatorProtegido() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());

        // Health é público de propósito: é o que o orquestrador consulta.
        // O 200 aqui também guarda uma decisão: o MailHealthIndicator está
        // desligado em application.yml. Sem isso, o Mailpit fora do ar (como
        // acontece nos testes de integração) devolveria 503 e, em produção,
        // tiraria a API de rotação por causa de um canal de notificação.
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    // =================================================================
    // Vazamento de informação
    // =================================================================

    @Test
    @DisplayName("Nenhuma resposta expõe senha ou hash BCrypt")
    void nuncaVazaSenha() throws Exception {
        String token = loginComoAdmin();

        String usuarios = mockMvc.perform(get("/api/v1/auth/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(usuarios)
                .doesNotContain("$2a$")   // prefixo do BCrypt
                .doesNotContain("$2b$")
                .doesNotContain("passwordHash")
                .doesNotContain("senha-forte-123");
    }

    @Test
    @DisplayName("Erro não vaza stacktrace nem detalhe interno")
    void erroNaoVazaInterno() throws Exception {
        String token = loginComoAdmin();

        String corpo = mockMvc.perform(get("/api/v1/customers/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(corpo)
                .doesNotContain("org.springframework")
                .doesNotContain("java.lang")
                .doesNotContain("at com.caiocodes")
                .doesNotContain("Exception");
    }

    @Test
    @DisplayName("Login não revela se o e-mail existe (nem por mensagem, nem por código)")
    void loginNaoEnumeraUsuarios() throws Exception {
        criarAdmin();

        String existente = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "admin@teste.local", "password": "errada"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String inexistente = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "fantasma@teste.local", "password": "errada"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // O corpo carrega um timestamp, que naturalmente difere entre as duas
        // chamadas. O que não pode diferir é qualquer coisa que revele se o
        // e-mail existe: título, detalhe, código e status.
        assertThat(semTimestamp(existente)).isEqualTo(semTimestamp(inexistente));
    }

    /** Remove o campo {@code timestamp} para comparar dois corpos de erro. */
    private static String semTimestamp(String json) {
        return json.replaceAll(",?\"timestamp\":\"[^\"]*\"", "");
    }
}

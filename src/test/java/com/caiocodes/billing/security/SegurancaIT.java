package com.caiocodes.billing.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.caiocodes.billing.AbstractIntegrationTest;
import com.caiocodes.billing.customer.repository.CustomerRepository;
import com.caiocodes.billing.invoice.repository.InvoiceRepository;
import com.caiocodes.billing.security.entity.User;
import com.caiocodes.billing.security.repository.RefreshTokenRepository;
import com.caiocodes.billing.security.repository.RoleRepository;
import com.caiocodes.billing.security.repository.UserRepository;
import com.caiocodes.billing.subscription.repository.SubscriptionRepository;
import com.jayway.jsonpath.JsonPath;

@AutoConfigureMockMvc
class SegurancaIT extends AbstractIntegrationTest {

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
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private InvoiceRepository invoiceRepository;

    // Sem @Transactional: o fluxo de login/refresh precisa de dados commitados.
    // Por isso a limpeza é explícita, antes E depois, para não deixar usuários
    // para os testes vizinhos.
    @BeforeEach
    void limparAntes() { limpar(); }

    @AfterEach
    void limparDepois() { limpar(); }

    private void limpar() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        // O teste da matriz de papéis cadastra um cliente pela API, e isso
        // commita. Sem limpar aqui, o CPF fixo colide com o índice único nos
        // testes vizinhos — que passam a falhar por um motivo que não é deles.
        invoiceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        customerRepository.deleteAll();
    }

    private void criarUsuario(String email, String senha, String papel) {
        userRepository.saveAndFlush(new User(
                "Usuário " + papel, email, passwordEncoder.encode(senha),
                Set.of(roleRepository.findByName(papel).orElseThrow())));
    }

    private String login(String email, String senha) throws Exception {
        String corpo = """
                {"email": "%s", "password": "%s"}
                """.formatted(email, senha);

        String resposta = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return resposta;
    }

    @Test
    @DisplayName("Login válido devolve access e refresh")
    void loginValido() throws Exception {
        criarUsuario("admin@teste.local", "senha-forte-123", "ADMIN");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "admin@teste.local", "password": "senha-forte-123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(900));
    }

    @Test
    @DisplayName("Senha errada e e-mail inexistente dão a MESMA resposta")
    void mensagemNaoRevelaSeOEmailExiste() throws Exception {
        criarUsuario("existe@teste.local", "senha-forte-123", "ADMIN");

        // Diferenciar as duas entregaria de graça um verificador de e-mails
        // cadastrados — útil para quem quer montar uma lista de alvos.
        String senhaErrada = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "existe@teste.local", "password": "errada-mesmo"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String naoExiste = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "naoexiste@teste.local", "password": "errada-mesmo"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.read(senhaErrada, "$.code").toString())
                .isEqualTo(JsonPath.read(naoExiste, "$.code").toString());
    }

    @Test
    @DisplayName("Sem token, a API responde 401")
    void semTokenNaoEntra() throws Exception {
        mockMvc.perform(get("/api/v1/customers"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Com token válido, a API responde")
    void comTokenEntra() throws Exception {
        criarUsuario("admin@teste.local", "senha-forte-123", "ADMIN");
        String token = JsonPath.read(login("admin@teste.local", "senha-forte-123"),
                "$.accessToken");

        mockMvc.perform(get("/api/v1/customers").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    // =================================================================
    // Rotação e detecção de reuso
    // =================================================================

    @Test
    @DisplayName("Refresh rotaciona: o token antigo deixa de valer")
    void refreshRotaciona() throws Exception {
        criarUsuario("admin@teste.local", "senha-forte-123", "ADMIN");
        String primeiro = JsonPath.read(login("admin@teste.local", "senha-forte-123"),
                "$.refreshToken");

        String renovado = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"%s\"}".formatted(primeiro)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andReturn().getResponse().getContentAsString();

        String segundo = JsonPath.read(renovado, "$.refreshToken");
        assertThat(segundo).isNotEqualTo(primeiro);

        // O novo funciona.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"%s\"}".formatted(segundo)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Reapresentar um refresh já rotacionado revoga a FAMÍLIA inteira")
    void deteccaoDeReuso() throws Exception {
        criarUsuario("admin@teste.local", "senha-forte-123", "ADMIN");
        String primeiro = JsonPath.read(login("admin@teste.local", "senha-forte-123"),
                "$.refreshToken");

        String renovado = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"%s\"}".formatted(primeiro)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String segundo = JsonPath.read(renovado, "$.refreshToken");

        // Alguém usa o token velho — só há duas explicações: cliente confuso ou
        // credencial copiada. Como não dá para distinguir, encerra-se tudo.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"%s\"}".formatted(primeiro)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_REUTILIZADO"));

        // E o token que era válido cai junto: é o ponto da defesa.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"%s\"}".formatted(segundo)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Logout invalida o refresh")
    void logout() throws Exception {
        criarUsuario("admin@teste.local", "senha-forte-123", "ADMIN");
        String refresh = JsonPath.read(login("admin@teste.local", "senha-forte-123"),
                "$.refreshToken");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"%s\"}".formatted(refresh)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"%s\"}".formatted(refresh)))
                .andExpect(status().isUnauthorized());
    }

    // =================================================================
    // A matriz de papéis
    // =================================================================

    @Test
    @DisplayName("SUPPORT cadastra cliente mas não cria plano")
    void matrizDoSupport() throws Exception {
        criarUsuario("support@teste.local", "senha-forte-123", "SUPPORT");
        String token = JsonPath.read(login("support@teste.local", "senha-forte-123"),
                "$.accessToken");

        mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Cliente do Suporte",
                                 "email": "sup@exemplo.com.br",
                                 "document": "52998224725"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/plans")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Proibido", "monthlyPrice": 10.00, "userLimit": 1}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("FINANCIAL vê o dashboard; SUPPORT não")
    void matrizDoDashboard() throws Exception {
        criarUsuario("fin@teste.local", "senha-forte-123", "FINANCIAL");
        criarUsuario("sup@teste.local", "senha-forte-123", "SUPPORT");

        String tokenFin = JsonPath.read(login("fin@teste.local", "senha-forte-123"),
                "$.accessToken");
        String tokenSup = JsonPath.read(login("sup@teste.local", "senha-forte-123"),
                "$.accessToken");

        mockMvc.perform(get("/api/v1/dashboard/summary")
                        .header("Authorization", "Bearer " + tokenFin))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/dashboard/summary")
                        .header("Authorization", "Bearer " + tokenSup))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Só ADMIN gerencia usuários")
    void matrizDeUsuarios() throws Exception {
        criarUsuario("fin@teste.local", "senha-forte-123", "FINANCIAL");
        String token = JsonPath.read(login("fin@teste.local", "senha-forte-123"),
                "$.accessToken");

        mockMvc.perform(get("/api/v1/auth/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN cria usuário; a resposta nunca traz senha nem hash")
    void adminCriaUsuario() throws Exception {
        criarUsuario("admin@teste.local", "senha-forte-123", "ADMIN");
        String token = JsonPath.read(login("admin@teste.local", "senha-forte-123"),
                "$.accessToken");

        String resposta = mockMvc.perform(post("/api/v1/auth/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Novo Financeiro",
                                 "email": "novo@teste.local",
                                 "password": "outra-senha-forte",
                                 "roles": ["FINANCIAL"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roles[0]").value("FINANCIAL"))
                .andReturn().getResponse().getContentAsString();

        assertThat(resposta).doesNotContain("password").doesNotContain("$2a$");
    }

    @Test
    @DisplayName("Usuário desativado não consegue mais entrar")
    void usuarioDesativado() throws Exception {
        criarUsuario("admin@teste.local", "senha-forte-123", "ADMIN");
        criarUsuario("vai@teste.local", "senha-forte-123", "SUPPORT");

        String tokenAdmin = JsonPath.read(login("admin@teste.local", "senha-forte-123"),
                "$.accessToken");
        var alvo = userRepository.findByEmailIgnoreCase("vai@teste.local").orElseThrow();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/auth/users/{id}", alvo.getId())
                        .header("Authorization", "Bearer " + tokenAdmin))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "vai@teste.local", "password": "senha-forte-123"}
                                """))
                .andExpect(status().isUnauthorized());
    }
}

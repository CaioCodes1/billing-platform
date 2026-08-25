package com.caiocodes.billing.customer;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import com.caiocodes.billing.AbstractIntegrationTest;
import com.caiocodes.billing.customer.entity.Customer;
import com.caiocodes.billing.customer.repository.CustomerRepository;

/**
 * Exercita a API de clientes de ponta a ponta contra o Postgres do
 * Testcontainers: JSON entra pelo controller, passa por validação, service,
 * Hibernate e migrations reais.
 *
 * <p>{@code @Transactional} faz cada teste terminar em rollback, então a base
 * volta limpa sem nenhuma rotina de limpeza.
 */
@AutoConfigureMockMvc
@Transactional
// ADMIN porque estes testes exercitam a API inteira; a matriz de papéis
// tem teste próprio em SegurancaIT.
@WithMockUser(roles = "ADMIN")
class CustomerControllerIT extends AbstractIntegrationTest {

    private static final String CPF = "52998224725";
    private static final String CPF_2 = "11144477735";
    private static final String CNPJ = "11222333000181";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomerRepository repository;

    @Test
    @DisplayName("POST cadastra, devolve 201 e o cabeçalho Location")
    void cadastra() throws Exception {
        String corpo = """
                {
                  "name": "Padaria do Bairro Ltda",
                  "email": "financeiro@padaria.com.br",
                  "document": "11.222.333/0001-81",
                  "phone": "(11) 98765-4321"
                }
                """;

        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                // A pontuação foi descartada na borda, nos dois campos.
                .andExpect(jsonPath("$.document").value(CNPJ))
                .andExpect(jsonPath("$.phone").value("11987654321"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    @DisplayName("POST com documento inválido devolve 422 apontando o campo")
    void recusaDocumentoInvalido() throws Exception {
        String corpo = """
                {
                  "name": "Cliente Teste",
                  "email": "teste@exemplo.com.br",
                  "document": "52998224726"
                }
                """;

        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDACAO_FALHOU"))
                .andExpect(jsonPath("$.errors[0].field").value("document"));
    }

    @Test
    @DisplayName("POST com e-mail já cadastrado devolve 409")
    void recusaEmailDuplicado() throws Exception {
        repository.save(new Customer("Existente", "ocupado@exemplo.com.br", CPF, null));

        String corpo = """
                {
                  "name": "Outro Cliente",
                  "email": "OCUPADO@exemplo.com.br",
                  "document": "%s"
                }
                """.formatted(CPF_2);

        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_JA_CADASTRADO"));
    }

    @Test
    @DisplayName("GET de id inexistente devolve 404")
    void buscaInexistente() throws Exception {
        mockMvc.perform(get("/api/v1/customers/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NAO_ENCONTRADO"));
    }

    @Test
    @DisplayName("GET lista filtra por nome e por status")
    void listaComFiltros() throws Exception {
        Customer padaria = repository.save(
                new Customer("Padaria do Bairro", "padaria@exemplo.com.br", CPF, null));
        repository.save(
                new Customer("Mercado Central", "mercado@exemplo.com.br", CPF_2, null));
        Customer inativa = repository.save(
                new Customer("Padaria Antiga", "antiga@exemplo.com.br", CNPJ, null));
        inativa.deactivate();
        repository.flush();

        // Filtro parcial e sem diferenciar maiúsculas: pega as duas padarias.
        mockMvc.perform(get("/api/v1/customers").param("name", "padaria"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(2));

        // Combinando os dois filtros, sobra uma.
        mockMvc.perform(get("/api/v1/customers")
                        .param("name", "padaria")
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(padaria.getId().toString()));
    }

    @Test
    @DisplayName("GET lista respeita paginação e devolve o envelope próprio")
    void pagina() throws Exception {
        repository.save(new Customer("Cliente A", "a@exemplo.com.br", CPF, null));
        repository.save(new Customer("Cliente B", "b@exemplo.com.br", CPF_2, null));
        repository.save(new Customer("Cliente C", "c@exemplo.com.br", CNPJ, null));

        mockMvc.perform(get("/api/v1/customers").param("size", "2").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false));
    }

    @Test
    @DisplayName("Ordenar por campo inexistente devolve 400, não 500")
    void ordenacaoInvalida() throws Exception {
        mockMvc.perform(get("/api/v1/customers").param("sort", "campoQueNaoExiste"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ORDENACAO_INVALIDA"));
    }

    @Test
    @DisplayName("PUT atualiza os campos editáveis e preserva o documento")
    void atualiza() throws Exception {
        Customer cliente = repository.save(
                new Customer("Nome Antigo", "antigo@exemplo.com.br", CPF, "11111111111"));

        String corpo = """
                {
                  "name": "Nome Novo",
                  "email": "novo@exemplo.com.br",
                  "phone": "11999998888"
                }
                """;

        mockMvc.perform(put("/api/v1/customers/{id}", cliente.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Nome Novo"))
                .andExpect(jsonPath("$.email").value("novo@exemplo.com.br"))
                // Documento não é editável e continua o mesmo.
                .andExpect(jsonPath("$.document").value(CPF));
    }

    @Test
    @DisplayName("DELETE desativa sem apagar, e repetir não é erro")
    void desativa() throws Exception {
        Customer cliente = repository.save(
                new Customer("Para Desativar", "sai@exemplo.com.br", CPF, null));

        mockMvc.perform(delete("/api/v1/customers/{id}", cliente.getId()))
                .andExpect(status().isNoContent());

        // Continua existindo — o histórico financeiro depende dele.
        mockMvc.perform(get("/api/v1/customers/{id}", cliente.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        mockMvc.perform(delete("/api/v1/customers/{id}", cliente.getId()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("POST /activate reativa um cliente desativado")
    void reativa() throws Exception {
        Customer cliente = repository.save(
                new Customer("Volta", "volta@exemplo.com.br", CPF, null));
        cliente.deactivate();
        repository.flush();

        mockMvc.perform(post("/api/v1/customers/{id}/activate", cliente.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }
}

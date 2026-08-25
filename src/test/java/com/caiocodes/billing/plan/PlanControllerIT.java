package com.caiocodes.billing.plan;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import com.caiocodes.billing.AbstractIntegrationTest;
import com.caiocodes.billing.plan.entity.Plan;
import com.caiocodes.billing.plan.repository.PlanRepository;

@AutoConfigureMockMvc
@Transactional
// ADMIN porque estes testes exercitam a API inteira; a matriz de papéis
// tem teste próprio em SegurancaIT.
@WithMockUser(roles = "ADMIN")
class PlanControllerIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlanRepository repository;

    @Test
    @DisplayName("POST cria plano ativo, em BRL, com Location")
    void cria() throws Exception {
        String corpo = """
                {
                  "name": "Profissional",
                  "description": "Até 25 usuários",
                  "monthlyPrice": 199.90,
                  "userLimit": 25
                }
                """;

        mockMvc.perform(post("/api/v1/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.monthlyPrice", closeTo(199.90, 0.0001)))
                .andExpect(jsonPath("$.currency").value("BRL"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("POST recusa nome repetido com 409")
    void recusaNomeDuplicado() throws Exception {
        repository.save(new Plan("Básico", null, new BigDecimal("49.90"), 5));

        String corpo = """
                {"name": "BÁSICO", "monthlyPrice": 59.90, "userLimit": 5}
                """;

        mockMvc.perform(post("/api/v1/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLANO_JA_CADASTRADO"));
    }

    @Test
    @DisplayName("POST recusa preço negativo e limite de usuários zero")
    void recusaValoresInvalidos() throws Exception {
        String corpo = """
                {"name": "Inválido", "monthlyPrice": -10.00, "userLimit": 0}
                """;

        mockMvc.perform(post("/api/v1/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDACAO_FALHOU"))
                .andExpect(jsonPath("$.errors", hasSize(2)));
    }

    @Test
    @DisplayName("POST recusa preço com mais casas decimais do que a coluna guarda")
    void recusaPrecisaoExcessiva() throws Exception {
        // NUMERIC(19,4): sem o @Digits, 99.999999 seria arredondado em silêncio
        // e o cliente nunca saberia que o plano não vale o que ele pediu.
        String corpo = """
                {"name": "Preciso Demais", "monthlyPrice": 99.999999, "userLimit": 5}
                """;

        mockMvc.perform(post("/api/v1/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("monthlyPrice"));
    }

    @Test
    @DisplayName("GET lista filtra por faixa de preço e por ativo")
    void listaComFiltros() throws Exception {
        repository.save(new Plan("Básico", null, new BigDecimal("49.90"), 5));
        repository.save(new Plan("Profissional", null, new BigDecimal("199.90"), 25));
        Plan enterprise = repository.save(
                new Plan("Enterprise", null, new BigDecimal("999.90"), 500));
        enterprise.deactivate();
        repository.flush();

        mockMvc.perform(get("/api/v1/plans")
                        .param("minPrice", "100.00")
                        .param("maxPrice", "500.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].name").value("Profissional"));

        mockMvc.perform(get("/api/v1/plans").param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));
    }

    @Test
    @DisplayName("GET lista vem ordenada por preço crescente por padrão")
    void ordenacaoPadrao() throws Exception {
        repository.save(new Plan("Enterprise", null, new BigDecimal("999.90"), 500));
        repository.save(new Plan("Básico", null, new BigDecimal("49.90"), 5));
        repository.save(new Plan("Profissional", null, new BigDecimal("199.90"), 25));

        mockMvc.perform(get("/api/v1/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Básico"))
                .andExpect(jsonPath("$.content[1].name").value("Profissional"))
                .andExpect(jsonPath("$.content[2].name").value("Enterprise"));
    }

    @Test
    @DisplayName("PUT reajusta o preço do plano")
    void reajusta() throws Exception {
        Plan plano = repository.save(
                new Plan("Profissional", null, new BigDecimal("199.90"), 25));

        String corpo = """
                {"name": "Profissional", "monthlyPrice": 249.90, "userLimit": 30}
                """;

        mockMvc.perform(put("/api/v1/plans/{id}", plano.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyPrice", closeTo(249.90, 0.0001)))
                .andExpect(jsonPath("$.userLimit").value(30));
    }

    @Test
    @DisplayName("DELETE tira do catálogo; repetir devolve 422")
    void desativa() throws Exception {
        Plan plano = repository.save(
                new Plan("Descontinuado", null, new BigDecimal("29.90"), 3));

        mockMvc.perform(delete("/api/v1/plans/{id}", plano.getId()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/plans/{id}", plano.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(delete("/api/v1/plans/{id}", plano.getId()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PLANO_JA_INATIVO"));
    }

    @Test
    @DisplayName("POST /activate devolve o plano ao catálogo")
    void reativa() throws Exception {
        Plan plano = repository.save(
                new Plan("Sazonal", null, new BigDecimal("19.90"), 2));
        plano.deactivate();
        repository.flush();

        mockMvc.perform(post("/api/v1/plans/{id}/activate", plano.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }
}

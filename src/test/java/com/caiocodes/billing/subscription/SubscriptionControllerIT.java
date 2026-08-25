package com.caiocodes.billing.subscription;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

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
import com.caiocodes.billing.plan.entity.Plan;
import com.caiocodes.billing.plan.repository.PlanRepository;

@AutoConfigureMockMvc
@Transactional
// ADMIN porque estes testes exercitam a API inteira; a matriz de papéis
// tem teste próprio em SegurancaIT.
@WithMockUser(roles = "ADMIN")
class SubscriptionControllerIT extends AbstractIntegrationTest {

    private static final String CPF = "52998224725";
    private static final String CPF_2 = "11144477735";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private PlanRepository planRepository;

    private Customer novoCliente(String email, String documento) {
        return customerRepository.saveAndFlush(
                new Customer("Cliente " + documento, email, documento, null));
    }

    private Plan novoPlano(String nome, String preco) {
        return planRepository.saveAndFlush(
                new Plan(nome, null, new BigDecimal(preco), 25));
    }

    private String corpo(Customer c, Plan p, LocalDate inicio) {
        String data = inicio == null ? "null" : "\"" + inicio + "\"";
        return """
                {"customerId": "%s", "planId": "%s", "startDate": %s}
                """.formatted(c.getId(), p.getId(), data);
    }

    @Test
    @DisplayName("POST contrata, copia o preço e devolve os resumos aninhados")
    void contrata() throws Exception {
        Customer cliente = novoCliente("a@exemplo.com.br", CPF);
        Plan plano = novoPlano("Profissional", "199.90");

        mockMvc.perform(post("/api/v1/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(cliente, plano, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.unitPrice", closeTo(199.90, 0.0001)))
                .andExpect(jsonPath("$.customer.name").value(cliente.getName()))
                .andExpect(jsonPath("$.plan.name").value("Profissional"))
                .andExpect(jsonPath("$.nextRenewalDate").exists());
    }

    // =================================================================
    // A garantia central do desenho do sistema
    // =================================================================
    @Test
    @DisplayName("Reajustar o plano NÃO repreça quem já assinou")
    void reajusteNaoAfetaContratoVivo() throws Exception {
        Customer cliente = novoCliente("b@exemplo.com.br", CPF);
        Plan plano = novoPlano("Profissional", "199.90");

        String resposta = mockMvc.perform(post("/api/v1/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(cliente, plano, null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String idAssinatura = com.jayway.jsonpath.JsonPath.read(resposta, "$.id");

        // A empresa reajusta o plano em 25%.
        mockMvc.perform(put("/api/v1/plans/{id}", plano.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Profissional", "monthlyPrice": 249.90, "userLimit": 25}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyPrice", closeTo(249.90, 0.0001)));

        // O contrato existente continua a 199,90. É o snapshot em
        // subscriptions.unit_price fazendo o trabalho dele.
        mockMvc.perform(get("/api/v1/subscriptions/{id}", idAssinatura))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unitPrice", closeTo(199.90, 0.0001)))
                // ...e o plano associado já mostra o preço novo, provando que
                // não é cache: são dois valores distintos de propósito.
                .andExpect(jsonPath("$.plan.monthlyPrice", closeTo(249.90, 0.0001)));

        // Migrar é uma decisão explícita.
        mockMvc.perform(post("/api/v1/subscriptions/{id}/migrate-price", idAssinatura))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unitPrice", closeTo(249.90, 0.0001)));
    }

    @Test
    @DisplayName("Data de início futura nasce PENDING e respeita o dia 31")
    void inicioFuturoComDia31() throws Exception {
        Customer cliente = novoCliente("c@exemplo.com.br", CPF);
        Plan plano = novoPlano("Básico", "49.90");

        mockMvc.perform(post("/api/v1/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(cliente, plano, LocalDate.of(2027, 1, 31))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.billingDay").value(31))
                .andExpect(jsonPath("$.currentPeriodStart").value("2027-01-31"))
                // Fevereiro de 2027 tem 28 dias.
                .andExpect(jsonPath("$.currentPeriodEnd").value("2027-02-28"));
    }

    @Test
    @DisplayName("Cliente com assinatura em vigor recebe 409")
    void umaAssinaturaPorCliente() throws Exception {
        Customer cliente = novoCliente("d@exemplo.com.br", CPF);
        Plan plano = novoPlano("Profissional", "199.90");

        mockMvc.perform(post("/api/v1/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(cliente, plano, null)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(cliente, plano, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSINATURA_ATIVA_EXISTENTE"));
    }

    @Test
    @DisplayName("Depois de cancelar, o cliente pode contratar de novo")
    void cancelarLiberaAVaga() throws Exception {
        Customer cliente = novoCliente("e@exemplo.com.br", CPF);
        Plan plano = novoPlano("Profissional", "199.90");

        String resposta = mockMvc.perform(post("/api/v1/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(cliente, plano, null)))
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(resposta, "$.id");

        mockMvc.perform(post("/api/v1/subscriptions/{id}/cancel", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").exists());

        mockMvc.perform(post("/api/v1/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(cliente, plano, null)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Cancelar duas vezes devolve 422 pela máquina de estados")
    void cancelarDuasVezes() throws Exception {
        Customer cliente = novoCliente("f@exemplo.com.br", CPF);
        Plan plano = novoPlano("Profissional", "199.90");

        String resposta = mockMvc.perform(post("/api/v1/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(cliente, plano, null)))
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(resposta, "$.id");

        mockMvc.perform(post("/api/v1/subscriptions/{id}/cancel", id))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/subscriptions/{id}/cancel", id))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSICAO_INVALIDA"));
    }

    @Test
    @DisplayName("Cliente desativado não contrata")
    void clienteInativoNaoContrata() throws Exception {
        Customer cliente = novoCliente("g@exemplo.com.br", CPF);
        cliente.deactivate();
        customerRepository.flush();
        Plan plano = novoPlano("Profissional", "199.90");

        mockMvc.perform(post("/api/v1/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(cliente, plano, null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLIENTE_INATIVO"));
    }

    @Test
    @DisplayName("Plano fora do catálogo não pode ser contratado")
    void planoInativoNaoContrata() throws Exception {
        Customer cliente = novoCliente("h@exemplo.com.br", CPF);
        Plan plano = novoPlano("Descontinuado", "99.90");
        plano.deactivate();
        planRepository.flush();

        mockMvc.perform(post("/api/v1/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(cliente, plano, null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PLANO_INATIVO"));
    }

    @Test
    @DisplayName("Início retroativo é recusado")
    void inicioRetroativo() throws Exception {
        Customer cliente = novoCliente("i@exemplo.com.br", CPF);
        Plan plano = novoPlano("Profissional", "199.90");

        mockMvc.perform(post("/api/v1/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(cliente, plano, LocalDate.of(2020, 1, 1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INICIO_RETROATIVO"));
    }

    @Test
    @DisplayName("GET lista filtra por cliente e por status")
    void listaComFiltros() throws Exception {
        Customer um = novoCliente("j@exemplo.com.br", CPF);
        Customer dois = novoCliente("k@exemplo.com.br", CPF_2);
        Plan plano = novoPlano("Profissional", "199.90");

        mockMvc.perform(post("/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON).content(corpo(um, plano, null)));
        mockMvc.perform(post("/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON).content(corpo(dois, plano, null)));

        mockMvc.perform(get("/api/v1/subscriptions").param("customerId", um.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].customer.id").value(um.getId().toString()));

        mockMvc.perform(get("/api/v1/subscriptions").param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));

        mockMvc.perform(get("/api/v1/subscriptions").param("status", "CANCELLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    @DisplayName("Trocar de plano adota o preço do novo")
    void trocaDePlano() throws Exception {
        Customer cliente = novoCliente("l@exemplo.com.br", CPF);
        Plan basico = novoPlano("Básico", "49.90");
        Plan enterprise = novoPlano("Enterprise", "999.90");

        String resposta = mockMvc.perform(post("/api/v1/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(cliente, basico, null)))
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(resposta, "$.id");

        mockMvc.perform(post("/api/v1/subscriptions/{id}/change-plan", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\": \"%s\"}".formatted(enterprise.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.name").value("Enterprise"))
                .andExpect(jsonPath("$.unitPrice", closeTo(999.90, 0.0001)));
    }
}

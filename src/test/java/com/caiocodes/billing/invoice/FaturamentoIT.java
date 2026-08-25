package com.caiocodes.billing.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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
import com.caiocodes.billing.invoice.entity.Invoice;
import com.caiocodes.billing.invoice.repository.InvoiceRepository;
import com.caiocodes.billing.plan.entity.Plan;
import com.caiocodes.billing.plan.repository.PlanRepository;
import com.jayway.jsonpath.JsonPath;

@AutoConfigureMockMvc
@Transactional
// ADMIN porque estes testes exercitam a API inteira; a matriz de papéis
// tem teste próprio em SegurancaIT.
@WithMockUser(roles = "ADMIN")
class FaturamentoIT extends AbstractIntegrationTest {

    private static final String CPF = "52998224725";
    private static final String CPF_2 = "11144477735";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private InvoiceRepository invoiceRepository;

    private Customer novoCliente(String email, String documento) {
        return customerRepository.saveAndFlush(
                new Customer("Cliente " + documento, email, documento, null));
    }

    private Plan novoPlano(String nome, String preco) {
        return planRepository.saveAndFlush(new Plan(nome, null, new BigDecimal(preco), 25));
    }

    private String contratar(Customer c, Plan p, LocalDate inicio) throws Exception {
        String data = inicio == null ? "null" : "\"" + inicio + "\"";
        String corpo = """
                {"customerId": "%s", "planId": "%s", "startDate": %s}
                """.formatted(c.getId(), p.getId(), data);

        String resposta = mockMvc.perform(post("/api/v1/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return JsonPath.read(resposta, "$.id");
    }

    @Test
    @DisplayName("Contratar emite a primeira cobrança na mesma transação")
    void contratarEmiteAPrimeiraCobranca() throws Exception {
        Customer cliente = novoCliente("a@exemplo.com.br", CPF);
        Plan plano = novoPlano("Profissional", "199.90");

        String idAssinatura = contratar(cliente, plano, null);

        mockMvc.perform(get("/api/v1/invoices").param("subscriptionId", idAssinatura))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[0].amount", closeTo(199.90, 0.0001)))
                .andExpect(jsonPath("$.content[0].subscription.customerName")
                        .value(cliente.getName()))
                .andExpect(jsonPath("$.content[0].subscription.planName").value("Profissional"));
    }

    // =================================================================
    // A garantia que o índice único existe para dar
    // =================================================================
    @Test
    @DisplayName("Emitir a mesma competência dez vezes produz UMA cobrança")
    void emissaoEhIdempotente() throws Exception {
        Customer cliente = novoCliente("b@exemplo.com.br", CPF);
        Plan plano = novoPlano("Profissional", "199.90");
        String idAssinatura = contratar(cliente, plano, null);

        // A contratação já emitiu a primeira. Agora mais dez tentativas.
        String idPrimeira = null;
        for (int i = 0; i < 10; i++) {
            String resposta = mockMvc.perform(
                            post("/api/v1/subscriptions/{id}/invoices", idAssinatura))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            String id = JsonPath.read(resposta, "$.id");
            if (idPrimeira == null) {
                idPrimeira = id;
            }
            // Toda chamada devolve exatamente a mesma cobrança.
            assertThat(id).isEqualTo(idPrimeira);
        }

        List<Invoice> todas = invoiceRepository
                .findBySubscriptionIdOrderByPeriodStartDesc(java.util.UUID.fromString(idAssinatura));
        assertThat(todas).hasSize(1);
    }

    @Test
    @DisplayName("O valor da cobrança é o do contrato, não o do catálogo")
    void cobrancaUsaOPrecoCongelado() throws Exception {
        Customer cliente = novoCliente("c@exemplo.com.br", CPF);
        Plan plano = novoPlano("Profissional", "199.90");
        String idAssinatura = contratar(cliente, plano, null);

        // Reajuste depois da emissão.
        mockMvc.perform(put("/api/v1/plans/{id}", plano.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Profissional", "monthlyPrice": 249.90, "userLimit": 25}
                                """))
                .andExpect(status().isOk());

        // A cobrança emitida não se move: são duas cópias em série
        // (plano → assinatura → cobrança), cada uma protegendo a anterior.
        mockMvc.perform(get("/api/v1/invoices").param("subscriptionId", idAssinatura))
                .andExpect(jsonPath("$.content[0].amount", closeTo(199.90, 0.0001)));
    }

    @Test
    @DisplayName("Assinatura futura não gera cobrança na contratação")
    void assinaturaPendenteNaoGeraCobranca() throws Exception {
        Customer cliente = novoCliente("d@exemplo.com.br", CPF);
        Plan plano = novoPlano("Básico", "49.90");

        String idAssinatura = contratar(cliente, plano, LocalDate.of(2027, 1, 31));

        mockMvc.perform(get("/api/v1/invoices").param("subscriptionId", idAssinatura))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));

        // E a emissão manual explica por quê, em vez de falhar em silêncio.
        mockMvc.perform(post("/api/v1/subscriptions/{id}/invoices", idAssinatura))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SEM_COBRANCA_A_EMITIR"));
    }

    @Test
    @DisplayName("Plano gratuito não gera cobrança de R$ 0,00")
    void planoGratuito() throws Exception {
        Customer cliente = novoCliente("e@exemplo.com.br", CPF);
        Plan gratuito = novoPlano("Free", "0.00");

        String idAssinatura = contratar(cliente, gratuito, null);

        mockMvc.perform(get("/api/v1/invoices").param("subscriptionId", idAssinatura))
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    @DisplayName("Cancelar assinatura não apaga as cobranças já emitidas")
    void cancelarPreservaHistorico() throws Exception {
        Customer cliente = novoCliente("f@exemplo.com.br", CPF);
        Plan plano = novoPlano("Profissional", "199.90");
        String idAssinatura = contratar(cliente, plano, null);

        mockMvc.perform(post("/api/v1/subscriptions/{id}/cancel", idAssinatura))
                .andExpect(status().isOk());

        // A dívida sobrevive ao cancelamento — é o histórico financeiro.
        mockMvc.perform(get("/api/v1/invoices").param("subscriptionId", idAssinatura))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"));
    }

    @Test
    @DisplayName("Cobrança pode ser anulada, e anular duas vezes é 422")
    void anulaCobranca() throws Exception {
        Customer cliente = novoCliente("g@exemplo.com.br", CPF);
        Plan plano = novoPlano("Profissional", "199.90");
        String idAssinatura = contratar(cliente, plano, null);

        String lista = mockMvc.perform(get("/api/v1/invoices")
                        .param("subscriptionId", idAssinatura))
                .andReturn().getResponse().getContentAsString();
        String idCobranca = JsonPath.read(lista, "$.content[0].id");

        mockMvc.perform(post("/api/v1/invoices/{id}/cancel", idCobranca))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(post("/api/v1/invoices/{id}/cancel", idCobranca))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSICAO_INVALIDA"));
    }

    @Test
    @DisplayName("Filtro por cliente atravessa a assinatura")
    void filtraPorCliente() throws Exception {
        Customer um = novoCliente("h@exemplo.com.br", CPF);
        Customer dois = novoCliente("i@exemplo.com.br", CPF_2);
        Plan plano = novoPlano("Profissional", "199.90");

        contratar(um, plano, null);
        contratar(dois, plano, null);

        mockMvc.perform(get("/api/v1/invoices").param("customerId", um.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].subscription.customerId")
                        .value(um.getId().toString()));

        mockMvc.perform(get("/api/v1/invoices").param("openOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));
    }
}

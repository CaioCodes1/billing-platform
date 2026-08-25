package com.caiocodes.billing.dashboard;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.caiocodes.billing.AbstractIntegrationTest;
import com.caiocodes.billing.customer.entity.Customer;
import com.caiocodes.billing.customer.repository.CustomerRepository;
import com.caiocodes.billing.invoice.repository.InvoiceRepository;
import com.caiocodes.billing.plan.entity.Plan;
import com.caiocodes.billing.plan.repository.PlanRepository;
import com.jayway.jsonpath.JsonPath;

@AutoConfigureMockMvc
@Transactional
@WithMockUser(roles = "FINANCIAL")
class DashboardIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private InvoiceRepository invoiceRepository;

    private String contratar(String email, String documento, String preco) throws Exception {
        Customer cliente = customerRepository.saveAndFlush(
                new Customer("Cliente " + documento, email, documento, null));
        Plan plano = planRepository.saveAndFlush(
                new Plan("Plano " + documento, null, new BigDecimal(preco), 25));

        String resposta = mockMvc.perform(post("/api/v1/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId": "%s", "planId": "%s"}
                                """.formatted(cliente.getId(), plano.getId())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return JsonPath.read(resposta, "$.id");
    }

    private String primeiraCobranca(String idAssinatura) throws Exception {
        String lista = mockMvc.perform(get("/api/v1/invoices")
                        .param("subscriptionId", idAssinatura))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(lista, "$.content[0].id");
    }

    @Test
    @DisplayName("Resumo separa recebido, em aberto e receita recorrente")
    void resumo() throws Exception {
        String a = contratar("a@exemplo.com.br", "52998224725", "199.90");
        contratar("b@exemplo.com.br", "11144477735", "99.90");

        // Nada pago ainda: tudo em aberto.
        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isOk())
                // JSON 0 chega como Integer; closeTo exige Double. Para o zero, value() basta.
                .andExpect(jsonPath("$.totalReceived").value(0))
                .andExpect(jsonPath("$.totalPending", closeTo(299.80, 0.0001)))
                .andExpect(jsonPath("$.activeSubscriptions").value(2))
                // MRR usa o valor CONTRATADO, não o preço de catálogo.
                .andExpect(jsonPath("$.monthlyRecurringRevenue", closeTo(299.80, 0.0001)));

        // Paga uma delas.
        mockMvc.perform(post("/api/v1/invoices/{id}/payments", primeiraCobranca(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method": "PIX", "amount": 199.90}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalReceived", closeTo(199.90, 0.0001)))
                .andExpect(jsonPath("$.totalPending", closeTo(99.90, 0.0001)));
    }

    @Test
    @DisplayName("Faturamento anual traz os doze meses, mesmo os zerados")
    void faturamentoAnual() throws Exception {
        contratar("c@exemplo.com.br", "52998224725", "150.00");
        int ano = LocalDate.now().getYear();

        mockMvc.perform(get("/api/v1/dashboard/revenue/{year}", ano))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.year").value(ano))
                // Um gráfico com meses faltando mente sobre a série temporal.
                .andExpect(jsonPath("$.months", hasSize(12)))
                .andExpect(jsonPath("$.invoiced", closeTo(150.00, 0.0001)));
    }

    @Test
    @DisplayName("Faturado e recebido são números diferentes — competência vs. caixa")
    void faturadoNaoEhRecebido() throws Exception {
        String a = contratar("d@exemplo.com.br", "52998224725", "300.00");
        int ano = LocalDate.now().getYear();

        // Emitiu 300, recebeu 0.
        mockMvc.perform(get("/api/v1/dashboard/revenue/{year}", ano))
                .andExpect(jsonPath("$.invoiced", closeTo(300.00, 0.0001)))
                .andExpect(jsonPath("$.received").value(0));

        // Pagamento parcial: o caixa anda, a competência não.
        mockMvc.perform(post("/api/v1/invoices/{id}/payments", primeiraCobranca(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method": "BOLETO", "amount": 100.00}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/dashboard/revenue/{year}", ano))
                .andExpect(jsonPath("$.invoiced", closeTo(300.00, 0.0001)))
                .andExpect(jsonPath("$.received", closeTo(100.00, 0.0001)));
    }

    @Test
    @DisplayName("Estorno reduz o recebido, sem apagar o faturado")
    void estornoReduzORecebido() throws Exception {
        String a = contratar("e@exemplo.com.br", "52998224725", "200.00");
        String cobranca = primeiraCobranca(a);
        int ano = LocalDate.now().getYear();

        mockMvc.perform(post("/api/v1/invoices/{id}/payments", cobranca)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method": "PIX", "amount": 200.00}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/invoices/{id}/refunds", cobranca)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method": "PIX", "amount": 50.00}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/dashboard/revenue/{year}", ano))
                .andExpect(jsonPath("$.invoiced", closeTo(200.00, 0.0001)))
                .andExpect(jsonPath("$.received", closeTo(150.00, 0.0001)));
    }

    @Test
    @DisplayName("Lista de inadimplentes traz quem tem cobrança vencida")
    void inadimplentes() throws Exception {
        String a = contratar("f@exemplo.com.br", "52998224725", "80.00");

        // Sem nada vencido, a lista está vazia.
        mockMvc.perform(get("/api/v1/dashboard/delinquent-customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // Marca a cobrança como vencida direto, para não depender do relógio.
        var cobranca = invoiceRepository.findById(
                java.util.UUID.fromString(primeiraCobranca(a))).orElseThrow();
        cobranca.markOverdue();
        invoiceRepository.flush();

        mockMvc.perform(get("/api/v1/dashboard/delinquent-customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].customerEmail").value("f@exemplo.com.br"))
                .andExpect(jsonPath("$[0].overdueInvoices").value(1))
                .andExpect(jsonPath("$[0].overdueAmount", closeTo(80.00, 0.0001)));
    }
}

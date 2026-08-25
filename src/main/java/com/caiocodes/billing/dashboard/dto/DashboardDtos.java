package com.caiocodes.billing.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public final class DashboardDtos {

    private DashboardDtos() {
    }

    /**
     * Faturamento de um mês.
     *
     * <p>Repare que são <strong>dois</strong> números, e a diferença entre eles
     * é o ponto do relatório: {@code faturado} é o que foi cobrado no período
     * (competência), {@code recebido} é o dinheiro que efetivamente entrou
     * (caixa). Confundir os dois é o erro clássico de dashboard financeiro —
     * um mês pode ter faturamento alto e caixa baixo, e é exatamente isso que
     * o gestor precisa enxergar.
     */
    @Schema(description = "Faturamento e recebimento de um mês")
    public record MonthlyRevenue(
            int year,
            int month,
            @Schema(description = "Somatório das cobranças emitidas na competência")
            BigDecimal invoiced,
            @Schema(description = "Somatório do que entrou de fato, líquido de estornos")
            BigDecimal received,
            long invoiceCount) {
    }

    @Schema(description = "Faturamento de um ano, com o detalhe mês a mês")
    public record AnnualRevenue(
            int year,
            BigDecimal invoiced,
            BigDecimal received,
            List<MonthlyRevenue> months) {
    }

    @Schema(description = "Números do momento")
    public record FinancialSummary(
            @Schema(description = "Total já recebido, líquido de estornos, em toda a história")
            BigDecimal totalReceived,
            @Schema(description = "Total em aberto: PENDING, PARTIALLY_PAID e OVERDUE")
            BigDecimal totalPending,
            @Schema(description = "Parcela do total em aberto que já passou do vencimento")
            BigDecimal totalOverdue,
            long activeSubscriptions,
            long suspendedSubscriptions,
            long delinquentCustomers,
            @Schema(description = "Receita recorrente mensal: soma do valor contratado "
                    + "das assinaturas em vigor")
            BigDecimal monthlyRecurringRevenue) {
    }

    @Schema(description = "Cliente com cobrança vencida")
    public record DelinquentCustomer(
            UUID customerId,
            String customerName,
            String customerEmail,
            long overdueInvoices,
            BigDecimal overdueAmount,
            @Schema(description = "Vencimento mais antigo em aberto")
            LocalDate oldestDueDate,
            long daysLate) {
    }
}

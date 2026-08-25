package com.caiocodes.billing.dashboard.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.caiocodes.billing.dashboard.dto.DashboardDtos.AnnualRevenue;
import com.caiocodes.billing.dashboard.dto.DashboardDtos.DelinquentCustomer;
import com.caiocodes.billing.dashboard.dto.DashboardDtos.FinancialSummary;
import com.caiocodes.billing.dashboard.dto.DashboardDtos.MonthlyRevenue;
import com.caiocodes.billing.dashboard.service.DashboardService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard", description = "Relatórios financeiros")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/revenue/{year}")
    @Operation(summary = "Faturamento anual, com detalhe mês a mês",
            description = "Distingue faturado (competência) de recebido (caixa).")
    @ApiResponse(responseCode = "403", description = "Requer ADMIN ou FINANCIAL")
    public AnnualRevenue annual(@PathVariable int year) {
        return service.faturamentoAnual(year);
    }

    @GetMapping("/revenue/{year}/{month}")
    @Operation(summary = "Faturamento de um mês")
    public MonthlyRevenue monthly(
            @PathVariable int year,
            @PathVariable @Min(1) @Max(12) int month) {
        return service.faturamentoMensal(year, month);
    }

    @GetMapping("/summary")
    @Operation(summary = "Números do momento",
            description = "Total recebido, em aberto, vencido, contagem de "
                    + "assinaturas e receita recorrente mensal.")
    public FinancialSummary summary() {
        return service.resumo();
    }

    @GetMapping("/delinquent-customers")
    @Operation(summary = "Clientes com cobrança vencida",
            description = "Ordenados pelo vencimento mais antigo — quem está "
                    + "devendo há mais tempo aparece primeiro.")
    public List<DelinquentCustomer> delinquent() {
        return service.inadimplentes();
    }

    @GetMapping("/revenue")
    @Operation(summary = "Faturamento do ano corrente")
    public AnnualRevenue currentYear(@RequestParam(required = false) Integer year) {
        return service.faturamentoAnual(year);
    }
}

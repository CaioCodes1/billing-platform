package com.caiocodes.billing.dashboard.service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.caiocodes.billing.dashboard.dto.DashboardDtos.AnnualRevenue;
import com.caiocodes.billing.dashboard.dto.DashboardDtos.DelinquentCustomer;
import com.caiocodes.billing.dashboard.dto.DashboardDtos.FinancialSummary;
import com.caiocodes.billing.dashboard.dto.DashboardDtos.MonthlyRevenue;
import com.caiocodes.billing.dashboard.repository.DashboardRepository;

/**
 * Relatórios financeiros. Só ADMIN e FINANCIAL — SUPPORT atende cliente, não
 * precisa ver o caixa da empresa.
 */
@Service
@PreAuthorize("hasAnyRole('ADMIN', 'FINANCIAL')")
public class DashboardService {

    private final DashboardRepository repository;
    private final Clock clock;

    public DashboardService(DashboardRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AnnualRevenue faturamentoAnual(Integer ano) {
        int alvo = ano == null ? LocalDate.now(clock).getYear() : ano;

        Map<Integer, BigDecimal> faturado = new HashMap<>();
        Map<Integer, Long> quantidades = new HashMap<>();
        for (Object[] linha : repository.faturadoPorMes(alvo)) {
            int mes = ((Number) linha[1]).intValue();
            faturado.put(mes, (BigDecimal) linha[2]);
            quantidades.put(mes, ((Number) linha[3]).longValue());
        }

        Map<Integer, BigDecimal> recebido = new HashMap<>();
        for (Object[] linha : repository.recebidoPorMes(alvo)) {
            recebido.put(((Number) linha[1]).intValue(), (BigDecimal) linha[2]);
        }

        // Os doze meses sempre aparecem, mesmo zerados: um gráfico com meses
        // faltando mente sobre a série temporal.
        List<MonthlyRevenue> meses = new ArrayList<>(12);
        BigDecimal totalFaturado = BigDecimal.ZERO;
        BigDecimal totalRecebido = BigDecimal.ZERO;

        for (int mes = 1; mes <= 12; mes++) {
            BigDecimal f = faturado.getOrDefault(mes, BigDecimal.ZERO);
            BigDecimal r = recebido.getOrDefault(mes, BigDecimal.ZERO);
            meses.add(new MonthlyRevenue(alvo, mes, f, r,
                    quantidades.getOrDefault(mes, 0L)));
            totalFaturado = totalFaturado.add(f);
            totalRecebido = totalRecebido.add(r);
        }

        return new AnnualRevenue(alvo, totalFaturado, totalRecebido, meses);
    }

    @Transactional(readOnly = true)
    public MonthlyRevenue faturamentoMensal(Integer ano, int mes) {
        return faturamentoAnual(ano).months().get(mes - 1);
    }

    @Transactional(readOnly = true)
    public FinancialSummary resumo() {
        return new FinancialSummary(
                repository.totalRecebido(),
                repository.totalEmAberto(),
                repository.totalVencido(),
                repository.contarAssinaturasPor("ACTIVE"),
                repository.contarAssinaturasPor("SUSPENDED"),
                repository.contarClientesInadimplentes(),
                repository.receitaRecorrenteMensal());
    }

    @Transactional(readOnly = true)
    public List<DelinquentCustomer> inadimplentes() {
        LocalDate hoje = LocalDate.now(clock);
        return repository.clientesInadimplentes(hoje).stream()
                .map(l -> new DelinquentCustomer(
                        (UUID) l[0],
                        (String) l[1],
                        (String) l[2],
                        ((Number) l[3]).longValue(),
                        (BigDecimal) l[4],
                        ((Date) l[5]).toLocalDate(),
                        ((Number) l[6]).longValue()))
                .toList();
    }
}

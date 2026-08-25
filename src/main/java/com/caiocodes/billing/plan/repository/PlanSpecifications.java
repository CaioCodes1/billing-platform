package com.caiocodes.billing.plan.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.springframework.data.jpa.domain.Specification;

import com.caiocodes.billing.plan.dto.PlanFilter;
import com.caiocodes.billing.plan.entity.Plan;

public final class PlanSpecifications {

    private PlanSpecifications() {
    }

    public static Specification<Plan> comFiltro(PlanFilter filtro) {
        List<Specification<Plan>> ativos = Stream.of(
                        nomeContendo(filtro.name()),
                        ativoIgual(filtro.active()),
                        precoAPartirDe(filtro.minPrice()),
                        precoAte(filtro.maxPrice()))
                .filter(Objects::nonNull)
                .toList();

        return Specification.allOf(ativos);
    }

    private static Specification<Plan> nomeContendo(String nome) {
        if (nome == null || nome.isBlank()) {
            return null;
        }
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("name")), "%" + nome.toLowerCase() + "%");
    }

    private static Specification<Plan> ativoIgual(Boolean ativo) {
        if (ativo == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("active"), ativo);
    }

    private static Specification<Plan> precoAPartirDe(BigDecimal minimo) {
        if (minimo == null) {
            return null;
        }
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("monthlyPrice"), minimo);
    }

    private static Specification<Plan> precoAte(BigDecimal maximo) {
        if (maximo == null) {
            return null;
        }
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("monthlyPrice"), maximo);
    }
}

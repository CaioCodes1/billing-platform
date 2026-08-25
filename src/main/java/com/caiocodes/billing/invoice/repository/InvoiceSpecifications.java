package com.caiocodes.billing.invoice.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.data.jpa.domain.Specification;

import com.caiocodes.billing.invoice.dto.InvoiceFilter;
import com.caiocodes.billing.invoice.entity.Invoice;
import com.caiocodes.billing.invoice.entity.InvoiceStatus;

import jakarta.persistence.criteria.JoinType;

public final class InvoiceSpecifications {

    private InvoiceSpecifications() {
    }

    public static Specification<Invoice> comFiltro(InvoiceFilter filtro) {
        List<Specification<Invoice>> ativos = Stream.of(
                        daAssinatura(filtro.subscriptionId()),
                        doCliente(filtro.customerId()),
                        comStatus(filtro.status()),
                        venceApartirDe(filtro.dueFrom()),
                        venceAte(filtro.dueTo()),
                        apenasEmAberto(filtro.openOnly()))
                .filter(Objects::nonNull)
                .toList();

        return Specification.allOf(ativos);
    }

    private static Specification<Invoice> daAssinatura(UUID subscriptionId) {
        if (subscriptionId == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("subscription").get("id"), subscriptionId);
    }

    private static Specification<Invoice> doCliente(UUID customerId) {
        if (customerId == null) {
            return null;
        }
        // Aqui o JOIN é inevitável: customer_id não é coluna de invoices.
        // JoinType.INNER explícito para não depender do padrão implícito.
        return (root, query, cb) ->
                cb.equal(root.join("subscription", JoinType.INNER).get("customer").get("id"),
                        customerId);
    }

    private static Specification<Invoice> comStatus(InvoiceStatus status) {
        if (status == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    private static Specification<Invoice> venceApartirDe(LocalDate de) {
        if (de == null) {
            return null;
        }
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("dueDate"), de);
    }

    private static Specification<Invoice> venceAte(LocalDate ate) {
        if (ate == null) {
            return null;
        }
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("dueDate"), ate);
    }

    private static Specification<Invoice> apenasEmAberto(Boolean somenteAbertas) {
        if (somenteAbertas == null || !somenteAbertas) {
            return null;
        }
        return (root, query, cb) -> root.get("status").in(
                InvoiceStatus.PENDING, InvoiceStatus.PARTIALLY_PAID, InvoiceStatus.OVERDUE);
    }
}

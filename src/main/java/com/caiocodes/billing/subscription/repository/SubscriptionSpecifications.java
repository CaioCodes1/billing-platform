package com.caiocodes.billing.subscription.repository;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.data.jpa.domain.Specification;

import com.caiocodes.billing.subscription.dto.SubscriptionFilter;
import com.caiocodes.billing.subscription.entity.Subscription;
import com.caiocodes.billing.subscription.entity.SubscriptionStatus;

public final class SubscriptionSpecifications {

    private SubscriptionSpecifications() {
    }

    public static Specification<Subscription> comFiltro(SubscriptionFilter filtro) {
        List<Specification<Subscription>> ativos = Stream.of(
                        doCliente(filtro.customerId()),
                        doPlano(filtro.planId()),
                        comStatus(filtro.status()))
                .filter(Objects::nonNull)
                .toList();

        return Specification.allOf(ativos);
    }

    private static Specification<Subscription> doCliente(UUID customerId) {
        if (customerId == null) {
            return null;
        }
        // get("customer").get("id") não força JOIN: o Hibernate usa a própria
        // coluna customer_id da tabela de assinaturas.
        return (root, query, cb) -> cb.equal(root.get("customer").get("id"), customerId);
    }

    private static Specification<Subscription> doPlano(UUID planId) {
        if (planId == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("plan").get("id"), planId);
    }

    private static Specification<Subscription> comStatus(SubscriptionStatus status) {
        if (status == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }
}

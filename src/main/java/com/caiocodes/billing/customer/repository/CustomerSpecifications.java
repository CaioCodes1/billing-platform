package com.caiocodes.billing.customer.repository;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.springframework.data.jpa.domain.Specification;

import com.caiocodes.billing.customer.dto.CustomerFilter;
import com.caiocodes.billing.customer.entity.Customer;
import com.caiocodes.billing.customer.entity.CustomerStatus;

/**
 * Filtros de listagem como {@link Specification}.
 *
 * <p>A alternativa seria uma {@code @Query} com {@code (:nome IS NULL OR ...)}
 * repetido para cada filtro. Funciona, mas o Postgres perde a chance de usar
 * índice — o predicado deixa de ser sargable. Com Specification, um filtro
 * ausente simplesmente não entra no SQL gerado.
 */
public final class CustomerSpecifications {

    private CustomerSpecifications() {
    }

    public static Specification<Customer> comFiltro(CustomerFilter filtro) {
        // Descarta explicitamente os filtros ausentes em vez de confiar em como
        // a versão atual do Spring Data trata null dentro de allOf.
        List<Specification<Customer>> ativos = Stream.of(
                        nomeContendo(filtro.name()),
                        emailContendo(filtro.email()),
                        documentoIgual(filtro.document()),
                        statusIgual(filtro.status()))
                .filter(Objects::nonNull)
                .toList();

        return Specification.allOf(ativos);
    }

    private static Specification<Customer> nomeContendo(String nome) {
        if (nome == null || nome.isBlank()) {
            return null;
        }
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("name")), "%" + nome.toLowerCase() + "%");
    }

    private static Specification<Customer> emailContendo(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("email")), "%" + email.toLowerCase() + "%");
    }

    private static Specification<Customer> documentoIgual(String documento) {
        if (documento == null || documento.isBlank()) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("document"), documento);
    }

    private static Specification<Customer> statusIgual(CustomerStatus status) {
        if (status == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }
}

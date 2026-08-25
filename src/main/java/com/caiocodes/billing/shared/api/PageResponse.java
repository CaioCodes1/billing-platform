package com.caiocodes.billing.shared.api;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * Envelope de paginação próprio.
 *
 * <p>Serializar {@code Page} do Spring Data direto no controller funciona, mas
 * acopla o contrato público da API à representação interna do Spring — e o
 * próprio Spring emite aviso sobre isso, porque o formato já mudou entre
 * versões. Este record é estável e é o que entra no OpenAPI.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public static <T> PageResponse<T> de(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}

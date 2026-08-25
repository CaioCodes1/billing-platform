package com.caiocodes.billing.shared.exception;

import org.springframework.http.HttpStatus;

/**
 * Raiz das exceções de negócio. Carrega o status HTTP porque é o
 * {@code GlobalExceptionHandler} — e só ele — que traduz domínio em HTTP.
 * O service lança {@code new ResourceNotFoundException(...)} sem nunca
 * importar nada de {@code org.springframework.web}.
 */
public abstract class DomainException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected DomainException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** Código estável para o cliente da API tratar programaticamente. */
    public String getCode() {
        return code;
    }
}

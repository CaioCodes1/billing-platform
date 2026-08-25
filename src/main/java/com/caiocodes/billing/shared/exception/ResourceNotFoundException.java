package com.caiocodes.billing.shared.exception;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends DomainException {

    public ResourceNotFoundException(String recurso, Object id) {
        super(HttpStatus.NOT_FOUND, "RECURSO_NAO_ENCONTRADO",
                "%s não encontrado(a): %s".formatted(recurso, id));
    }
}

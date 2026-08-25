package com.caiocodes.billing.shared.exception;

import org.springframework.http.HttpStatus;

/** 409: colide com o estado atual (e-mail duplicado, assinatura ativa já existente). */
public class ConflictException extends DomainException {

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}

package com.caiocodes.billing.shared.exception;

import org.springframework.http.HttpStatus;

/** 401: quem está chamando não provou quem é. */
public class UnauthorizedException extends DomainException {

    public UnauthorizedException(String code, String message) {
        super(HttpStatus.UNAUTHORIZED, code, message);
    }
}

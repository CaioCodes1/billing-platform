package com.caiocodes.billing.shared.exception;

import org.springframework.http.HttpStatus;

/** 422: a requisição está bem formada, mas viola uma regra de negócio. */
public class BusinessRuleException extends DomainException {

    public BusinessRuleException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}

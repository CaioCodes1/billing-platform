package com.caiocodes.billing.shared.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Valida CPF (11 dígitos) ou CNPJ (14 dígitos), incluindo o dígito verificador.
 *
 * <p>O {@code CHECK} da migration garante apenas o formato de armazenamento —
 * o banco não deve calcular dígito verificador. A regra fica na borda.
 */
@Documented
@Constraint(validatedBy = DocumentValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Document {

    String message() default "documento inválido: informe um CPF ou CNPJ válido";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

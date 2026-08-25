package com.caiocodes.billing.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Implementa {@link Document}.
 *
 * <p>Trabalha sobre o valor já normalizado (só dígitos) — a normalização
 * acontece no DTO, para que "123.456.789-09" e "12345678909" cheguem iguais
 * aqui e ao banco.
 */
public class DocumentValidator implements ConstraintValidator<Document, String> {

    @Override
    public boolean isValid(String valor, ConstraintValidatorContext context) {
        // Obrigatoriedade é responsabilidade do @NotBlank, não desta anotação.
        if (valor == null || valor.isBlank()) {
            return true;
        }
        String digitos = valor.replaceAll("\\D", "");
        return switch (digitos.length()) {
            case 11 -> cpfValido(digitos);
            case 14 -> cnpjValido(digitos);
            default -> false;
        };
    }

    private boolean cpfValido(String cpf) {
        // 000.000.000-00, 111.111.111-11 etc. passam no cálculo dos dígitos,
        // mas não são CPFs reais.
        if (todosIguais(cpf)) {
            return false;
        }
        int primeiro = digitoVerificador(cpf, 9, 10);
        int segundo = digitoVerificador(cpf, 10, 11);
        return primeiro == charParaInt(cpf, 9) && segundo == charParaInt(cpf, 10);
    }

    /** Peso decrescente a partir de {@code pesoInicial}, resto 11. */
    private int digitoVerificador(String cpf, int tamanho, int pesoInicial) {
        int soma = 0;
        for (int i = 0; i < tamanho; i++) {
            soma += charParaInt(cpf, i) * (pesoInicial - i);
        }
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }

    private boolean cnpjValido(String cnpj) {
        if (todosIguais(cnpj)) {
            return false;
        }
        int[] pesosPrimeiro = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] pesosSegundo = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

        int primeiro = digitoCnpj(cnpj, pesosPrimeiro);
        int segundo = digitoCnpj(cnpj, pesosSegundo);
        return primeiro == charParaInt(cnpj, 12) && segundo == charParaInt(cnpj, 13);
    }

    private int digitoCnpj(String cnpj, int[] pesos) {
        int soma = 0;
        for (int i = 0; i < pesos.length; i++) {
            soma += charParaInt(cnpj, i) * pesos[i];
        }
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }

    private boolean todosIguais(String valor) {
        return valor.chars().distinct().count() == 1;
    }

    private int charParaInt(String valor, int indice) {
        return Character.getNumericValue(valor.charAt(indice));
    }
}

package com.caiocodes.billing.shared.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DocumentValidatorTest {

    private final DocumentValidator validator = new DocumentValidator();

    @ParameterizedTest
    @ValueSource(strings = {
            "52998224725",        // CPF válido
            "529.982.247-25",     // o mesmo, com pontuação
            "11222333000181",     // CNPJ válido
            "11.222.333/0001-81"  // o mesmo, com pontuação
    })
    @DisplayName("Aceita CPF e CNPJ válidos, com ou sem pontuação")
    void aceitaValidos(String documento) {
        assertThat(validator.isValid(documento, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "52998224726",   // CPF com dígito verificador errado
            "11222333000182" // CNPJ com dígito verificador errado
    })
    @DisplayName("Recusa documento com dígito verificador errado")
    void recusaDigitoErrado(String documento) {
        assertThat(validator.isValid(documento, null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"00000000000", "11111111111", "99999999999999"})
    @DisplayName("Recusa sequências repetidas, que passam no cálculo mas não existem")
    void recusaSequenciasRepetidas(String documento) {
        assertThat(validator.isValid(documento, null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "529982247251", "abcdefghijk"})
    @DisplayName("Recusa tamanho que não é de CPF nem de CNPJ")
    void recusaTamanhoInvalido(String documento) {
        assertThat(validator.isValid(documento, null)).isFalse();
    }

    @Test
    @DisplayName("Valor ausente é assunto do @NotBlank, não desta anotação")
    void ignoraAusente() {
        assertThat(validator.isValid(null, null)).isTrue();
        assertThat(validator.isValid("   ", null)).isTrue();
    }
}

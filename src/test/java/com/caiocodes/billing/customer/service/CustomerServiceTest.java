package com.caiocodes.billing.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.caiocodes.billing.customer.dto.CreateCustomerRequest;
import com.caiocodes.billing.customer.dto.CustomerResponse;
import com.caiocodes.billing.customer.dto.UpdateCustomerRequest;
import com.caiocodes.billing.customer.entity.Customer;
import com.caiocodes.billing.customer.entity.CustomerStatus;
import com.caiocodes.billing.customer.mapper.CustomerMapperImpl;
import com.caiocodes.billing.customer.repository.CustomerRepository;
import com.caiocodes.billing.shared.exception.ConflictException;
import com.caiocodes.billing.shared.exception.ResourceNotFoundException;

/**
 * Testa a regra de negócio isolada do banco.
 *
 * <p>O mapper NÃO é mockado — usamos a implementação que o MapStruct gera. Um
 * mapper mockado devolveria o que mandássemos e o teste passaria mesmo com o
 * mapeamento quebrado.
 */
@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    private static final String CPF = "52998224725";

    @Mock
    private CustomerRepository repository;

    private CustomerService service;

    @BeforeEach
    void setUp() {
        service = new CustomerService(repository, new CustomerMapperImpl());
    }

    @Test
    @DisplayName("Cadastra cliente novo já como ACTIVE")
    void cadastraClienteNovo() {
        when(repository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(repository.existsByDocument(anyString())).thenReturn(false);
        when(repository.saveAndFlush(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

        CustomerResponse resposta = service.create(new CreateCustomerRequest(
                "Padaria do Bairro", "financeiro@padaria.com.br", CPF, "11987654321"));

        assertThat(resposta.name()).isEqualTo("Padaria do Bairro");
        assertThat(resposta.document()).isEqualTo(CPF);
        assertThat(resposta.status()).isEqualTo(CustomerStatus.ACTIVE);
    }

    @Test
    @DisplayName("Recusa e-mail já cadastrado sem chegar a gravar")
    void recusaEmailDuplicado() {
        when(repository.existsByEmailIgnoreCase(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateCustomerRequest(
                "Outro", "financeiro@padaria.com.br", CPF, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("financeiro@padaria.com.br");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Recusa documento já cadastrado sem chegar a gravar")
    void recusaDocumentoDuplicado() {
        when(repository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(repository.existsByDocument(CPF)).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateCustomerRequest(
                "Outro", "outro@padaria.com.br", CPF, null)))
                .isInstanceOf(ConflictException.class);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Buscar id inexistente devolve erro de recurso não encontrado")
    void buscaInexistente() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Cliente");
    }

    @Test
    @DisplayName("Manter o próprio e-mail na atualização não acusa conflito")
    void atualizaMantendoProprioEmail() {
        Customer existente = new Customer("Padaria", "financeiro@padaria.com.br", CPF, null);
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(existente));

        CustomerResponse resposta = service.update(id, new UpdateCustomerRequest(
                "Padaria do Bairro Ltda", "FINANCEIRO@padaria.com.br", "11999998888"));

        assertThat(resposta.name()).isEqualTo("Padaria do Bairro Ltda");
        // Não pode ter perguntado ao banco se o e-mail existe: é o dele mesmo.
        verify(repository, never()).existsByEmailIgnoreCase(anyString());
    }

    @Test
    @DisplayName("Atualização recusa e-mail que pertence a outro cliente")
    void atualizaComEmailDeOutro() {
        Customer existente = new Customer("Padaria", "financeiro@padaria.com.br", CPF, null);
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(existente));
        when(repository.existsByEmailIgnoreCase("ocupado@outra.com.br")).thenReturn(true);

        assertThatThrownBy(() -> service.update(id, new UpdateCustomerRequest(
                "Padaria", "ocupado@outra.com.br", null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("Desativar é idempotente")
    void desativaDuasVezes() {
        Customer existente = new Customer("Padaria", "financeiro@padaria.com.br", CPF, null);
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(existente));

        service.deactivate(id);
        assertThat(existente.getStatus()).isEqualTo(CustomerStatus.INACTIVE);

        service.deactivate(id);
        assertThat(existente.getStatus()).isEqualTo(CustomerStatus.INACTIVE);
    }
}

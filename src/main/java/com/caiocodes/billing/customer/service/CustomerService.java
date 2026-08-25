package com.caiocodes.billing.customer.service;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.caiocodes.billing.customer.dto.CreateCustomerRequest;
import com.caiocodes.billing.customer.dto.CustomerFilter;
import com.caiocodes.billing.customer.dto.CustomerResponse;
import com.caiocodes.billing.customer.dto.UpdateCustomerRequest;
import com.caiocodes.billing.customer.entity.Customer;
import com.caiocodes.billing.customer.mapper.CustomerMapper;
import com.caiocodes.billing.customer.repository.CustomerRepository;
import com.caiocodes.billing.customer.repository.CustomerSpecifications;
import com.caiocodes.billing.shared.api.PageResponse;
import com.caiocodes.billing.shared.exception.ConflictException;
import com.caiocodes.billing.shared.exception.ResourceNotFoundException;

@Service
@PreAuthorize("isAuthenticated()")
public class CustomerService {

    private final CustomerRepository repository;
    private final CustomerMapper mapper;

    public CustomerService(CustomerRepository repository, CustomerMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT')")
    public CustomerResponse create(CreateCustomerRequest request) {
        // Estas duas verificações existem pela MENSAGEM, não pela garantia.
        // Sob concorrência elas não seguram nada: duas requisições simultâneas
        // passam as duas antes de qualquer uma gravar. Quem garante é o índice
        // único do banco — e a violação vira 409 no GlobalExceptionHandler.
        // Aqui só transformamos o caso comum num erro que diz qual campo colidiu.
        if (repository.existsByEmailIgnoreCase(request.email())) {
            throw new ConflictException("EMAIL_JA_CADASTRADO",
                    "Já existe um cliente com o e-mail %s.".formatted(request.email()));
        }
        if (repository.existsByDocument(request.document())) {
            throw new ConflictException("DOCUMENTO_JA_CADASTRADO",
                    "Já existe um cliente com o documento informado.");
        }

        Customer cliente = new Customer(
                request.name(), request.email(), request.document(), request.phone());

        // saveAndFlush, e não save: created_at/updated_at são preenchidos por
        // trigger no banco. Com save() o INSERT ficaria pendente até o commit,
        // e o @Generated releria os carimbos depois de a resposta já ter sido
        // montada — o cliente receberia createdAt nulo.
        return mapper.toResponse(repository.saveAndFlush(cliente));
    }

    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT')")
    public CustomerResponse update(UUID id, UpdateCustomerRequest request) {
        Customer cliente = buscar(id);

        if (!cliente.getEmail().equalsIgnoreCase(request.email())
                && repository.existsByEmailIgnoreCase(request.email())) {
            throw new ConflictException("EMAIL_JA_CADASTRADO",
                    "Já existe um cliente com o e-mail %s.".formatted(request.email()));
        }

        cliente.setName(request.name());
        cliente.setEmail(request.email());
        cliente.setPhone(request.phone());

        // Sem save(): a entidade está gerenciada, o UPDATE sai sozinho. O flush
        // é explícito só para que o trigger rode e o updated_at devolvido seja
        // o gravado, não o que estava em memória.
        repository.flush();
        return mapper.toResponse(cliente);
    }

    @Transactional(readOnly = true)
    public CustomerResponse findById(UUID id) {
        return mapper.toResponse(buscar(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerResponse> list(CustomerFilter filtro, Pageable pageable) {
        Page<CustomerResponse> pagina = repository
                .findAll(CustomerSpecifications.comFiltro(filtro), pageable)
                .map(mapper::toResponse);

        return PageResponse.de(pagina);
    }

    /**
     * Desativação — nunca {@code DELETE} físico. Um cliente é referenciado por
     * assinaturas, cobranças e pagamentos; apagá-lo destruiria histórico
     * financeiro e quebraria as chaves estrangeiras.
     */
    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT')")
    public void deactivate(UUID id) {
        Customer cliente = buscar(id);
        if (!cliente.isActive()) {
            // Idempotente: desativar quem já está inativo não é erro.
            return;
        }
        cliente.deactivate();
    }

    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT')")
    public CustomerResponse activate(UUID id) {
        Customer cliente = buscar(id);
        cliente.activate();
        repository.flush();
        return mapper.toResponse(cliente);
    }

    private Customer buscar(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Cliente", id));
    }
}

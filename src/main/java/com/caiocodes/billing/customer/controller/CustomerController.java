package com.caiocodes.billing.customer.controller;

import java.net.URI;
import java.util.UUID;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.caiocodes.billing.customer.dto.CreateCustomerRequest;
import com.caiocodes.billing.customer.dto.CustomerFilter;
import com.caiocodes.billing.customer.dto.CustomerResponse;
import com.caiocodes.billing.customer.dto.UpdateCustomerRequest;
import com.caiocodes.billing.customer.service.CustomerService;
import com.caiocodes.billing.shared.api.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Clientes", description = "Cadastro e consulta de clientes")
public class CustomerController {

    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Cadastra um cliente")
    @ApiResponse(responseCode = "201", description = "Criado")
    @ApiResponse(responseCode = "409", description = "E-mail ou documento já cadastrado")
    @ApiResponse(responseCode = "422", description = "Dados inválidos")
    public ResponseEntity<CustomerResponse> create(
            @Valid @RequestBody CreateCustomerRequest request,
            UriComponentsBuilder uriBuilder) {

        CustomerResponse criado = service.create(request);

        URI local = uriBuilder.path("/api/v1/customers/{id}")
                .buildAndExpand(criado.id()).toUri();

        return ResponseEntity.created(local).body(criado);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Busca um cliente pelo id")
    @ApiResponse(responseCode = "404", description = "Cliente não encontrado")
    public CustomerResponse findById(@PathVariable UUID id) {
        return service.findById(id);
    }

    @GetMapping
    @Operation(summary = "Lista clientes com filtros, paginação e ordenação")
    public PageResponse<CustomerResponse> list(
            @ParameterObject CustomerFilter filtro,
            @ParameterObject
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        return service.list(filtro, pageable);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Atualiza os dados editáveis de um cliente",
            description = "Documento e status não são editáveis por aqui.")
    @ApiResponse(responseCode = "404", description = "Cliente não encontrado")
    @ApiResponse(responseCode = "409", description = "E-mail já usado por outro cliente")
    public CustomerResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCustomerRequest request) {

        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Desativa um cliente",
            description = "Desativação lógica: o cliente continua existindo para "
                    + "preservar o histórico de assinaturas, cobranças e pagamentos. "
                    + "Repetir a chamada não é erro.")
    @ApiResponse(responseCode = "204", description = "Desativado")
    @ApiResponse(responseCode = "404", description = "Cliente não encontrado")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        service.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Reativa um cliente desativado")
    @ApiResponse(responseCode = "404", description = "Cliente não encontrado")
    public CustomerResponse activate(@PathVariable UUID id) {
        return service.activate(id);
    }
}

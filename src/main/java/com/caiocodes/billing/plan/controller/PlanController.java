package com.caiocodes.billing.plan.controller;

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

import com.caiocodes.billing.plan.dto.CreatePlanRequest;
import com.caiocodes.billing.plan.dto.PlanFilter;
import com.caiocodes.billing.plan.dto.PlanResponse;
import com.caiocodes.billing.plan.dto.UpdatePlanRequest;
import com.caiocodes.billing.plan.service.PlanService;
import com.caiocodes.billing.shared.api.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/plans")
@Tag(name = "Planos", description = "Catálogo de planos de assinatura")
public class PlanController {

    private final PlanService service;

    public PlanController(PlanService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Cria um plano")
    @ApiResponse(responseCode = "201", description = "Criado")
    @ApiResponse(responseCode = "409", description = "Já existe plano com esse nome")
    public ResponseEntity<PlanResponse> create(
            @Valid @RequestBody CreatePlanRequest request,
            UriComponentsBuilder uriBuilder) {

        PlanResponse criado = service.create(request);

        URI local = uriBuilder.path("/api/v1/plans/{id}")
                .buildAndExpand(criado.id()).toUri();

        return ResponseEntity.created(local).body(criado);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Busca um plano pelo id")
    @ApiResponse(responseCode = "404", description = "Plano não encontrado")
    public PlanResponse findById(@PathVariable UUID id) {
        return service.findById(id);
    }

    @GetMapping
    @Operation(summary = "Lista planos com filtros, paginação e ordenação")
    public PageResponse<PlanResponse> list(
            @ParameterObject PlanFilter filtro,
            @ParameterObject
            @PageableDefault(size = 20, sort = "monthlyPrice", direction = Sort.Direction.ASC)
            Pageable pageable) {

        return service.list(filtro, pageable);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Atualiza um plano",
            description = "Reajustar o preço aqui vale apenas para contratações "
                    + "futuras — assinaturas existentes têm o valor congelado.")
    @ApiResponse(responseCode = "404", description = "Plano não encontrado")
    @ApiResponse(responseCode = "409", description = "Nome já usado por outro plano")
    public PlanResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePlanRequest request) {

        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Tira o plano do catálogo",
            description = "Impede novas contratações. Assinaturas existentes "
                    + "continuam ativas e sendo faturadas normalmente.")
    @ApiResponse(responseCode = "204", description = "Desativado")
    @ApiResponse(responseCode = "404", description = "Plano não encontrado")
    @ApiResponse(responseCode = "422", description = "Plano já estava inativo")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        service.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Devolve o plano ao catálogo")
    @ApiResponse(responseCode = "404", description = "Plano não encontrado")
    public PlanResponse activate(@PathVariable UUID id) {
        return service.activate(id);
    }
}

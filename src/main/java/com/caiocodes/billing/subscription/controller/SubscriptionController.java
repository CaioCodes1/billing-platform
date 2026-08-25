package com.caiocodes.billing.subscription.controller;

import java.net.URI;
import java.util.UUID;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.caiocodes.billing.invoice.dto.InvoiceResponse;
import com.caiocodes.billing.invoice.service.InvoiceService;
import com.caiocodes.billing.shared.api.PageResponse;
import com.caiocodes.billing.subscription.dto.ChangePlanRequest;
import com.caiocodes.billing.subscription.dto.CreateSubscriptionRequest;
import com.caiocodes.billing.subscription.dto.SubscriptionFilter;
import com.caiocodes.billing.subscription.dto.SubscriptionResponse;
import com.caiocodes.billing.subscription.service.SubscriptionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Repare que não há {@code PUT}: uma assinatura não é um formulário que se
 * edita. Cancelar, suspender e trocar de plano são <em>ações</em>, com
 * pré-condições e efeitos distintos, e cada uma tem seu próprio endpoint.
 * Um {@code PUT /subscriptions/{id}} com o corpo inteiro esconderia isso e
 * abriria caminho para "editar" o status na mão.
 */
@RestController
@RequestMapping("/api/v1/subscriptions")
@Tag(name = "Assinaturas", description = "Contratos entre clientes e planos")
public class SubscriptionController {

    private final SubscriptionService service;
    private final InvoiceService invoiceService;

    public SubscriptionController(SubscriptionService service, InvoiceService invoiceService) {
        this.service = service;
        this.invoiceService = invoiceService;
    }

    @PostMapping("/{id}/invoices")
    @Operation(summary = "Emite a cobrança da competência corrente",
            description = "Operação de apoio — no fluxo normal quem emite é o job "
                    + "diário. É idempotente: repetir devolve a cobrança já emitida, "
                    + "nunca uma segunda.")
    @ApiResponse(responseCode = "404", description = "Assinatura não encontrada")
    @ApiResponse(responseCode = "422", description = "Assinatura não gera cobrança agora")
    public InvoiceResponse issueInvoice(@PathVariable UUID id) {
        return invoiceService.issueForSubscription(id);
    }

    @PostMapping
    @Operation(summary = "Contrata uma assinatura",
            description = "Copia o preço atual do plano para o contrato. "
                    + "Reajustes futuros do plano não afetam esta assinatura.")
    @ApiResponse(responseCode = "201", description = "Contratada")
    @ApiResponse(responseCode = "404", description = "Cliente ou plano não encontrado")
    @ApiResponse(responseCode = "409", description = "Cliente já possui assinatura em vigor")
    @ApiResponse(responseCode = "422", description = "Cliente inativo, plano fora do catálogo "
            + "ou data de início retroativa")
    public ResponseEntity<SubscriptionResponse> create(
            @Valid @RequestBody CreateSubscriptionRequest request,
            UriComponentsBuilder uriBuilder) {

        SubscriptionResponse criada = service.create(request);

        URI local = uriBuilder.path("/api/v1/subscriptions/{id}")
                .buildAndExpand(criada.id()).toUri();

        return ResponseEntity.created(local).body(criada);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Busca uma assinatura pelo id")
    @ApiResponse(responseCode = "404", description = "Assinatura não encontrada")
    public SubscriptionResponse findById(@PathVariable UUID id) {
        return service.findById(id);
    }

    @GetMapping
    @Operation(summary = "Lista assinaturas com filtros, paginação e ordenação")
    public PageResponse<SubscriptionResponse> list(
            @ParameterObject SubscriptionFilter filtro,
            @ParameterObject
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        return service.list(filtro, pageable);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancela a assinatura",
            description = "Estado terminal. É o único que libera o cliente para "
                    + "contratar de novo.")
    @ApiResponse(responseCode = "422", description = "Assinatura já cancelada")
    public SubscriptionResponse cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspende a assinatura",
            description = "Normalmente feito pela régua de inadimplência. "
                    + "Suspensa não gera novas cobranças.")
    @ApiResponse(responseCode = "422", description = "Transição inválida a partir do estado atual")
    public SubscriptionResponse suspend(@PathVariable UUID id) {
        return service.suspend(id);
    }

    @PostMapping("/{id}/reactivate")
    @Operation(summary = "Reativa uma assinatura suspensa ou pendente")
    @ApiResponse(responseCode = "422", description = "Transição inválida a partir do estado atual")
    public SubscriptionResponse reactivate(@PathVariable UUID id) {
        return service.reactivate(id);
    }

    @PostMapping("/{id}/migrate-price")
    @Operation(summary = "Aplica o preço atual do plano a esta assinatura",
            description = "Contrapartida explícita do congelamento de preço: "
                    + "reprecificar é uma decisão comercial, registrada e auditável.")
    public SubscriptionResponse migratePrice(@PathVariable UUID id) {
        return service.migrateToCurrentPrice(id);
    }

    @PostMapping("/{id}/change-plan")
    @Operation(summary = "Troca o plano da assinatura",
            description = "Adota o preço do plano novo a partir de agora.")
    @ApiResponse(responseCode = "404", description = "Plano não encontrado")
    @ApiResponse(responseCode = "422", description = "Plano fora do catálogo ou assinatura cancelada")
    public SubscriptionResponse changePlan(
            @PathVariable UUID id,
            @Valid @RequestBody ChangePlanRequest request) {

        return service.changePlan(id, request);
    }
}

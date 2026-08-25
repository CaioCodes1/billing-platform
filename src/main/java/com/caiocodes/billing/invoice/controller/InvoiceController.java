package com.caiocodes.billing.invoice.controller;

import java.util.UUID;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.caiocodes.billing.invoice.dto.InvoiceFilter;
import com.caiocodes.billing.invoice.dto.InvoiceResponse;
import com.caiocodes.billing.invoice.service.InvoiceService;
import com.caiocodes.billing.shared.api.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Não existe {@code POST /invoices}: cobrança não é criada à mão, ela é
 * <em>emitida</em> a partir de uma assinatura e de uma competência. Deixar um
 * endpoint que aceita valor e vencimento arbitrários abriria a porta para
 * cobranças sem lastro em contrato.
 */
@RestController
@RequestMapping("/api/v1/invoices")
@Tag(name = "Cobranças", description = "Faturas emitidas por competência")
public class InvoiceController {

    private final InvoiceService service;

    public InvoiceController(InvoiceService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Busca uma cobrança pelo id")
    @ApiResponse(responseCode = "404", description = "Cobrança não encontrada")
    public InvoiceResponse findById(@PathVariable UUID id) {
        return service.findById(id);
    }

    @GetMapping
    @Operation(summary = "Lista cobranças com filtros, paginação e ordenação",
            description = "Aceita filtro por assinatura, por cliente, por status, "
                    + "por faixa de vencimento e o atalho openOnly.")
    public PageResponse<InvoiceResponse> list(
            @ParameterObject InvoiceFilter filtro,
            @ParameterObject
            @PageableDefault(size = 20, sort = "dueDate", direction = Sort.Direction.DESC)
            Pageable pageable) {

        return service.list(filtro, pageable);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Anula uma cobrança",
            description = "Só antes do pagamento. Depois de paga, o caminho é o estorno.")
    @ApiResponse(responseCode = "422", description = "Cobrança já paga, cancelada ou estornada")
    public InvoiceResponse cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }
}

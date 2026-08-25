package com.caiocodes.billing.payment.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.caiocodes.billing.payment.dto.PaymentResponse;
import com.caiocodes.billing.payment.dto.RegisterPaymentRequest;
import com.caiocodes.billing.payment.service.PaymentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Pagamentos são sempre relativos a uma cobrança — por isso a rota é aninhada
 * em {@code /invoices/{id}}. Não existe pagamento avulso: dinheiro que entra
 * sem fatura correspondente é um problema contábil, não um recurso da API.
 *
 * <p>Também não existe {@code PUT} nem {@code DELETE}: o razão é imutável.
 */
@RestController
@RequestMapping("/api/v1/invoices/{invoiceId}")
@Tag(name = "Pagamentos", description = "Livro-razão de uma cobrança")
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @PostMapping("/payments")
    @Operation(summary = "Registra um pagamento",
            description = "Recalcula o status da cobrança a partir da soma do razão. "
                    + "Idempotente por idempotencyKey e por providerRef.")
    @ApiResponse(responseCode = "201", description = "Registrado")
    @ApiResponse(responseCode = "404", description = "Cobrança não encontrada")
    @ApiResponse(responseCode = "422", description = "Cobrança cancelada ou já estornada")
    public ResponseEntity<PaymentResponse> register(
            @PathVariable UUID invoiceId,
            @Valid @RequestBody RegisterPaymentRequest request) {

        return ResponseEntity.status(201).body(service.register(invoiceId, request));
    }

    @PostMapping("/refunds")
    @Operation(summary = "Estorna, total ou parcialmente",
            description = "Não altera o pagamento original: lança o contrário. "
                    + "O histórico continua explicando o saldo.")
    @ApiResponse(responseCode = "201", description = "Estorno registrado")
    @ApiResponse(responseCode = "422", description = "Estorno maior que o valor pago")
    public ResponseEntity<PaymentResponse> refund(
            @PathVariable UUID invoiceId,
            @Valid @RequestBody RegisterPaymentRequest request) {

        return ResponseEntity.status(201).body(service.refund(invoiceId, request));
    }

    @GetMapping("/payments")
    @Operation(summary = "Extrato de lançamentos da cobrança")
    @ApiResponse(responseCode = "404", description = "Cobrança não encontrada")
    public List<PaymentResponse> list(@PathVariable UUID invoiceId) {
        return service.listByInvoice(invoiceId);
    }

    @GetMapping("/balance")
    @Operation(summary = "Saldo da cobrança",
            description = "Soma dos pagamentos menos os estornos.")
    public Map<String, BigDecimal> balance(@PathVariable UUID invoiceId) {
        return Map.of("balance", service.saldoDe(invoiceId));
    }
}

package com.caiocodes.billing.invoice.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.caiocodes.billing.invoice.dto.InvoiceResponse;
import com.caiocodes.billing.invoice.entity.Invoice;
import com.caiocodes.billing.subscription.entity.Subscription;

/**
 * Aqui os nomes não batem sozinhos: {@code customerName} está em
 * {@code subscription.customer.name}. Em vez de tentar encaixar isso numa única
 * anotação com caminho aninhado, declara-se um método para o resumo — o
 * MapStruct o adota automaticamente ao mapear a propriedade {@code subscription},
 * porque os tipos correspondem.
 */
@Mapper
public interface InvoiceMapper {

    InvoiceResponse toResponse(Invoice invoice);

    @Mapping(target = "customerId", source = "customer.id")
    @Mapping(target = "customerName", source = "customer.name")
    @Mapping(target = "planName", source = "plan.name")
    InvoiceResponse.SubscriptionSummary toSummary(Subscription subscription);
}

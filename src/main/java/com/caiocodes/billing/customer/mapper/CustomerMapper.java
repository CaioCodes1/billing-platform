package com.caiocodes.billing.customer.mapper;

import org.mapstruct.Mapper;

import com.caiocodes.billing.customer.dto.CustomerResponse;
import com.caiocodes.billing.customer.entity.Customer;

/**
 * Só mapeia entidade → DTO de saída.
 *
 * <p>O caminho inverso (DTO → entidade) é feito no service, pelo construtor de
 * {@link Customer}. Deixar o MapStruct montar a entidade exigiria expor
 * {@code setter} para {@code document} e para {@code status}, furando as
 * invariantes que a entidade protege — documento imutável e status que só muda
 * por método de negócio.
 */
@Mapper
public interface CustomerMapper {

    CustomerResponse toResponse(Customer customer);
}

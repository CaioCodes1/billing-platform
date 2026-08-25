package com.caiocodes.billing.payment.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.caiocodes.billing.payment.dto.PaymentResponse;
import com.caiocodes.billing.payment.entity.Payment;

@Mapper
public interface PaymentMapper {

    @Mapping(target = "invoiceId", source = "invoice.id")
    PaymentResponse toResponse(Payment payment);
}

package com.caiocodes.billing.subscription.mapper;

import org.mapstruct.Mapper;

import com.caiocodes.billing.subscription.dto.SubscriptionResponse;
import com.caiocodes.billing.subscription.entity.Subscription;

/**
 * O MapStruct resolve os resumos aninhados sozinho: os componentes de
 * {@code CustomerSummary} e {@code PlanSummary} têm os mesmos nomes dos campos
 * de {@code Customer} e {@code Plan}, então ele gera os dois sub-mapeamentos.
 *
 * <p>Com {@code unmappedTargetPolicy=ERROR} ligado no compilador, isso também
 * significa que acrescentar um campo ao resumo sem equivalente na entidade
 * quebra o build — e não vira {@code null} silencioso em produção.
 */
@Mapper
public interface SubscriptionMapper {

    SubscriptionResponse toResponse(Subscription subscription);
}

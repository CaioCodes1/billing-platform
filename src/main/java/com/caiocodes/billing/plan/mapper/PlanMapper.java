package com.caiocodes.billing.plan.mapper;

import org.mapstruct.Mapper;

import com.caiocodes.billing.plan.dto.PlanResponse;
import com.caiocodes.billing.plan.entity.Plan;

@Mapper
public interface PlanMapper {

    PlanResponse toResponse(Plan plan);
}

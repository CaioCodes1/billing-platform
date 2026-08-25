package com.caiocodes.billing.plan.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.caiocodes.billing.plan.entity.Plan;

public interface PlanRepository
        extends JpaRepository<Plan, UUID>, JpaSpecificationExecutor<Plan> {

    boolean existsByNameIgnoreCase(String name);
}

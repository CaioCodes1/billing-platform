package com.caiocodes.billing.customer.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.caiocodes.billing.customer.entity.Customer;

public interface CustomerRepository
        extends JpaRepository<Customer, UUID>, JpaSpecificationExecutor<Customer> {

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByDocument(String document);
}

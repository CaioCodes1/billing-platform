package com.caiocodes.billing.security.repository;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;

import com.caiocodes.billing.security.entity.Role;

public interface RoleRepository extends JpaRepository<Role, Short> {

    Set<Role> findByNameIn(Collection<String> names);

    Optional<Role> findByName(String name);
}

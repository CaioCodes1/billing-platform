package com.caiocodes.billing.security.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Papel do usuário interno. Conjunto fechado, semeado pela migration V4 com
 * ids fixos — não é cadastro, é vocabulário do sistema.
 */
@Entity
@Table(name = "roles")
@Getter
public class Role {

    public static final short ADMIN = 1;
    public static final short FINANCIAL = 2;
    public static final short SUPPORT = 3;

    @Id
    private Short id;

    @Column(nullable = false, length = 20)
    private String name;

    protected Role() {
        // exigido pelo JPA
    }

    /** Nome no formato que o Spring Security espera em authorities. */
    public String authority() {
        return "ROLE_" + name;
    }
}

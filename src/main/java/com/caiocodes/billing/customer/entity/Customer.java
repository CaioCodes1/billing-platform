package com.caiocodes.billing.customer.entity;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "customers")
@Getter
@Setter
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 255)
    private String email;

    /**
     * CPF ou CNPJ, só dígitos. Sem {@code setter} de negócio: o documento
     * identifica o pagador na fatura e trocá-lo depois da emissão significaria
     * reescrever histórico fiscal. Alteração é caso de suporte, não de PUT.
     */
    @Setter(lombok.AccessLevel.NONE)
    @Column(nullable = false, length = 14, updatable = false)
    private String document;

    @Column(length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CustomerStatus status = CustomerStatus.ACTIVE;

    // ------------------------------------------------------------------
    // Carimbos de tempo: o dono é o banco.
    // ------------------------------------------------------------------
    // A migration instala trigger que preenche created_at e atualiza
    // updated_at em todo UPDATE — inclusive nos que não passam pelo JPA
    // (script de correção, migration, job em SQL puro). Mapear como
    // insertable/updatable = false evita que o Hibernate discorde do banco;
    // @Generated faz ele reler o valor gravado depois da escrita.

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected Customer() {
        // exigido pelo JPA
    }

    public Customer(String name, String email, String document, String phone) {
        this.name = name;
        this.email = email;
        this.document = document;
        this.phone = phone;
        this.status = CustomerStatus.ACTIVE;
    }

    public void deactivate() {
        this.status = CustomerStatus.INACTIVE;
    }

    public void activate() {
        this.status = CustomerStatus.ACTIVE;
    }

    public boolean isActive() {
        return status == CustomerStatus.ACTIVE;
    }

    // ------------------------------------------------------------------
    // equals/hashCode manuais, e não @Data do Lombok.
    // ------------------------------------------------------------------
    // @Data gera equals sobre todos os campos, o que em entidade JPA quebra
    // de duas formas: toca associações lazy (disparando query ou exceção) e
    // muda o hashCode quando um campo muda, corrompendo entidades já dentro
    // de um HashSet. Aqui a identidade é só o id.

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Customer outro)) {
            return false;
        }
        return id != null && Objects.equals(id, outro.id);
    }

    @Override
    public int hashCode() {
        // Constante de propósito: o hash não pode mudar quando o id sai de
        // null para o valor gerado no flush.
        return Customer.class.hashCode();
    }
}

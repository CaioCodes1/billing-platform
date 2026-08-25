package com.caiocodes.billing.plan.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Plano de venda.
 *
 * <p>Atenção ao papel desta entidade no faturamento: o preço daqui é usado
 * <strong>apenas no momento da contratação</strong>. A assinatura guarda a sua
 * própria cópia em {@code subscriptions.unit_price}. Reajustar um plano vale
 * para contratos novos e não repreça quem já assinou.
 */
@Entity
@Table(name = "plans")
@Getter
@Setter
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    /**
     * {@code BigDecimal}, nunca {@code double}. Em ponto flutuante binário
     * 0.1 + 0.2 não dá 0.3, e num sistema de cobrança isso vira diferença de
     * centavo no fechamento contábil.
     */
    @Column(name = "monthly_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal monthlyPrice;

    /**
     * Fixo em BRL nesta versão. A coluna existe para que suportar outra moeda
     * um dia seja uma migration de dados, e não uma alteração de schema em
     * cinco tabelas — mas não há endpoint que a altere: multimoeda exige
     * conversão, arredondamento por moeda e cotação, e isso é outro projeto.
     */
    @Setter(lombok.AccessLevel.NONE)
    @Column(nullable = false, length = 3)
    private String currency = "BRL";

    @Column(name = "user_limit", nullable = false)
    private Integer userLimit;

    @Column(nullable = false)
    private boolean active = true;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected Plan() {
        // exigido pelo JPA
    }

    public Plan(String name, String description, BigDecimal monthlyPrice, Integer userLimit) {
        this.name = name;
        this.description = description;
        this.monthlyPrice = monthlyPrice;
        this.userLimit = userLimit;
        this.currency = "BRL";
        this.active = true;
    }

    /**
     * Tira o plano do catálogo. Não afeta assinaturas existentes: elas já têm
     * o preço congelado e continuam sendo faturadas normalmente. O efeito é só
     * impedir novas contratações.
     */
    public void deactivate() {
        this.active = false;
    }

    public void activate() {
        this.active = true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Plan outro)) {
            return false;
        }
        return id != null && Objects.equals(id, outro.id);
    }

    @Override
    public int hashCode() {
        return Plan.class.hashCode();
    }
}

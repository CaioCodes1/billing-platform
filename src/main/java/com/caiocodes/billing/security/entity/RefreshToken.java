package com.caiocodes.billing.security.entity;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Refresh token, guardado como <strong>hash</strong>.
 *
 * <p>O token em claro só existe em trânsito e na mão do cliente. Um vazamento
 * do dump do banco não vira sessão válida — mesma lógica da senha.
 *
 * <p>O {@code familyId} implementa detecção de reuso: todo refresh emitido a
 * partir de um mesmo login carrega o mesmo valor. Se um token já rotacionado
 * for apresentado de novo, é sinal de cópia — e a família inteira é revogada,
 * derrubando o atacante <em>e</em> o usuário legítimo. Na dúvida, login novo.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    /** SHA-256 em hexadecimal do token entregue ao cliente. */
    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "replaced_by_id")
    private UUID replacedById;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected RefreshToken() {
        // exigido pelo JPA
    }

    public RefreshToken(User user, String tokenHash, UUID familyId, OffsetDateTime expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.expiresAt = expiresAt;
    }

    public void revoke(OffsetDateTime quando) {
        if (revokedAt == null) {
            this.revokedAt = quando;
        }
    }

    public void replaceBy(UUID novoId, OffsetDateTime quando) {
        this.replacedById = novoId;
        revoke(quando);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(OffsetDateTime agora) {
        return expiresAt.isBefore(agora);
    }

    /** Válido para uso: não revogado, não expirado e ainda não rotacionado. */
    public boolean isUsable(OffsetDateTime agora) {
        return !isRevoked() && !isExpired(agora) && replacedById == null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RefreshToken outro)) {
            return false;
        }
        return id != null && Objects.equals(id, outro.id);
    }

    @Override
    public int hashCode() {
        return RefreshToken.class.hashCode();
    }
}

package com.caiocodes.billing.security.repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.caiocodes.billing.security.entity.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revogação em massa da família — a resposta à detecção de reuso.
     *
     * <p>Feito num único {@code UPDATE}: carregar todos para revogar um a um
     * seria lento e deixaria uma janela em que o atacante ainda poderia usar
     * outro token da mesma família.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken r SET r.revokedAt = :agora
            WHERE r.familyId = :familyId AND r.revokedAt IS NULL
            """)
    int revogarFamilia(@Param("familyId") UUID familyId,
                       @Param("agora") OffsetDateTime agora);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken r SET r.revokedAt = :agora
            WHERE r.user.id = :userId AND r.revokedAt IS NULL
            """)
    int revogarTodosDoUsuario(@Param("userId") UUID userId,
                              @Param("agora") OffsetDateTime agora);
}

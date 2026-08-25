package com.caiocodes.billing.security.service;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.caiocodes.billing.security.repository.RefreshTokenRepository;

/**
 * Revogação que precisa sobreviver a uma exceção.
 *
 * <p><strong>Por que este bean existe.</strong> Ao detectar reuso de refresh
 * token, o fluxo faz duas coisas: revoga a família inteira e recusa a
 * requisição com 401. Feitas na mesma transação, a segunda desfaz a primeira —
 * a {@code UnauthorizedException} é {@code RuntimeException}, o Spring marca a
 * transação para rollback, e a revogação nunca chega ao banco.
 *
 * <p>O sintoma é traiçoeiro: o atacante recebe o 401 e parece que a defesa
 * funcionou, mas os demais tokens da família continuam válidos — exatamente o
 * que a defesa existia para impedir. Só um teste que tenta usar o outro token
 * depois revela o problema.
 *
 * <p>{@code REQUIRES_NEW} suspende a transação em curso e abre uma própria, que
 * comita antes de a exceção subir.
 */
@Component
public class RefreshTokenRevoker {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenRevoker.class);

    private final RefreshTokenRepository repository;

    public RefreshTokenRevoker(RefreshTokenRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revogarFamilia(UUID familyId, OffsetDateTime agora) {
        int revogados = repository.revogarFamilia(familyId, agora);
        log.error("REUSO DE REFRESH TOKEN: família {} revogada ({} tokens)",
                familyId, revogados);
        return revogados;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revogarTodosDoUsuario(UUID userId, OffsetDateTime agora) {
        return repository.revogarTodosDoUsuario(userId, agora);
    }
}

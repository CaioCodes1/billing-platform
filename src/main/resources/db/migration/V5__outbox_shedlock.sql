-- =====================================================================
-- V5 - Outbox de eventos e lock distribuido do scheduler
-- =====================================================================

-- ---------------------------------------------------------------------
-- outbox_messages
-- ---------------------------------------------------------------------
-- O problema: mandar e-mail dentro do @Transactional tem dois defeitos.
--   1) o SMTP lento segura a conexao do banco aberta;
--   2) se a transacao der rollback DEPOIS do envio, o cliente ja recebeu
--      um aviso de cobranca que nao existe - e nao da para "des-enviar".
--
-- A solucao: o service grava o evento nesta tabela NA MESMA TRANSACAO do
-- dado. Ou os dois vao para o banco, ou nenhum vai. Um worker separado le
-- os PENDING e despacha. O e-mail passa a poder falhar e ser retentado sem
-- arrastar a regra de negocio junto.
CREATE TABLE outbox_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id   UUID        NOT NULL,
    event_type     VARCHAR(60) NOT NULL,
    payload        JSONB       NOT NULL,

    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts        SMALLINT    NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at    TIMESTAMPTZ,

    CONSTRAINT ck_outbox_status   CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0)
);

-- O worker faz: WHERE status='PENDING' AND next_attempt_at <= now()
-- ORDER BY next_attempt_at FOR UPDATE SKIP LOCKED
-- O SKIP LOCKED permite varias replicas consumindo a fila sem colidir.
CREATE INDEX idx_outbox_pendentes ON outbox_messages (next_attempt_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_outbox_agregado ON outbox_messages (aggregate_type, aggregate_id);

COMMENT ON TABLE outbox_messages IS
    'Padrao Transactional Outbox: evento gravado na mesma transacao do dado, '
    'despachado depois por worker com retry e backoff.';


-- ---------------------------------------------------------------------
-- shedlock
-- ---------------------------------------------------------------------
-- @Scheduled dispara em TODA instancia da aplicacao. Com duas replicas,
-- o job de faturamento roda duas vezes no mesmo minuto. O UNIQUE de
-- invoices ja impede a cobranca duplicada, mas o trabalho e feito em
-- dobro e os logs ficam ilegiveis. O ShedLock elege uma so por execucao.
-- Estrutura exigida pela lib (net.javacrumbs.shedlock).
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);

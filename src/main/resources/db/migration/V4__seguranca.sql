-- =====================================================================
-- V4 - Usuarios, papeis e refresh tokens
-- =====================================================================
-- Atencao: nenhum usuario e semeado aqui. Credencial em migration vira
-- credencial versionada no git e igual em todo ambiente. O admin inicial
-- e criado no boot a partir de variavel de ambiente (ver ARQUITETURA.md).

CREATE TABLE users (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(150) NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_users_email ON users (lower(email));

CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

COMMENT ON COLUMN users.password_hash IS 'BCrypt (60 chars); folga ate 100 para trocar de algoritmo';


-- ---------------------------------------------------------------------
-- roles: conjunto fechado, id fixo de proposito
-- ---------------------------------------------------------------------
CREATE TABLE roles (
    id   SMALLINT    PRIMARY KEY,
    name VARCHAR(20) NOT NULL UNIQUE
);

INSERT INTO roles (id, name) VALUES
    (1, 'ADMIN'),
    (2, 'FINANCIAL'),
    (3, 'SUPPORT');

CREATE TABLE user_roles (
    user_id UUID     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id SMALLINT NOT NULL REFERENCES roles (id),
    PRIMARY KEY (user_id, role_id)
);


-- ---------------------------------------------------------------------
-- refresh_tokens - com rotacao e deteccao de reuso
-- ---------------------------------------------------------------------
-- Guardamos o HASH, nunca o token em claro: vazamento do dump do banco
-- nao vira sessao valida. Mesma logica de password_hash.
--
-- family_id: todo refresh emitido a partir de um login carrega o mesmo
-- family_id. Se um token ja rotacionado for apresentado de novo, e sinal
-- de que alguem copiou o token - revoga-se a FAMILIA inteira, derrubando
-- tanto o atacante quanto o usuario legitimo. E o comportamento correto:
-- na duvida, exigir login novo.
CREATE TABLE refresh_tokens (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash     VARCHAR(64) NOT NULL,
    family_id      UUID        NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    revoked_at     TIMESTAMPTZ,
    replaced_by_id UUID        REFERENCES refresh_tokens (id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_refresh_tokens_hash CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uq_refresh_tokens_hash ON refresh_tokens (token_hash);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_user   ON refresh_tokens (user_id) WHERE revoked_at IS NULL;
-- Faxina periodica dos expirados.
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens (expires_at);

COMMENT ON COLUMN refresh_tokens.token_hash IS 'SHA-256 em hex do token entregue ao cliente';

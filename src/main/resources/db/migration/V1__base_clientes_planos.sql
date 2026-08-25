-- =====================================================================
-- V1 - Fundacao: helpers, clientes e planos
-- =====================================================================
-- Convencao de enums: VARCHAR + CHECK, nao o tipo ENUM nativo do Postgres.
-- Motivo: com @Enumerated(EnumType.STRING) o JPA le/escreve VARCHAR sem
-- adaptador nenhum, e adicionar um valor novo e um ALTER de CHECK dentro
-- da transacao da migration. Com ENUM nativo seria preciso @JdbcTypeCode
-- em toda entidade e ALTER TYPE ... ADD VALUE, que tem regras proprias.
-- O CHECK entrega a mesma garantia de integridade.

-- Mantem updated_at correto mesmo para UPDATE que nao passe pelo JPA
-- (migration, script de correcao, job em SQL puro).
CREATE OR REPLACE FUNCTION fn_set_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;


-- ---------------------------------------------------------------------
-- customers
-- ---------------------------------------------------------------------
CREATE TABLE customers (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(150) NOT NULL,
    email      VARCHAR(255) NOT NULL,
    document   VARCHAR(14)  NOT NULL,
    phone      VARCHAR(20),
    status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_customers_status   CHECK (status IN ('ACTIVE', 'INACTIVE')),
    -- so digitos: 11 (CPF) ou 14 (CNPJ). A validacao do digito verificador
    -- fica no Bean Validation; aqui garantimos so o formato de armazenamento.
    CONSTRAINT ck_customers_document CHECK (document ~ '^([0-9]{11}|[0-9]{14})$')
);

-- Unicidade case-insensitive sem precisar da extensao citext.
CREATE UNIQUE INDEX uq_customers_email    ON customers (lower(email));
CREATE UNIQUE INDEX uq_customers_document ON customers (document);
CREATE INDEX        idx_customers_name    ON customers (lower(name));

CREATE TRIGGER trg_customers_updated_at BEFORE UPDATE ON customers
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

COMMENT ON COLUMN customers.document IS 'CPF (11) ou CNPJ (14), somente digitos';
COMMENT ON COLUMN customers.created_at IS 'Equivale ao dataCadastro do enunciado';


-- ---------------------------------------------------------------------
-- plans
-- ---------------------------------------------------------------------
CREATE TABLE plans (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(100)  NOT NULL,
    description   TEXT,
    monthly_price NUMERIC(19,4) NOT NULL,
    currency      VARCHAR(3)    NOT NULL DEFAULT 'BRL',
    user_limit    INTEGER       NOT NULL,
    active        BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_plans_price      CHECK (monthly_price >= 0),
    CONSTRAINT ck_plans_user_limit CHECK (user_limit > 0)
);

CREATE UNIQUE INDEX uq_plans_name   ON plans (lower(name));
CREATE INDEX        idx_plans_active ON plans (active) WHERE active;

CREATE TRIGGER trg_plans_updated_at BEFORE UPDATE ON plans
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

COMMENT ON TABLE plans IS
    'Catalogo de venda. O preco daqui e usado APENAS no momento da contratacao; '
    'a assinatura guarda sua propria copia em subscriptions.unit_price. '
    'Reajustar um plano nao repreca contratos existentes.';

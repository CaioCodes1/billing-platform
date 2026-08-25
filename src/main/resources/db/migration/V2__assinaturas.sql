-- =====================================================================
-- V2 - Assinaturas
-- =====================================================================

CREATE TABLE subscriptions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID NOT NULL REFERENCES customers (id),
    plan_id     UUID NOT NULL REFERENCES plans (id),

    -- Snapshot do preco no ato da contratacao. NAO ler plans.monthly_price
    -- na hora de faturar: reajuste de plano nao pode reprecar contrato vivo.
    unit_price  NUMERIC(19,4) NOT NULL,
    currency    VARCHAR(3)    NOT NULL DEFAULT 'BRL',

    -- Dia de cobranca ORIGINAL (1..31). Guardado separado porque
    -- current_period_start sofre clamp: quem assina 31/01 vira 28/02 e, se
    -- o proximo ciclo partisse dai, viraria cliente do dia 28 para sempre.
    -- O calculo do ciclo sempre parte deste valor.
    billing_day SMALLINT NOT NULL,

    start_date           DATE NOT NULL,
    current_period_start DATE NOT NULL,
    current_period_end   DATE NOT NULL,
    next_renewal_date    DATE NOT NULL,

    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    suspended_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_subscriptions_status CHECK (
        status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'CANCELLED')),
    CONSTRAINT ck_subscriptions_billing_day CHECK (billing_day BETWEEN 1 AND 31),
    CONSTRAINT ck_subscriptions_price       CHECK (unit_price >= 0),
    CONSTRAINT ck_subscriptions_period      CHECK (current_period_end > current_period_start),
    -- Coerencia entre status e carimbo de data, garantida pelo banco.
    CONSTRAINT ck_subscriptions_suspended CHECK (
        status <> 'SUSPENDED' OR suspended_at IS NOT NULL),
    CONSTRAINT ck_subscriptions_cancelled CHECK (
        status <> 'CANCELLED' OR cancelled_at IS NOT NULL)
);

-- =====================================================================
-- A regra "um cliente so pode ter uma assinatura ativa" mora AQUI, nao
-- num if do service. Dois POST simultaneos passam os dois pelo if antes
-- de qualquer um gravar; o indice unico parcial e o unico ponto do
-- sistema onde essa corrida e resolvida de verdade.
--
-- Repare no conjunto de status: a vaga so e liberada por CANCELLED.
-- Um inadimplente SUSPENDED nao pode abrir assinatura nova para fugir
-- da divida.
-- =====================================================================
CREATE UNIQUE INDEX uq_subscriptions_active_slot ON subscriptions (customer_id)
    WHERE status IN ('PENDING', 'ACTIVE', 'SUSPENDED');

-- Passo 1 do job diario (emitir): varre so quem esta ACTIVE.
CREATE INDEX idx_subscriptions_renewal ON subscriptions (next_renewal_date)
    WHERE status = 'ACTIVE';

-- Passo 4 do job diario (encerrar suspensos antigos).
CREATE INDEX idx_subscriptions_suspended ON subscriptions (suspended_at)
    WHERE status = 'SUSPENDED';

CREATE INDEX idx_subscriptions_customer ON subscriptions (customer_id);
CREATE INDEX idx_subscriptions_plan     ON subscriptions (plan_id);

CREATE TRIGGER trg_subscriptions_updated_at BEFORE UPDATE ON subscriptions
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

COMMENT ON COLUMN subscriptions.unit_price IS
    'Preco congelado na contratacao. Migrar para o preco novo do plano e uma acao explicita.';

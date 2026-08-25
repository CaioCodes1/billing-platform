-- =====================================================================
-- V3 - Cobrancas e pagamentos
-- =====================================================================

CREATE TABLE invoices (
    id              UUID NOT NULL PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id UUID NOT NULL REFERENCES subscriptions (id),

    -- Competencia: qual periodo de servico esta sendo cobrado.
    -- E o que torna a emissao idempotente (ver indice unico abaixo).
    period_start DATE NOT NULL,
    period_end   DATE NOT NULL,

    amount   NUMERIC(19,4) NOT NULL,
    currency VARCHAR(3)    NOT NULL DEFAULT 'BRL',
    due_date DATE          NOT NULL,

    status  VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    -- Denormalizacao consciente: a verdade e a soma de payments, mas
    -- guardar o carimbo evita agregacao em todo relatorio.
    paid_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_invoices_status CHECK (status IN (
        'PENDING', 'PARTIALLY_PAID', 'PAID', 'OVERDUE', 'CANCELLED', 'REFUNDED')),
    CONSTRAINT ck_invoices_amount  CHECK (amount > 0),
    CONSTRAINT ck_invoices_period  CHECK (period_end > period_start),
    CONSTRAINT ck_invoices_due     CHECK (due_date >= period_start),
    CONSTRAINT ck_invoices_paid_at CHECK (status <> 'PAID' OR paid_at IS NOT NULL)
);

-- =====================================================================
-- A trava de idempotencia do scheduler.
-- Job reiniciado no meio, retry, deploy durante a execucao ou duas
-- replicas subindo juntas: a segunda tentativa de emitir a mesma
-- competencia bate aqui e o INSERT falha. O codigo nao precisa acertar;
-- cobrar o cliente duas vezes passa a ser impossivel por construcao.
-- =====================================================================
CREATE UNIQUE INDEX uq_invoices_competencia ON invoices (subscription_id, period_start);

-- Passos 2 e 3 do job diario (vencer e suspender).
CREATE INDEX idx_invoices_dunning ON invoices (status, due_date)
    WHERE status IN ('PENDING', 'PARTIALLY_PAID', 'OVERDUE');

-- Extrato do cliente e dashboard.
CREATE INDEX idx_invoices_subscription ON invoices (subscription_id, due_date DESC);
CREATE INDEX idx_invoices_paid_at      ON invoices (paid_at) WHERE paid_at IS NOT NULL;

CREATE TRIGGER trg_invoices_updated_at BEFORE UPDATE ON invoices
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();


-- ---------------------------------------------------------------------
-- payments - livro-razao, append-only
-- ---------------------------------------------------------------------
-- Nao existe "corrigir um pagamento": lanca-se o contrario (REFUND).
-- Consequencias que saem de graca deste desenho:
--   - pagamento parcial = soma < amount  -> PARTIALLY_PAID
--   - pagamento a maior  = soma > amount -> credito visivel, nao perdido
--   - estorno PIX / chargeback de cartao = lancamento REFUND
--   - auditoria completa sem tabela de auditoria separada
CREATE TABLE payments (
    id         UUID NOT NULL PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id UUID NOT NULL REFERENCES invoices (id),

    type   VARCHAR(10)   NOT NULL DEFAULT 'PAYMENT',
    method VARCHAR(20)   NOT NULL,
    amount NUMERIC(19,4) NOT NULL,

    paid_at TIMESTAMPTZ NOT NULL,

    -- Id da transacao no provedor (PSP). Unico: webhook reentregue
    -- pelo provedor nao vira pagamento duplicado.
    provider_ref TEXT,
    -- Chave enviada pelo cliente da API. Unica: clique duplo no front
    -- ou retry de rede nao vira pagamento duplicado.
    idempotency_key UUID,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_payments_type   CHECK (type IN ('PAYMENT', 'REFUND')),
    CONSTRAINT ck_payments_method CHECK (method IN ('PIX', 'CREDIT_CARD', 'BOLETO')),
    -- Sempre positivo; quem da o sinal e o type. Evita a classe de bug
    -- "estorno gravado positivo" passar despercebida na soma.
    CONSTRAINT ck_payments_amount CHECK (amount > 0)
);

CREATE UNIQUE INDEX uq_payments_provider_ref ON payments (provider_ref)
    WHERE provider_ref IS NOT NULL;
CREATE UNIQUE INDEX uq_payments_idempotency ON payments (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_payments_invoice ON payments (invoice_id);
CREATE INDEX idx_payments_paid_at ON payments (paid_at);

-- Append-only imposto pelo banco, nao pela disciplina do time.
CREATE OR REPLACE FUNCTION fn_payments_append_only() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'payments e append-only: para estornar, insira um lancamento do tipo REFUND';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payments_append_only
    BEFORE UPDATE OR DELETE ON payments
    FOR EACH ROW EXECUTE FUNCTION fn_payments_append_only();

COMMENT ON TABLE payments IS
    'Livro-razao imutavel. O status de invoices e derivado da soma '
    'dos PAYMENT menos os REFUND desta tabela.';

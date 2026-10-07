-- Refunds (FR-PAY: full or partial, total refunded <= captured, idempotent, with a reason and a
-- link to the original charge) and wallet adjustments (BR-011, BR-012: corrections are new
-- ledger entries). Both are requested by finance staff and audited (BR-014).

-- A captured payment can be refunded; refunded_amount is the sum of its succeeded refunds and is
-- kept under the payment row lock, so the check below enforces BR-010 in the database itself.
ALTER TABLE payments
    DROP CONSTRAINT payments_status_check,
    DROP CONSTRAINT payments_check,
    ADD COLUMN refunded_amount BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT payments_status_check CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'REFUND_PENDING',
                                                           'PARTIALLY_REFUNDED', 'REFUNDED')),
    ADD CONSTRAINT payments_captured_check
        CHECK ((status IN ('SUCCEEDED', 'REFUND_PENDING', 'PARTIALLY_REFUNDED', 'REFUNDED'))
               = (provider_ref IS NOT NULL AND succeeded_at IS NOT NULL)),
    ADD CONSTRAINT payments_refunded_amount_check CHECK (refunded_amount >= 0 AND refunded_amount <= amount),
    ADD CONSTRAINT payments_refund_status_check
        CHECK ((status <> 'REFUNDED' OR refunded_amount = amount)
               AND (status <> 'PARTIALLY_REFUNDED' OR (refunded_amount > 0 AND refunded_amount < amount))
               AND (status NOT IN ('PENDING', 'FAILED') OR refunded_amount = 0));

CREATE TABLE refunds (
    id               UUID         PRIMARY KEY,
    payment_id       UUID         NOT NULL REFERENCES payments (id),
    trip_id          UUID         NOT NULL,
    amount           BIGINT       NOT NULL CHECK (amount > 0),
    currency         CHAR(3)      NOT NULL,
    reason           VARCHAR(30)  NOT NULL,
    note             VARCHAR(500),
    status           VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    -- Sent to the provider; re-sending a refund whose outcome was lost never refunds twice.
    idempotency_key  VARCHAR(80)  NOT NULL UNIQUE,
    provider         VARCHAR(30)  NOT NULL,
    provider_ref     VARCHAR(100),
    failure_code     VARCHAR(50),
    -- The client's Idempotency-Key (COM-008): the same request sent again returns this refund.
    request_key      VARCHAR(100) NOT NULL,
    request_hash     CHAR(64)     NOT NULL,
    requested_by     UUID         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    completed_at     TIMESTAMPTZ,
    version          BIGINT       NOT NULL,
    UNIQUE (payment_id, request_key),
    CHECK ((status = 'PENDING') = (completed_at IS NULL)),
    CHECK (status <> 'SUCCEEDED' OR provider_ref IS NOT NULL)
);

CREATE INDEX ix_refunds_payment ON refunds (payment_id, created_at);
CREATE INDEX ix_refunds_pending ON refunds (created_at) WHERE status = 'PENDING';
-- One refund in flight per payment: its outcome is known before the next one is accepted.
CREATE UNIQUE INDEX ux_refunds_one_pending ON refunds (payment_id) WHERE status = 'PENDING';

ALTER TABLE provider_callbacks ADD COLUMN refund_id UUID REFERENCES refunds (id);

-- A finance correction to a driver's wallet. The ledger line it posts is the ADJUSTMENT entry
-- whose reference is this row; both are append-only.
CREATE TABLE wallet_adjustments (
    id            UUID         PRIMARY KEY,
    wallet_id     UUID         NOT NULL REFERENCES wallets (id),
    amount        BIGINT       NOT NULL CHECK (amount <> 0),
    currency      CHAR(3)      NOT NULL,
    reason        VARCHAR(30)  NOT NULL,
    note          VARCHAR(500),
    trip_id       UUID,
    refund_id     UUID         REFERENCES refunds (id),
    request_key   VARCHAR(100) NOT NULL,
    request_hash  CHAR(64)     NOT NULL,
    requested_by  UUID         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    UNIQUE (wallet_id, request_key)
);

CREATE TRIGGER trg_wallet_adjustments_immutable
    BEFORE UPDATE OR DELETE ON wallet_adjustments
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_change();

-- Sensitive operations (BR-014, FR-ADM-006): who did what to which target, with a safe delta.
CREATE TABLE audit_records (
    id              UUID         PRIMARY KEY,
    actor_id        UUID,
    action          VARCHAR(60)  NOT NULL,
    target_type     VARCHAR(40)  NOT NULL,
    target_id       VARCHAR(64)  NOT NULL,
    result          VARCHAR(20)  NOT NULL,
    delta           JSONB,
    correlation_id  VARCHAR(64),
    occurred_at     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_audit_target ON audit_records (target_type, target_id, occurred_at);

CREATE FUNCTION forbid_modification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER trg_audit_records_append_only
    BEFORE UPDATE OR DELETE ON audit_records
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();

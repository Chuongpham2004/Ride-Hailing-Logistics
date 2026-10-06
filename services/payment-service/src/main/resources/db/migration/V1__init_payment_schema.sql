-- payment-service owns payment_db (CON-02). Money is BIGINT VND, time UTC. No card data is ever
-- stored (CON-08): only the provider's reference for each charge.

CREATE EXTENSION IF NOT EXISTS btree_gist;

-- What a customer owes for a trip. Amounts come from pricing-service events, never from a
-- client (FR-PAY). One payment per trip and purpose (BR-015).
CREATE TABLE payments (
    id               UUID        PRIMARY KEY,
    trip_id          UUID        NOT NULL,
    purpose          VARCHAR(20) NOT NULL CHECK (purpose IN ('TRIP_FARE', 'CANCELLATION_FEE')),
    -- fareId / feeId of the pricing event the amount comes from.
    source_id        UUID        NOT NULL UNIQUE,
    customer_id      UUID        NOT NULL,
    driver_id        UUID,
    service_type     VARCHAR(10) NOT NULL CHECK (service_type IN ('RIDE', 'DELIVERY')),
    amount           BIGINT      NOT NULL CHECK (amount > 0),
    currency         CHAR(3)     NOT NULL,
    status           VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    attempt_count    INT         NOT NULL CHECK (attempt_count >= 0),
    provider         VARCHAR(30),
    provider_ref     VARCHAR(100),
    failure_code     VARCHAR(50),
    version          BIGINT      NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    succeeded_at     TIMESTAMPTZ,
    UNIQUE (trip_id, purpose),
    CHECK ((status = 'SUCCEEDED') = (provider_ref IS NOT NULL AND succeeded_at IS NOT NULL))
);

CREATE INDEX ix_payments_customer ON payments (customer_id, created_at DESC);
CREATE INDEX ix_payments_status ON payments (status, created_at);

-- Every call to the provider. The idempotency key goes to the provider, so retrying an attempt
-- whose outcome is unknown can never charge twice.
CREATE TABLE payment_attempts (
    id               UUID        PRIMARY KEY,
    payment_id       UUID        NOT NULL REFERENCES payments (id),
    attempt_no       INT         NOT NULL CHECK (attempt_no >= 1),
    idempotency_key  VARCHAR(80) NOT NULL UNIQUE,
    status           VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    provider         VARCHAR(30) NOT NULL,
    provider_ref     VARCHAR(100),
    failure_code     VARCHAR(50),
    created_at       TIMESTAMPTZ NOT NULL,
    completed_at     TIMESTAMPTZ,
    version          BIGINT      NOT NULL,
    UNIQUE (payment_id, attempt_no)
);

CREATE INDEX ix_payment_attempts_pending ON payment_attempts (created_at) WHERE status = 'PENDING';

-- Platform commission per service, versioned (FR-WAL); placeholder rate until TBD-10.
CREATE TABLE commission_rules (
    id              UUID         PRIMARY KEY,
    service_type    VARCHAR(10)  NOT NULL CHECK (service_type IN ('RIDE', 'DELIVERY')),
    version         INT          NOT NULL CHECK (version > 0),
    rate            NUMERIC(5, 4) NOT NULL CHECK (rate >= 0 AND rate <= 1),
    effective_from  TIMESTAMPTZ  NOT NULL,
    effective_to    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    UNIQUE (service_type, version),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    EXCLUDE USING gist (service_type WITH =, tstzrange(effective_from, effective_to, '[)') WITH &&)
);

INSERT INTO commission_rules (id, service_type, version, rate, effective_from, created_at)
VALUES ('01a00000-0000-7000-8000-000000000031', 'RIDE', 1, 0.2000, '2026-01-01T00:00:00Z', now()),
       ('01a00000-0000-7000-8000-000000000032', 'DELIVERY', 1, 0.2000, '2026-01-01T00:00:00Z', now());

-- One wallet per driver and currency (FR-WAL). balance is derived from the ledger and kept in
-- step under a row lock; it can never go negative (FR-WAL-009).
CREATE TABLE wallets (
    id          UUID        PRIMARY KEY,
    driver_id   UUID        NOT NULL,
    currency    CHAR(3)     NOT NULL,
    balance     BIGINT      NOT NULL CHECK (balance >= 0),
    version     BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    UNIQUE (driver_id, currency)
);

-- Immutable ledger (BR-011): corrections are new entries, never edits. The unique key makes
-- crediting a payment idempotent (README §4.5).
CREATE TABLE wallet_entries (
    id                       UUID        PRIMARY KEY,
    wallet_id                UUID        NOT NULL REFERENCES wallets (id),
    entry_type               VARCHAR(20) NOT NULL CHECK (entry_type IN ('EARNING', 'COMMISSION', 'ADJUSTMENT')),
    amount                   BIGINT      NOT NULL CHECK (amount <> 0),
    balance_after            BIGINT      NOT NULL CHECK (balance_after >= 0),
    reference_type           VARCHAR(20) NOT NULL,
    reference_id             UUID        NOT NULL,
    trip_id                  UUID,
    commission_rule_version  INT,
    created_at               TIMESTAMPTZ NOT NULL,
    UNIQUE (reference_type, reference_id, entry_type),
    -- Earnings credit, commissions debit; adjustments (corrections) may go either way.
    CHECK ((entry_type = 'EARNING' AND amount > 0) OR (entry_type = 'COMMISSION' AND amount < 0)
           OR entry_type = 'ADJUSTMENT')
);

CREATE INDEX ix_wallet_entries_wallet ON wallet_entries (wallet_id, created_at DESC, id DESC);

CREATE FUNCTION reject_ledger_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'wallet_entries is append-only';
END;
$$;

CREATE TRIGGER trg_wallet_entries_immutable
    BEFORE UPDATE OR DELETE ON wallet_entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_change();

CREATE TABLE outbox_events (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id      UUID         NOT NULL UNIQUE,
    topic         VARCHAR(200) NOT NULL,
    message_key   VARCHAR(200) NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    envelope      JSONB        NOT NULL,
    status        VARCHAR(10)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT')),
    attempts      INT          NOT NULL DEFAULT 0,
    last_error    VARCHAR(1000),
    created_at    TIMESTAMPTZ  NOT NULL,
    sent_at       TIMESTAMPTZ
);

CREATE INDEX ix_outbox_pending ON outbox_events (id) WHERE status = 'PENDING';

CREATE TABLE processed_events (
    consumer     VARCHAR(100) NOT NULL,
    event_id     UUID         NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (consumer, event_id)
);

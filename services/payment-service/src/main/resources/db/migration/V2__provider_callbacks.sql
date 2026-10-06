-- Every webhook the provider sent, with what was done with it (FR-PAY: repeated or out-of-order
-- callbacks never charge twice; replays are refused). The unique key is the provider's own event
-- ID, so a redelivered callback is recognised before anything is applied. No card data: the
-- payload carries only references, amounts and outcomes.
CREATE TABLE provider_callbacks (
    id                 UUID         PRIMARY KEY,
    provider           VARCHAR(30)  NOT NULL,
    provider_event_id  VARCHAR(100) NOT NULL,
    event_type         VARCHAR(50)  NOT NULL,
    idempotency_key    VARCHAR(80),
    payment_id         UUID         REFERENCES payments (id),
    outcome            VARCHAR(20)  NOT NULL
        CHECK (outcome IN ('RECEIVED', 'APPLIED', 'ALREADY_RESOLVED', 'REJECTED')),
    reason             VARCHAR(200),
    payload            JSONB        NOT NULL,
    signed_at          TIMESTAMPTZ  NOT NULL,
    received_at        TIMESTAMPTZ  NOT NULL,
    UNIQUE (provider, provider_event_id)
);

CREATE INDEX ix_provider_callbacks_payment ON provider_callbacks (payment_id);

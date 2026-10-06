-- Settlement after a trip ends: one final fare per completed trip (BR-009) and one fee decision
-- per cancelled trip (FR-CAN). UNIQUE (trip_id) is the business idempotency key, on top of
-- processed_events for redelivered events. Neither table is ever updated.

CREATE TABLE final_fares (
    id                      UUID          PRIMARY KEY,
    trip_id                 UUID          NOT NULL UNIQUE,
    quote_id                UUID          NOT NULL REFERENCES fare_quotes (id),
    customer_id             UUID          NOT NULL,
    driver_id               UUID          NOT NULL,
    -- UPFRONT: the booked quote is charged exactly (TBD-05 may add metered settlement later).
    method                  VARCHAR(20)   NOT NULL CHECK (method IN ('UPFRONT')),
    base_fare               BIGINT        NOT NULL,
    distance_fare           BIGINT        NOT NULL,
    time_fare               BIGINT        NOT NULL,
    minimum_fare_adjustment BIGINT        NOT NULL,
    surge_amount            BIGINT        NOT NULL,
    rounding_adjustment     BIGINT        NOT NULL,
    total                   BIGINT        NOT NULL CHECK (total >= 0),
    currency                CHAR(3)       NOT NULL,
    surge_multiplier        NUMERIC(4, 2) NOT NULL CHECK (surge_multiplier >= 1),
    pricing_rule_version    INT           NOT NULL,
    completed_at            TIMESTAMPTZ   NOT NULL,
    finalized_at            TIMESTAMPTZ   NOT NULL,
    CHECK (total = base_fare + distance_fare + time_fare + minimum_fare_adjustment + surge_amount
                   + rounding_adjustment)
);

-- Versioned like the other rules; placeholder amounts until TBD-07 (VND).
CREATE TABLE cancellation_fee_rules (
    id                   UUID        PRIMARY KEY,
    service_type         VARCHAR(10) NOT NULL CHECK (service_type IN ('RIDE', 'DELIVERY')),
    version              INT         NOT NULL CHECK (version > 0),
    -- A customer may cancel this long after a driver accepted without paying.
    free_window_seconds  INT         NOT NULL CHECK (free_window_seconds >= 0),
    customer_cancel_fee  BIGINT      NOT NULL CHECK (customer_cancel_fee >= 0),
    -- Charged when the driver waited at the pickup and the customer did not show up.
    no_show_fee          BIGINT      NOT NULL CHECK (no_show_fee >= 0),
    currency             CHAR(3)     NOT NULL,
    effective_from       TIMESTAMPTZ NOT NULL,
    effective_to         TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL,
    UNIQUE (service_type, version),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    EXCLUDE USING gist (service_type WITH =, tstzrange(effective_from, effective_to, '[)') WITH &&)
);

INSERT INTO cancellation_fee_rules (id, service_type, version, free_window_seconds, customer_cancel_fee,
                                    no_show_fee, currency, effective_from, created_at)
VALUES ('01a00000-0000-7000-8000-000000000021', 'RIDE', 1, 120, 10000, 15000, 'VND',
        '2026-01-01T00:00:00Z', now()),
       ('01a00000-0000-7000-8000-000000000022', 'DELIVERY', 1, 120, 10000, 15000, 'VND',
        '2026-01-01T00:00:00Z', now());

CREATE TABLE cancellation_fees (
    id             UUID        PRIMARY KEY,
    trip_id        UUID        NOT NULL UNIQUE,
    customer_id    UUID        NOT NULL,
    driver_id      UUID,
    quote_id       UUID,
    rule_id        UUID        NOT NULL REFERENCES cancellation_fee_rules (id),
    rule_version   INT         NOT NULL,
    actor_type     VARCHAR(20) NOT NULL,
    old_status     VARCHAR(20) NOT NULL,
    cancel_reason  VARCHAR(40) NOT NULL,
    decision       VARCHAR(30) NOT NULL
        CHECK (decision IN ('NOT_ASSIGNED', 'WITHIN_FREE_WINDOW', 'LATE_CANCELLATION', 'NO_SHOW',
                            'NOT_CHARGEABLE')),
    fee            BIGINT      NOT NULL CHECK (fee >= 0),
    currency       CHAR(3)     NOT NULL,
    cancelled_at   TIMESTAMPTZ NOT NULL,
    calculated_at  TIMESTAMPTZ NOT NULL,
    -- Only these decisions may charge anything (a rule may still set their amount to 0).
    CHECK (fee = 0 OR decision IN ('LATE_CANCELLATION', 'NO_SHOW'))
);

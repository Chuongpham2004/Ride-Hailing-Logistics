-- pricing-service owns pricing_db (CON-02). Money is BIGINT VND (FR-PRI-016, DR-008); time is UTC.

-- Overlap check on effective periods (FR-PRI: no two rules for the same service and region at
-- the same time). btree_gist is a trusted extension, so the database owner can create it.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- A rule is never edited: a new version closes the previous one (effective_to) and takes over.
-- Quotes keep the rule id and version they were priced with, so a price can always be re-derived.
CREATE TABLE pricing_rules (
    id              UUID         PRIMARY KEY,
    service_type    VARCHAR(10)  NOT NULL CHECK (service_type IN ('RIDE', 'DELIVERY')),
    region_code     VARCHAR(30)  NOT NULL,
    version         INT          NOT NULL CHECK (version > 0),
    base_fare       BIGINT       NOT NULL CHECK (base_fare >= 0),
    per_km          BIGINT       NOT NULL CHECK (per_km >= 0),
    per_minute      BIGINT       NOT NULL CHECK (per_minute >= 0),
    minimum_fare    BIGINT       NOT NULL CHECK (minimum_fare >= 0),
    currency        CHAR(3)      NOT NULL,
    effective_from  TIMESTAMPTZ  NOT NULL,
    effective_to    TIMESTAMPTZ,
    created_by      UUID,
    created_at      TIMESTAMPTZ  NOT NULL,
    row_version     BIGINT       NOT NULL,
    UNIQUE (service_type, region_code, version),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    EXCLUDE USING gist (service_type WITH =, region_code WITH =,
                        tstzrange(effective_from, effective_to, '[)') WITH &&)
);

-- Placeholder prices until TBD-03 is settled (README §15). VND.
INSERT INTO pricing_rules (id, service_type, region_code, version, base_fare, per_km, per_minute, minimum_fare,
                           currency, effective_from, created_at, row_version)
VALUES ('01a00000-0000-7000-8000-000000000001', 'RIDE', 'DEFAULT', 1, 12000, 4300, 350, 15000, 'VND',
        '2026-01-01T00:00:00Z', now(), 0),
       ('01a00000-0000-7000-8000-000000000002', 'DELIVERY', 'DEFAULT', 1, 15000, 5000, 300, 18000, 'VND',
        '2026-01-01T00:00:00Z', now(), 0);

-- Quotes are bound to the customer who asked (BR-005) and hold the full price snapshot
-- (BR-007): the trip copies it, so later rule changes never alter an accepted price.
CREATE TABLE fare_quotes (
    id                    UUID          PRIMARY KEY,
    customer_id           UUID          NOT NULL,
    service_type          VARCHAR(10)   NOT NULL CHECK (service_type IN ('RIDE', 'DELIVERY')),
    pickup_latitude       DOUBLE PRECISION NOT NULL CHECK (pickup_latitude BETWEEN -90 AND 90),
    pickup_longitude      DOUBLE PRECISION NOT NULL CHECK (pickup_longitude BETWEEN -180 AND 180),
    pickup_address        VARCHAR(300)  NOT NULL,
    dropoff_latitude      DOUBLE PRECISION NOT NULL CHECK (dropoff_latitude BETWEEN -90 AND 90),
    dropoff_longitude     DOUBLE PRECISION NOT NULL CHECK (dropoff_longitude BETWEEN -180 AND 180),
    dropoff_address       VARCHAR(300)  NOT NULL,
    distance_meters       INT           NOT NULL CHECK (distance_meters > 0),
    duration_seconds      INT           NOT NULL CHECK (duration_seconds > 0),
    route_source          VARCHAR(20)   NOT NULL,
    rule_id               UUID          NOT NULL REFERENCES pricing_rules (id),
    rule_version          INT           NOT NULL,
    surge_multiplier      NUMERIC(4, 2) NOT NULL CHECK (surge_multiplier >= 1),
    base_fare             BIGINT        NOT NULL,
    distance_fare         BIGINT        NOT NULL,
    time_fare             BIGINT        NOT NULL,
    minimum_fare_adjustment BIGINT      NOT NULL,
    surge_amount          BIGINT        NOT NULL,
    rounding_adjustment   BIGINT        NOT NULL,
    total                 BIGINT        NOT NULL CHECK (total >= 0),
    currency              CHAR(3)       NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL,
    expires_at            TIMESTAMPTZ   NOT NULL,
    CHECK (expires_at > created_at),
    CHECK (total = base_fare + distance_fare + time_fare + minimum_fare_adjustment + surge_amount
                   + rounding_adjustment)
);

CREATE INDEX ix_fare_quotes_customer ON fare_quotes (customer_id, created_at DESC);

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

-- Supply/demand surge (FR-PRI-007…012). Like pricing_rules, a surge rule is never edited: a new
-- version takes over at its start, so every quote can name the formula it was priced with.
CREATE TABLE surge_rules (
    id               UUID          PRIMARY KEY,
    service_type     VARCHAR(10)   NOT NULL CHECK (service_type IN ('RIDE', 'DELIVERY')),
    version          INT           NOT NULL CHECK (version > 0),
    -- Below this many requests in the area and window there is no surge (too little signal).
    min_demand       INT           NOT NULL CHECK (min_demand >= 1),
    -- multiplier = 1 + slope × (demand / max(supply, 1) − ratio_threshold), clamped to
    -- [1, max_multiplier] and rounded down to a multiple of step.
    ratio_threshold  NUMERIC(6, 2) NOT NULL CHECK (ratio_threshold >= 0),
    slope            NUMERIC(6, 2) NOT NULL CHECK (slope >= 0),
    max_multiplier   NUMERIC(4, 2) NOT NULL CHECK (max_multiplier >= 1),
    step             NUMERIC(3, 2) NOT NULL CHECK (step > 0),
    effective_from   TIMESTAMPTZ   NOT NULL,
    effective_to     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL,
    UNIQUE (service_type, version),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    EXCLUDE USING gist (service_type WITH =, tstzrange(effective_from, effective_to, '[)') WITH &&)
);

-- Placeholders until TBD-04 (area size, window, formula, cap) is settled.
INSERT INTO surge_rules (id, service_type, version, min_demand, ratio_threshold, slope, max_multiplier, step,
                         effective_from, created_at)
VALUES ('01a00000-0000-7000-8000-000000000011', 'RIDE', 1, 3, 1.00, 0.50, 2.00, 0.10,
        '2026-01-01T00:00:00Z', now()),
       ('01a00000-0000-7000-8000-000000000012', 'DELIVERY', 1, 3, 1.00, 0.50, 2.00, 0.10,
        '2026-01-01T00:00:00Z', now());

-- The surge inputs behind each quote, so a multiplier can be explained and re-derived.
-- surge_source: COMPUTED from the counters; UNAVAILABLE when Redis could not be read (priced
-- at 1.00, never above what the customer could have been shown); NONE for quotes issued
-- before surge existed.
ALTER TABLE fare_quotes
    ADD COLUMN surge_source       VARCHAR(20) NOT NULL DEFAULT 'NONE'
        CHECK (surge_source IN ('NONE', 'COMPUTED', 'UNAVAILABLE')),
    ADD COLUMN h3_cell            VARCHAR(20),
    ADD COLUMN surge_demand       INT CHECK (surge_demand >= 0),
    ADD COLUMN surge_supply       INT CHECK (surge_supply >= 0),
    ADD COLUMN surge_rule_id      UUID REFERENCES surge_rules (id),
    ADD COLUMN surge_rule_version INT;

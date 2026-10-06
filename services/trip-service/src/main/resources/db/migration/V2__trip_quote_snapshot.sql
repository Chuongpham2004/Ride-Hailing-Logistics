-- Trips are created from a pricing-service quote (BR-005) and keep its price snapshot (BR-007):
-- later rule or surge changes never alter what the customer accepted. Nullable only for trips
-- created before quotes were required.
ALTER TABLE trips
    ADD COLUMN quote_id               UUID,
    ADD COLUMN quoted_fare            BIGINT CHECK (quoted_fare >= 0),
    ADD COLUMN currency               CHAR(3),
    ADD COLUMN surge_multiplier       NUMERIC(4, 2) CHECK (surge_multiplier >= 1),
    ADD COLUMN pricing_rule_version   INT,
    ADD COLUMN route_distance_meters  INT CHECK (route_distance_meters > 0),
    ADD COLUMN route_duration_seconds INT CHECK (route_duration_seconds > 0),
    ADD CHECK ((quote_id IS NULL) = (quoted_fare IS NULL));

-- A quote buys one trip.
CREATE UNIQUE INDEX ux_trips_quote ON trips (quote_id) WHERE quote_id IS NOT NULL;

-- trip-service owns trip_db (CON-02): trips, their immutable status history, driver offers and
-- the idempotency/outbox tables. Statuses are VARCHAR + CHECK (README §4.5).

CREATE TABLE trips (
    id                      UUID         PRIMARY KEY,
    customer_id             UUID         NOT NULL,
    driver_id               UUID,
    service_type            VARCHAR(10)  NOT NULL CHECK (service_type IN ('RIDE', 'DELIVERY')),
    status                  VARCHAR(20)  NOT NULL
        CHECK (status IN ('CREATED', 'MATCHING', 'ACCEPTED', 'PICKING_UP', 'ARRIVED', 'IN_TRIP',
                          'COMPLETED', 'CANCELLED', 'NO_DRIVER')),
    -- Snapshot of the stops (BR-007); one pickup and one drop-off in v1.0.
    pickup_latitude         DOUBLE PRECISION NOT NULL CHECK (pickup_latitude BETWEEN -90 AND 90),
    pickup_longitude        DOUBLE PRECISION NOT NULL CHECK (pickup_longitude BETWEEN -180 AND 180),
    pickup_address          VARCHAR(300) NOT NULL,
    dropoff_latitude        DOUBLE PRECISION NOT NULL CHECK (dropoff_latitude BETWEEN -90 AND 90),
    dropoff_longitude       DOUBLE PRECISION NOT NULL CHECK (dropoff_longitude BETWEEN -180 AND 180),
    dropoff_address         VARCHAR(300) NOT NULL,
    -- Dispatch progress: current search radius and when matching gives up (README §5.2).
    matching_radius_meters  INT          NOT NULL CHECK (matching_radius_meters > 0),
    matching_deadline       TIMESTAMPTZ  NOT NULL,
    cancel_reason           VARCHAR(40),
    cancel_note             VARCHAR(300),
    cancelled_by            VARCHAR(20),
    version                 BIGINT       NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL,
    updated_at              TIMESTAMPTZ  NOT NULL,
    accepted_at             TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ,
    cancelled_at            TIMESTAMPTZ,
    CHECK (driver_id IS NOT NULL OR status IN ('CREATED', 'MATCHING', 'CANCELLED', 'NO_DRIVER'))
);

-- BR-002 / CON-06: last line of defence against double assignment, whatever Redis says.
CREATE UNIQUE INDEX ux_trips_active_driver ON trips (driver_id)
    WHERE status IN ('ACCEPTED', 'PICKING_UP', 'ARRIVED', 'IN_TRIP');
-- FR-TRIP-007: one active trip per customer.
CREATE UNIQUE INDEX ux_trips_active_customer ON trips (customer_id)
    WHERE status IN ('CREATED', 'MATCHING', 'ACCEPTED', 'PICKING_UP', 'ARRIVED', 'IN_TRIP');
CREATE INDEX ix_trips_customer_created ON trips (customer_id, created_at DESC, id DESC);
CREATE INDEX ix_trips_driver_created ON trips (driver_id, created_at DESC, id DESC) WHERE driver_id IS NOT NULL;
CREATE INDEX ix_trips_matching ON trips (matching_deadline) WHERE status = 'MATCHING';

-- Append-only (DR-005): the application never updates or deletes rows, and the trigger below
-- makes sure nobody else does either.
CREATE TABLE trip_status_history (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    trip_id      UUID         NOT NULL REFERENCES trips (id),
    from_status  VARCHAR(20),
    to_status    VARCHAR(20)  NOT NULL,
    actor_type   VARCHAR(20)  NOT NULL CHECK (actor_type IN ('CUSTOMER', 'DRIVER', 'STAFF', 'SYSTEM')),
    actor_id     UUID,
    reason       VARCHAR(300),
    occurred_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_trip_status_history_trip ON trip_status_history (trip_id, id);

CREATE FUNCTION reject_history_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'trip_status_history is append-only';
END;
$$;

CREATE TRIGGER trg_trip_status_history_immutable
    BEFORE UPDATE OR DELETE ON trip_status_history
    FOR EACH ROW EXECUTE FUNCTION reject_history_change();

CREATE TABLE driver_offers (
    id                     UUID        PRIMARY KEY,
    trip_id                UUID        NOT NULL REFERENCES trips (id),
    driver_id              UUID        NOT NULL,
    status                 VARCHAR(20) NOT NULL
        CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'CANCELLED')),
    pickup_distance_meters INT         NOT NULL CHECK (pickup_distance_meters >= 0),
    version                BIGINT      NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL,
    expires_at             TIMESTAMPTZ NOT NULL,
    responded_at           TIMESTAMPTZ,
    CHECK (expires_at > created_at)
);

-- Offers go out one at a time per trip, and a driver holds at most one open offer.
CREATE UNIQUE INDEX ux_driver_offers_pending_trip ON driver_offers (trip_id) WHERE status = 'PENDING';
CREATE UNIQUE INDEX ux_driver_offers_pending_driver ON driver_offers (driver_id) WHERE status = 'PENDING';
CREATE INDEX ix_driver_offers_trip ON driver_offers (trip_id);
CREATE INDEX ix_driver_offers_expiry ON driver_offers (expires_at) WHERE status = 'PENDING';

-- COM-008: replaying a request with the same key returns the stored response; a different
-- payload under the same key is refused with IDEMPOTENCY_KEY_REUSED.
CREATE TABLE idempotency_keys (
    scope         VARCHAR(100) NOT NULL,
    idem_key      VARCHAR(100) NOT NULL,
    request_hash  CHAR(64)     NOT NULL,
    resource_id   UUID         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (scope, idem_key)
);

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

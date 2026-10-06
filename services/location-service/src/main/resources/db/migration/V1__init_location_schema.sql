-- location-service owns location_db (CON-02). Redis holds the live index; these tables are what
-- it is rebuilt from (DR-GEO-005) plus the telemetry history kept under the retention policy.

-- Projection of DriverAvailabilityChanged (driver.events.v1). user-service stays the source of
-- truth; aggregate_version drops stale or replayed events (FR-EVT-006).
CREATE TABLE driver_presence (
    driver_id         UUID         PRIMARY KEY,
    availability      VARCHAR(20)  NOT NULL
        CHECK (availability IN ('OFFLINE', 'AVAILABLE', 'OFFERED', 'BUSY')),
    service_types     VARCHAR(100) NOT NULL DEFAULT '',
    vehicle_id        UUID,
    aggregate_version BIGINT       NOT NULL,
    changed_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_driver_presence_availability ON driver_presence (availability);

-- Every non-duplicate report, including the ones not used as current location. Partitioned by
-- day so retention is a DROP of old partitions; TelemetryPartitions creates them ahead of time.
CREATE TABLE telemetry_history (
    driver_id              UUID             NOT NULL,
    sequence               BIGINT           NOT NULL,
    latitude               DOUBLE PRECISION NOT NULL,
    longitude              DOUBLE PRECISION NOT NULL,
    accuracy_meters        REAL             NOT NULL,
    heading_degrees        REAL,
    speed_meters_per_second REAL,
    device_time            TIMESTAMPTZ      NOT NULL,
    received_at            TIMESTAMPTZ      NOT NULL,
    quality                VARCHAR(20)      NOT NULL
        CHECK (quality IN ('CURRENT', 'BACKFILL', 'OUT_OF_ORDER', 'LOW_ACCURACY', 'SUSPICIOUS')),
    source                 VARCHAR(10)      NOT NULL CHECK (source IN ('STREAM', 'HTTP'))
) PARTITION BY RANGE (received_at);

CREATE INDEX ix_telemetry_history_driver ON telemetry_history (driver_id, received_at);

CREATE TABLE processed_events (
    consumer     VARCHAR(100) NOT NULL,
    event_id     UUID         NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (consumer, event_id)
);

-- Delivery trips and handover codes (FR-TRIP, UC-05; TBD-08 chose a delivery code as proof).
-- Codes are shown to the customer only and checked when the driver enters them; wrong entries
-- are counted so a 4-digit code cannot be guessed.
ALTER TABLE trips
    ADD COLUMN pickup_code            VARCHAR(8),
    ADD COLUMN delivery_code          VARCHAR(8),
    ADD COLUMN pickup_code_failures   INT NOT NULL DEFAULT 0 CHECK (pickup_code_failures >= 0),
    ADD COLUMN delivery_code_failures INT NOT NULL DEFAULT 0 CHECK (delivery_code_failures >= 0),
    ADD CONSTRAINT ck_trips_delivery_code CHECK (delivery_code IS NULL OR service_type = 'DELIVERY');

-- Recipient and package snapshot of a DELIVERY trip, taken at booking. Personal data: shown only
-- to the customer, the assigned driver and staff, never put in offers or events (BR-013).
CREATE TABLE delivery_details (
    trip_id               UUID         PRIMARY KEY REFERENCES trips (id),
    recipient_name        VARCHAR(120) NOT NULL,
    recipient_phone       VARCHAR(16)  NOT NULL,
    package_description   VARCHAR(200) NOT NULL,
    package_size          VARCHAR(10)  NOT NULL CHECK (package_size IN ('SMALL', 'MEDIUM', 'LARGE')),
    package_weight_grams  INT          NOT NULL CHECK (package_weight_grams > 0),
    instructions          VARCHAR(300),
    created_at            TIMESTAMPTZ  NOT NULL
);

-- How a delivery was proven, once per trip; append-only like the status history.
CREATE TABLE delivery_proofs (
    id           UUID        PRIMARY KEY,
    trip_id      UUID        NOT NULL UNIQUE REFERENCES trips (id),
    method       VARCHAR(20) NOT NULL CHECK (method IN ('DELIVERY_CODE')),
    driver_id    UUID        NOT NULL,
    verified_at  TIMESTAMPTZ NOT NULL
);

CREATE FUNCTION reject_proof_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'delivery_proofs is append-only';
END;
$$;

CREATE TRIGGER trg_delivery_proofs_immutable
    BEFORE UPDATE OR DELETE ON delivery_proofs
    FOR EACH ROW EXECUTE FUNCTION reject_proof_change();

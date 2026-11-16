-- Support let the driver try a locked handover code again (README §6). The note explains why
-- (it may name people, so it stays here and not in the audit delta); append-only.
CREATE TABLE handover_code_unlocks (
    id              UUID         PRIMARY KEY,
    trip_id         UUID         NOT NULL REFERENCES trips (id),
    code            VARCHAR(10)  NOT NULL CHECK (code IN ('PICKUP', 'DELIVERY')),
    staff_id        UUID         NOT NULL,
    failures_before INTEGER      NOT NULL CHECK (failures_before > 0),
    note            VARCHAR(300) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_handover_code_unlocks_trip ON handover_code_unlocks (trip_id, created_at);

CREATE FUNCTION reject_code_unlock_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'handover_code_unlocks is append-only';
END;
$$;

CREATE TRIGGER trg_handover_code_unlocks_immutable
    BEFORE UPDATE OR DELETE ON handover_code_unlocks
    FOR EACH ROW EXECUTE FUNCTION reject_code_unlock_change();

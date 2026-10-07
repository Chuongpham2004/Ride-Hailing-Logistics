-- Staff actions on trips (BR-014, FR-ADM-006): exceptional cancellations and looking up a trip
-- with its personal data. Append-only; the delta never holds free text or recipient details.
CREATE TABLE audit_records (
    id              UUID         PRIMARY KEY,
    actor_id        UUID,
    action          VARCHAR(60)  NOT NULL,
    target_type     VARCHAR(40)  NOT NULL,
    target_id       VARCHAR(64)  NOT NULL,
    result          VARCHAR(20)  NOT NULL,
    delta           JSONB,
    correlation_id  VARCHAR(64),
    occurred_at     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_audit_target ON audit_records (target_type, target_id, occurred_at);

CREATE FUNCTION reject_audit_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_records is append-only';
END;
$$;

CREATE TRIGGER trg_audit_records_immutable
    BEFORE UPDATE OR DELETE ON audit_records
    FOR EACH ROW EXECUTE FUNCTION reject_audit_change();

-- Staff lookups filter by status, customer or driver over time (FR-ADM-001, NFR-PERF-008).
CREATE INDEX ix_trips_status_created ON trips (status, created_at);

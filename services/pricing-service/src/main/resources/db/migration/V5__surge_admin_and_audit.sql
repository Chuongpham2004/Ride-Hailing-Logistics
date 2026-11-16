-- Administrators schedule surge rule versions like price lists (README §3, FR-PRI-007…012): the
-- current version is closed when the next one starts, so it now needs the optimistic lock and
-- the author that pricing_rules already have. Rows created by migration have no author.
ALTER TABLE surge_rules
    ADD COLUMN created_by  UUID,
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;

-- Who changed prices or surge, and to what (BR-014, FR-ADM-006). Append-only; the delta holds
-- the old and new values only, never free text.
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

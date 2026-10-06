-- user_db baseline (README §4.5). Times are TIMESTAMPTZ (UTC), IDs are UUIDv7, statuses are
-- VARCHAR + CHECK so new values only need a constraint change.

CREATE TABLE users (
    id             UUID PRIMARY KEY,
    email          VARCHAR(254),
    phone          VARCHAR(16),
    password_hash  VARCHAR(100) NOT NULL,
    full_name      VARCHAR(120) NOT NULL,
    status         VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_users_identifier CHECK (email IS NOT NULL OR phone IS NOT NULL)
);
-- Identifiers are normalized by the application (lower-case email, E.164 phone) before insert.
CREATE UNIQUE INDEX ux_users_email ON users (email) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX ux_users_phone ON users (phone) WHERE phone IS NOT NULL;

CREATE TABLE user_roles (
    user_id  UUID        NOT NULL REFERENCES users (id),
    role     VARCHAR(20) NOT NULL CHECK (role IN ('CUSTOMER', 'DRIVER', 'REVIEWER', 'SUPPORT_STAFF',
                                                   'FINANCE_STAFF', 'ADMINISTRATOR')),
    PRIMARY KEY (user_id, role)
);

CREATE TABLE refresh_tokens (
    id           UUID PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES users (id),
    family_id    UUID        NOT NULL,
    token_hash   VARCHAR(64) NOT NULL UNIQUE,
    expires_at   TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);

CREATE TABLE driver_profiles (
    driver_id          UUID PRIMARY KEY REFERENCES users (id),
    full_name          VARCHAR(120) NOT NULL,
    date_of_birth      DATE,
    review_status      VARCHAR(20)  NOT NULL CHECK (review_status IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED',
                                                                       'REJECTED', 'SUSPENDED')),
    status_reason      VARCHAR(500),
    profile_version    INT          NOT NULL DEFAULT 0,
    submitted_at       TIMESTAMPTZ,
    availability       VARCHAR(20)  NOT NULL DEFAULT 'OFFLINE'
                           CHECK (availability IN ('OFFLINE', 'AVAILABLE', 'OFFERED', 'BUSY')),
    active_vehicle_id  UUID,
    availability_changed_at TIMESTAMPTZ,
    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_driver_profiles_review ON driver_profiles (review_status, submitted_at);

CREATE TABLE driver_profile_service_types (
    driver_id     UUID        NOT NULL REFERENCES driver_profiles (driver_id),
    service_type  VARCHAR(20) NOT NULL CHECK (service_type IN ('RIDE', 'DELIVERY')),
    PRIMARY KEY (driver_id, service_type)
);

CREATE TABLE vehicles (
    id            UUID PRIMARY KEY,
    driver_id     UUID        NOT NULL REFERENCES driver_profiles (driver_id),
    type          VARCHAR(20) NOT NULL CHECK (type IN ('MOTORBIKE', 'CAR_4_SEAT', 'CAR_7_SEAT', 'VAN')),
    plate_number  VARCHAR(16) NOT NULL,
    brand         VARCHAR(60) NOT NULL,
    model         VARCHAR(60) NOT NULL,
    color         VARCHAR(30) NOT NULL,
    manufacture_year INT      NOT NULL CHECK (manufacture_year BETWEEN 1980 AND 2100),
    status        VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version       BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL
);
-- A plate can be registered to only one active vehicle record across all drivers.
CREATE UNIQUE INDEX ux_vehicles_active_plate ON vehicles (plate_number) WHERE status = 'ACTIVE';
CREATE INDEX ix_vehicles_driver ON vehicles (driver_id);

CREATE TABLE driver_documents (
    id               UUID PRIMARY KEY,
    driver_id        UUID         NOT NULL REFERENCES driver_profiles (driver_id),
    vehicle_id       UUID REFERENCES vehicles (id),
    type             VARCHAR(30)  NOT NULL CHECK (type IN ('NATIONAL_ID', 'DRIVER_LICENSE',
                                                           'VEHICLE_REGISTRATION', 'VEHICLE_INSURANCE')),
    document_number  VARCHAR(40)  NOT NULL,
    issued_on        DATE,
    expires_on       DATE,
    file_ref         VARCHAR(300),
    status           VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE', 'REPLACED')),
    created_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_documents_vehicle_scope CHECK (
        (type IN ('VEHICLE_REGISTRATION', 'VEHICLE_INSURANCE')) = (vehicle_id IS NOT NULL))
);
-- One current document per type (per vehicle for vehicle-scoped types); older ones are REPLACED.
CREATE UNIQUE INDEX ux_documents_current ON driver_documents
    (driver_id, type, COALESCE(vehicle_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE status = 'ACTIVE';

CREATE TABLE review_decisions (
    id               UUID PRIMARY KEY,
    driver_id        UUID         NOT NULL REFERENCES driver_profiles (driver_id),
    profile_version  INT          NOT NULL,
    decision         VARCHAR(20)  NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED', 'CHANGES_REQUESTED',
                                                              'SUSPENDED', 'REINSTATED')),
    reason           VARCHAR(500),
    reviewer_id      UUID         NOT NULL,
    decided_at       TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_review_decisions_driver ON review_decisions (driver_id, decided_at);

CREATE TABLE audit_records (
    id              UUID PRIMARY KEY,
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

-- Append-only records (DR-005, FR-ADM-006): corrections are new rows, never edits.
CREATE FUNCTION forbid_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_review_decisions_append_only BEFORE UPDATE OR DELETE ON review_decisions
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER trg_audit_records_append_only BEFORE UPDATE OR DELETE ON audit_records
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();

-- Messaging infrastructure (see common-messaging OutboxWriter / ProcessedEvents).
CREATE TABLE outbox_events (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id     UUID         NOT NULL UNIQUE,
    topic        VARCHAR(200) NOT NULL,
    message_key  VARCHAR(200) NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    envelope     JSONB        NOT NULL,
    status       VARCHAR(10)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT')),
    attempts     INT          NOT NULL DEFAULT 0,
    last_error   VARCHAR(1000),
    created_at   TIMESTAMPTZ  NOT NULL,
    sent_at      TIMESTAMPTZ
);
CREATE INDEX ix_outbox_pending ON outbox_events (id) WHERE status = 'PENDING';

CREATE TABLE processed_events (
    consumer      VARCHAR(100) NOT NULL,
    event_id      UUID         NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (consumer, event_id)
);

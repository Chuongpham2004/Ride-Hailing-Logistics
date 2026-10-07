-- Uploaded document scans and photos (FR-DRV, README §9: uploads are checked and stored outside
-- any executable area). The bytes live in the private object store under object_key; this table
-- is the only way to reach them, and a file can back exactly one driver document.
CREATE TABLE document_files (
    id            UUID         PRIMARY KEY,
    driver_id     UUID         NOT NULL REFERENCES users (id),
    object_key    VARCHAR(200) NOT NULL UNIQUE,
    content_type  VARCHAR(50)  NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'application/pdf')),
    size_bytes    BIGINT       NOT NULL CHECK (size_bytes > 0),
    sha256        CHAR(64)     NOT NULL,
    status        VARCHAR(20)  NOT NULL CHECK (status IN ('UPLOADED', 'ATTACHED')),
    created_at    TIMESTAMPTZ  NOT NULL,
    attached_at   TIMESTAMPTZ,
    CHECK ((status = 'ATTACHED') = (attached_at IS NOT NULL))
);

CREATE INDEX ix_document_files_driver ON document_files (driver_id, created_at);

-- file_ref stays for documents submitted before uploads existed.
ALTER TABLE driver_documents ADD COLUMN file_id UUID UNIQUE REFERENCES document_files (id);

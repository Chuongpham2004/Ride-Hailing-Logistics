-- Contact verification and password reset (FR-IAM: verify email/phone, reset password). The
-- one-time codes themselves live in Redis with a short TTL; only the outcome is stored here.
ALTER TABLE users
    ADD COLUMN email_verified_at   TIMESTAMPTZ,
    ADD COLUMN phone_verified_at   TIMESTAMPTZ,
    ADD COLUMN password_changed_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_users_email_verified CHECK (email_verified_at IS NULL OR email IS NOT NULL),
    ADD CONSTRAINT ck_users_phone_verified CHECK (phone_verified_at IS NULL OR phone IS NOT NULL);

-- One-time codes sent by SMS for app sign-in and password reset. Global (like users): a person signs in before
-- any agency is selected. Only a hash of the code is stored.
CREATE TABLE otp_codes (
    id UUID PRIMARY KEY,
    phone VARCHAR(20) NOT NULL,              -- last 10 digits
    purpose VARCHAR(10) NOT NULL,            -- LOGIN | RESET
    code_hash VARCHAR(100) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    consumed_at TIMESTAMPTZ,
    sent BOOLEAN NOT NULL DEFAULT FALSE,     -- false when the number isn't an active member (nothing is sent)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_otp_phone ON otp_codes (phone, created_at);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${appRole}') THEN
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON otp_codes TO %I', '${appRole}');
    END IF;
END $$;

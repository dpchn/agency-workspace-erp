-- Attendance for workers without a phone (site kiosk, supervisor roll-call, office register entry),
-- stored photo evidence, and client sign-off of monthly attendance.

-- Photos (selfies, team photos, scanned registers). Kept in the database so a single-server install
-- needs no object store; rows are small (the apps compress to ~100-300 KB).
CREATE TABLE attendance_photos (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    kind VARCHAR(20) NOT NULL,              -- SELFIE | TEAM | REGISTER | PROFILE
    content_type VARCHAR(40) NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    size_bytes INT NOT NULL,
    data BYTEA NOT NULL,
    site_id UUID, employee_id UUID,
    taken_at TIMESTAMPTZ,
    uploaded_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id)
);
CREATE INDEX ix_photo_employee ON attendance_photos (tenant_id, employee_id, created_at);

-- Workers without a smartphone punch on a shared site device with employee code + PIN.
ALTER TABLE employees
    ADD COLUMN has_smartphone BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN attendance_pin_hash VARCHAR(100),
    ADD COLUMN pin_failed_count INT NOT NULL DEFAULT 0,
    ADD COLUMN pin_locked_until TIMESTAMPTZ,
    ADD COLUMN profile_photo_id UUID;

-- MOBILE (own phone) | KIOSK (shared device + PIN) | SUPERVISOR (marked by supervisor) | MANUAL (office, from register)
ALTER TABLE attendance_events ADD COLUMN photo_id UUID;
ALTER TABLE attendance_events ADD COLUMN batch_id UUID;
ALTER TABLE attendance_records ADD COLUMN sources VARCHAR(20)[] NOT NULL DEFAULT '{}';
ALTER TABLE attendance_records ADD COLUMN marked_by UUID[] NOT NULL DEFAULT '{}';
ALTER TABLE attendance_adjustments ADD COLUMN photo_id UUID;
ALTER TABLE attendance_adjustments ADD COLUMN batch_id UUID;

-- Client confirms (or disputes) a site's attendance for a month before it is invoiced.
CREATE TABLE attendance_signoffs (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    client_id UUID NOT NULL, site_id UUID NOT NULL, period_month DATE NOT NULL,
    status VARCHAR(20) NOT NULL,            -- CONFIRMED | DISPUTED
    note VARCHAR(1000),
    disputes JSONB NOT NULL DEFAULT '[]'::jsonb,   -- [{"date":"2026-11-03","note":"only 1 guard at night"}]
    duties_at_signoff NUMERIC(10,2),
    signed_by UUID NOT NULL, signed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_by UUID, resolved_at TIMESTAMPTZ, resolution_note VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, site_id, period_month),
    FOREIGN KEY (tenant_id, client_id) REFERENCES clients (tenant_id, id),
    FOREIGN KEY (tenant_id, site_id) REFERENCES sites (tenant_id, id)
);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['attendance_photos', 'attendance_signoffs'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I
            USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
            WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)$p$, t);
    END LOOP;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${appRole}') THEN
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON attendance_photos, attendance_signoffs TO %I', '${appRole}');
    END IF;
END $$;

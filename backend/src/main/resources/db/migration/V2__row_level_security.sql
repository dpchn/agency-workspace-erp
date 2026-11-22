-- Defense in depth: even if application code forgets a tenant filter, PostgreSQL
-- only returns rows of the tenant bound to the current connection
-- (set by TenantAwareDataSource via set_config('app.tenant_id', ...)).
-- The application must connect as a NON-owner role for RLS to apply; see README.
DO $$
DECLARE
    t TEXT;
    tenant_tables TEXT[] := ARRAY[
        'branches','service_types','holidays','shift_templates','employees','employee_documents',
        'clients','sites','posts','post_shift_requirements','contracts','contract_versions','contract_rates',
        'pay_rates','salary_components','deployments','shifts','assignments','attendance_events',
        'attendance_records','attendance_adjustments','leave_requests','employee_adjustments',
        'payroll_runs','payslips','payroll_lines','payroll_site_costs','number_sequences',
        'invoices','invoice_lines','receipts','receipt_allocations','incidents','audit_events','idempotency_keys'];
BEGIN
    FOREACH t IN ARRAY tenant_tables LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I
            USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
            WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)$p$, t);
    END LOOP;
END $$;

-- Grant DML to the runtime role if it exists (created by ops / docker-compose init).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${appRole}') THEN
        EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', '${appRole}');
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', '${appRole}');
        EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', '${appRole}');
    END IF;
END $$;

-- One-off repair for rosters generated before the shift-time fix (backend running with a non-UTC TZ,
-- e.g. TZ=Asia/Kolkata, generated an 08:00 shift at 13:30). Opt-in; run once after upgrading:
--
--   docker compose exec -T db psql -U postgres -d erp -v dry_run=1 < scripts/fix-shift-times.sql   # report only
--   docker compose exec -T db psql -U postgres -d erp -v dry_run=0 < scripts/fix-shift-times.sql   # apply
--
-- Only touches duties that have NOT started and have no check-in: past, attended or payroll/invoice-locked
-- duties are left exactly as they are. A shift whose move would clash with another duty of the same person
-- is skipped and reported, never forced.
\set ON_ERROR_STOP on
SELECT set_config('fix.dry_run', :'dry_run', false);

DO $$
DECLARE
    s RECORD;
    correct_start TIMESTAMPTZ;
    delta INTERVAL;
    fixed INT := 0;
    skipped INT := 0;
    dry BOOLEAN := current_setting('fix.dry_run') <> '0';
BEGIN
    FOR s IN
        SELECT sh.id, sh.duty_date, sh.start_at, st.start_time, COALESCE(si.timezone, t.timezone) AS tz
        FROM shifts sh
        JOIN shift_templates st ON st.id = sh.shift_template_id
        JOIN sites si ON si.id = sh.site_id
        JOIN tenants t ON t.id = sh.tenant_id
        WHERE sh.status <> 'CANCELLED' AND sh.kind = 'REGULAR'
          AND sh.start_at > now()
          AND NOT EXISTS (SELECT 1 FROM attendance_records r WHERE r.shift_id = sh.id
                          AND (r.check_in_at IS NOT NULL OR r.payroll_run_id IS NOT NULL OR r.invoice_id IS NOT NULL))
    LOOP
        correct_start := (s.duty_date + s.start_time) AT TIME ZONE s.tz;
        delta := correct_start - s.start_at;
        CONTINUE WHEN delta = interval '0';
        IF dry THEN
            fixed := fixed + 1;
            CONTINUE;
        END IF;
        BEGIN
            UPDATE assignments SET start_at = start_at + delta, end_at = end_at + delta, updated_at = now(), version = version + 1
             WHERE shift_id = s.id AND status IN ('PLANNED', 'CONFIRMED');
            UPDATE shifts SET start_at = start_at + delta, end_at = end_at + delta, updated_at = now(), version = version + 1
             WHERE id = s.id;
            fixed := fixed + 1;
        EXCEPTION WHEN exclusion_violation OR check_violation THEN
            skipped := skipped + 1;
            RAISE NOTICE 'Skipped shift % on % (would clash with another duty); fix it by hand in the roster', s.id, s.duty_date;
        END;
    END LOOP;
    RAISE NOTICE '% shift(s) %, % skipped', fixed, CASE WHEN dry THEN 'need fixing (dry run, nothing changed)' ELSE 'fixed' END, skipped;
END $$;

package app.erp.platform;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Typed view over tenants.settings (JSONB). Every business policy that differs between
 * agencies lives here, with safe defaults, instead of being hard-coded.
 */
public record TenantSettings(Map<String, Object> raw) {

    public static final Map<String, Object> DEFAULTS = Map.ofEntries(
            Map.entry("payDivisor", "CALENDAR_DAYS"),          // CALENDAR_DAYS | 26 | 30: monthly wage -> per-duty value
            Map.entry("payRatePolicy", "SITE_RATE"),           // SITE_RATE | HOME_RATE | HIGHER_OF when a guard works away from home site
            Map.entry("otMultiplier", 2.0),                    // OT hourly = per-duty / shift hours * multiplier, if no explicit OT rate
            Map.entry("otThresholdMinutes", 30),               // minutes beyond shift before OT counts
            Map.entry("holidayPayMultiplier", 2.0),
            Map.entry("paidLeaveTypes", List.of("CASUAL", "SICK", "EARNED")),
            Map.entry("lateGraceMinutes", 15),
            Map.entry("fullDayMinPercent", 75),                // worked % of shift for a full duty
            Map.entry("halfDayMinPercent", 40),                // worked % for a half duty
            Map.entry("autoApproveCleanAttendance", true),     // un-flagged, completed duties approve themselves
            Map.entry("autoApproveSources", List.of("MOBILE", "KIOSK")), // only these ways of marking may auto-approve;
                                                               // SUPERVISOR (proxy) and MANUAL (register) always need a second person
            Map.entry("kioskPhotoRequired", true),             // shared site device takes a photo with every punch
            Map.entry("rollCallPhotoRequired", true),          // supervisor roll-call needs a photo of the team
            Map.entry("pinMaxAttempts", 5),                    // wrong kiosk PINs before the PIN locks
            Map.entry("pinLockMinutes", 15),
            Map.entry("clientSignoffEnabled", false),          // clients confirm/dispute monthly attendance in the portal (off: the agency
                                                               // manages attendance alone; nothing waits for the client)
            Map.entry("blockInvoiceOnDispute", true),          // when sign-off is on: an open dispute blocks issuing that month's invoice
            Map.entry("checkInWindowMinutes", 120),            // how early/late a check-in may match a shift
            Map.entry("lateSubmissionHours", 6),               // offline sync older than this is flagged
            Map.entry("maxAccuracyMeters", 100),
            Map.entry("maxTravelKmph", 120),                   // impossible-travel heuristic
            Map.entry("minRestHours", 8),
            Map.entry("maxShiftsPerDay", 2),
            Map.entry("requireApprovalSeparation", true),      // maker != checker for payroll/invoice/adjustments
            Map.entry("invoicePrefix", "INV"),
            Map.entry("creditNotePrefix", "CN"),
            Map.entry("defaultGstRatePct", 18),
            Map.entry("incidentResponseMinutes", Map.of("CRITICAL", 15, "HIGH", 60, "MEDIUM", 240, "LOW", 1440)));

    private Object get(String k) {
        Object v = raw == null ? null : raw.get(k);
        return v != null ? v : DEFAULTS.get(k);
    }

    public String str(String k) { return String.valueOf(get(k)); }
    public int integer(String k) { return ((Number) get(k)).intValue(); }
    public boolean bool(String k) { return Boolean.TRUE.equals(get(k)); }
    public BigDecimal decimal(String k) { return new BigDecimal(String.valueOf(get(k))); }

    @SuppressWarnings("unchecked")
    public List<String> list(String k) { return (List<String>) get(k); }

    @SuppressWarnings("unchecked")
    public int incidentResponseMinutes(String severity) {
        Map<String, Object> m = (Map<String, Object>) get("incidentResponseMinutes");
        Object v = m.get(severity);
        return v == null ? 240 : ((Number) v).intValue();
    }
}

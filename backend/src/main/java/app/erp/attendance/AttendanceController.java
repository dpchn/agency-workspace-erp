package app.erp.attendance;

import app.erp.common.ApiException;
import app.erp.common.Idempotent;
import app.erp.security.Authz;
import app.erp.security.Perm;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/attendance")
public class AttendanceController {
    private final AttendanceService service;
    private final AttendanceRecordRepository records;
    private final AttendanceEventRepository events;
    private final AttendanceAdjustmentRepository adjustments;
    private final JdbcTemplate jdbc;

    public AttendanceController(AttendanceService service, AttendanceRecordRepository records, AttendanceEventRepository events,
                                AttendanceAdjustmentRepository adjustments, JdbcTemplate jdbc) {
        this.service = service;
        this.records = records;
        this.events = events;
        this.adjustments = adjustments;
        this.jdbc = jdbc;
    }

    /** Single check-in/out (online). Requires Idempotency-Key; clientEventId also dedups. */
    @PostMapping("/events")
    @Idempotent
    public AttendanceService.EventResult event(@RequestBody AttendanceService.EventRequest req) {
        return service.ingest(req);
    }

    /**
     * Offline sync: the device uploads its queued events in order. Each event is processed
     * independently and deduplicated by clientEventId, so retries are safe.
     */
    @PostMapping("/events/batch")
    public List<AttendanceService.EventResult> batch(@RequestBody List<AttendanceService.EventRequest> reqs) {
        if (reqs.size() > 200) throw ApiException.badRequest("Max 200 events per batch");
        List<AttendanceService.EventResult> out = new ArrayList<>();
        reqs.stream().sorted(Comparator.comparing(AttendanceService.EventRequest::occurredAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(r -> {
                    try {
                        out.add(service.ingest(r));
                    } catch (ApiException e) {
                        out.add(new AttendanceService.EventResult(r.clientEventId(), null, "REJECTED", List.of(e.getCode()), null, null,
                                null, false, e.getMessage()));
                    }
                });
        return out;
    }

    @GetMapping("/events")
    @Transactional(readOnly = true)
    public List<AttendanceEvent> listEvents(@RequestParam(required = false) UUID employeeId, @RequestParam(required = false) UUID assignmentId,
                                            @RequestParam(required = false) String status, @RequestParam(required = false) String source,
                                            @RequestParam(defaultValue = "200") int size) {
        Authz.require(Perm.ATTENDANCE_READ);
        return events.findAll((r, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (employeeId != null) p.add(cb.equal(r.get("employeeId"), employeeId));
            if (assignmentId != null) p.add(cb.equal(r.get("assignmentId"), assignmentId));
            if (status != null) p.add(cb.equal(r.get("status"), status));
            if (source != null) p.add(cb.equal(r.get("source"), source));
            var scope = Authz.user().siteIds();
            if (scope != null) {   // site-scoped users only see punches for duties at their sites
                var sq = q.subquery(UUID.class);
                var a = sq.from(app.erp.roster.Assignment.class);
                var sh = sq.from(app.erp.roster.Shift.class);
                sq.select(a.get("id")).where(cb.equal(a.get("shiftId"), sh.get("id")), sh.get("siteId").in(scope));
                p.add(r.get("assignmentId").in(sq));
            }
            return cb.and(p.toArray(Predicate[]::new));
        }, PageRequest.of(0, Math.min(size, 1000), Sort.by("occurredAt").descending())).getContent();
    }

    @GetMapping("/records")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRecords(@RequestParam LocalDate from, @RequestParam LocalDate to,
                                                 @RequestParam(required = false) UUID siteId, @RequestParam(required = false) UUID employeeId,
                                                 @RequestParam(required = false) UUID clientId, @RequestParam(required = false) String approvalStatus,
                                                 @RequestParam(required = false) Boolean flagged) {
        Authz.require(Perm.ATTENDANCE_READ);
        var u = Authz.user();
        StringBuilder sql = new StringBuilder("""
                select r.id, r.duty_date, r.status, r.approval_status, r.check_in_at, r.check_out_at, r.worked_minutes,
                       r.late_minutes, r.ot_minutes, r.pay_units, r.bill_units, r.billable, r.is_holiday, r.flags,
                       r.assignment_role, r.shift_kind, r.sources, r.payroll_run_id is not null as payroll_locked, r.invoice_id is not null as invoice_locked,
                       r.employee_id, e.employee_code, e.first_name || coalesce(' ' || e.last_name, '') as employee_name,
                       r.site_id, s.name as site_name, r.post_id, p.name as post_name, r.assignment_id, r.shift_id
                from attendance_records r join employees e on e.id = r.employee_id
                join sites s on s.id = r.site_id join posts p on p.id = r.post_id
                where r.duty_date between ? and ?""");
        List<Object> args = new ArrayList<>(List.of(from, to));
        if (siteId != null) { sql.append(" and r.site_id = ?"); args.add(siteId); }
        if (employeeId != null) { sql.append(" and r.employee_id = ?"); args.add(employeeId); }
        if (clientId != null) { sql.append(" and r.client_id = ?"); args.add(clientId); }
        if (approvalStatus != null) { sql.append(" and r.approval_status = ?"); args.add(approvalStatus); }
        if (Boolean.TRUE.equals(flagged)) sql.append(" and cardinality(r.flags) > 0");
        if (u.siteIds() != null) { sql.append(" and r.site_id = any(?)"); args.add(u.siteIds().toArray(UUID[]::new)); }
        sql.append(" order by r.duty_date, s.name, e.employee_code limit 5000");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @PostMapping("/approvals")
    @Idempotent
    public Map<String, Object> approve(@RequestBody AttendanceService.ApprovalRequest req) {
        return service.approve(req);
    }

    @PostMapping("/adjustments")
    public AttendanceAdjustment adjust(@RequestBody AttendanceService.AdjustmentRequest req) {
        return service.requestAdjustment(req);
    }

    /** Correction requests with who/where/when, so a reviewer can decide without opening each record. */
    @GetMapping("/adjustments")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAdjustments(@RequestParam(defaultValue = "PENDING") String status,
                                                     @RequestParam(required = false) UUID batchId) {
        Authz.require(Perm.ATTENDANCE_READ);
        var u = Authz.user();
        StringBuilder sql = new StringBuilder("""
                select a.id, a.record_id as "recordId", a.status, a.reason, a.proposed_status as "proposedStatus",
                       a.proposed_check_in_at as "proposedCheckInAt", a.proposed_check_out_at as "proposedCheckOutAt",
                       a.before_state as "beforeState", a.photo_id as "photoId", a.batch_id as "batchId", a.evidence_key as "evidenceKey",
                       a.requested_by as "requestedBy", ru.full_name as "requestedByName", a.created_at as "createdAt",
                       a.decided_by as "decidedBy", a.decision_note as "decisionNote",
                       r.duty_date as "dutyDate", r.site_id as "siteId", s.name as "siteName", e.employee_code as "employeeCode",
                       e.first_name || coalesce(' ' || e.last_name, '') as "employeeName", r.status as "currentStatus"
                from attendance_adjustments a join attendance_records r on r.id = a.record_id
                join employees e on e.id = r.employee_id join sites s on s.id = r.site_id
                left join users ru on ru.id = a.requested_by
                where a.status = ?""");
        List<Object> args = new ArrayList<>(List.of(status));
        if (batchId != null) { sql.append(" and a.batch_id = ?"); args.add(batchId); }
        if (u.siteIds() != null) { sql.append(" and r.site_id = any(?)"); args.add(u.siteIds().toArray(UUID[]::new)); }
        sql.append(" order by a.created_at desc limit 1000");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public record BatchDecision(List<UUID> ids, boolean approve, String note) {}

    /** Decide many correction requests at once (e.g. a whole register page). Each is independent. */
    @PostMapping("/adjustments/decisions")
    @Idempotent
    public Map<String, Object> decideMany(@RequestBody BatchDecision d) {
        Authz.require(Perm.ATTENDANCE_APPROVE);
        if (d.ids() == null || d.ids().isEmpty()) throw ApiException.field("ids", "Required");
        int done = 0;
        List<String> skipped = new ArrayList<>();
        for (UUID id : d.ids()) {
            try {
                service.decideAdjustment(id, new AttendanceService.AdjustmentDecision(d.approve(), d.note()));
                done++;
            } catch (ApiException e) {
                skipped.add(id + ": " + e.getMessage());
            }
        }
        return Map.of("updated", done, "skipped", skipped);
    }

    // ------------------------------------------------------------------ workers without a phone

    /** Shared site device (gate tablet / supervisor phone): punch by employee code + PIN, with a photo. */
    @PostMapping("/kiosk/punch")
    @Idempotent
    public AttendanceService.EventResult kioskPunch(@RequestBody AttendanceService.KioskPunch p) {
        if (p.clientEventId() == null) throw ApiException.field("clientEventId", "Device-generated UUID required");
        Authz.require(Perm.ATTENDANCE_KIOSK);
        var dup = service.duplicate(p.clientEventId());
        if (dup.isPresent()) return dup.get();
        UUID employeeId = service.verifyPin(p);
        return service.kioskPunch(p, employeeId);
    }

    /** Offline kiosk sync. PINs are checked on arrival; each punch is independent and deduplicated. */
    @PostMapping("/kiosk/punch/batch")
    public List<AttendanceService.EventResult> kioskBatch(@RequestBody List<AttendanceService.KioskPunch> ps) {
        Authz.require(Perm.ATTENDANCE_KIOSK);
        if (ps.size() > 200) throw ApiException.badRequest("Max 200 punches per batch");
        List<AttendanceService.EventResult> out = new ArrayList<>();
        ps.stream().sorted(Comparator.comparing(AttendanceService.KioskPunch::occurredAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(p -> {
                    try {
                        var dup = p.clientEventId() == null ? Optional.<AttendanceService.EventResult>empty() : service.duplicate(p.clientEventId());
                        out.add(dup.isPresent() ? dup.get() : service.kioskPunch(p, service.verifyPin(p)));
                    } catch (ApiException e) {
                        out.add(new AttendanceService.EventResult(p.clientEventId(), null, "REJECTED", List.of(e.getCode()), null, null,
                                null, false, e.getMessage()));
                    }
                });
        return out;
    }

    /** Today's duties at the device's sites, so a worker can tap their name instead of typing a code. */
    @GetMapping("/kiosk/duties")
    @Transactional(readOnly = true)
    public Map<String, Object> kioskDuties(@RequestParam(required = false) LocalDate date) {
        var u = Authz.require(Perm.ATTENDANCE_KIOSK);
        LocalDate d = date == null ? LocalDate.now() : date;
        StringBuilder sql = new StringBuilder("""
                select a.id as "assignmentId", a.employee_id as "employeeId", e.employee_code as "employeeCode",
                       e.first_name || coalesce(' ' || e.last_name, '') as "employeeName", e.profile_photo_id as "photoId",
                       e.attendance_pin_hash is not null as "pinSet", e.has_smartphone as "hasSmartphone",
                       a.start_at as "startAt", a.end_at as "endAt", a.status, si.id as "siteId", si.name as "siteName", p.name as "postName",
                       r.check_in_at as "checkInAt", r.check_out_at as "checkOutAt"
                from assignments a join shifts s on s.id = a.shift_id join sites si on si.id = s.site_id join posts p on p.id = s.post_id
                join employees e on e.id = a.employee_id left join attendance_records r on r.assignment_id = a.id
                where s.duty_date between ? and ? and a.status <> 'CANCELLED' and s.status <> 'CANCELLED'""");
        List<Object> args = new ArrayList<>(List.of(d.minusDays(1), d));
        if (u.siteIds() != null) { sql.append(" and si.id = any(?)"); args.add(u.siteIds().toArray(UUID[]::new)); }
        // ended duties stay listed for 2 hours (time to check out late), then drop off the gate screen
        sql.append(" and a.end_at > now() - interval '2 hours' order by a.start_at, e.employee_code");
        List<Map<String, Object>> sitesOut = u.siteIds() == null ? List.of()
                : jdbc.queryForList("select id, name, latitude, longitude, geofence_radius_m as \"geofenceRadiusM\" from sites where id = any(?) order by name",
                        (Object) u.siteIds().toArray(UUID[]::new));
        return Map.of("date", d, "sites", sitesOut, "duties", jdbc.queryForList(sql.toString(), args.toArray()));
    }

    /** Supervisor roll-call: one team photo + the supervisor's location marks everyone selected. */
    @PostMapping("/roll-call")
    @Idempotent
    public Map<String, Object> rollCall(@RequestBody AttendanceService.RollCallRequest r) {
        service.checkRollCall(r);
        List<AttendanceService.EventResult> results = new ArrayList<>();
        for (UUID id : new LinkedHashSet<>(r.assignmentIds())) {
            try {
                results.add(service.rollCallOne(r, id));
            } catch (ApiException e) {
                results.add(new AttendanceService.EventResult(null, null, "REJECTED", List.of(e.getCode()), id, null, null, false, e.getMessage()));
            }
        }
        long ok = results.stream().filter(x -> !"REJECTED".equals(x.status())).count();
        return Map.of("batchId", r.batchId(), "marked", ok, "rejected", results.size() - ok, "results", results);
    }

    /** Office entry from a signed paper register; creates correction requests for a second person to approve. */
    @PostMapping("/register-entry")
    @Idempotent
    public Map<String, Object> registerEntry(@RequestBody AttendanceService.RegisterRequest r) {
        return service.registerEntry(r);
    }

    /** Duties at a site on a date, with their current attendance: the sheet for roll-call and register entry. */
    @GetMapping("/sheet")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> sheet(@RequestParam UUID siteId, @RequestParam LocalDate date) {
        Authz.require(Perm.ATTENDANCE_READ);
        Authz.requireSite(siteId, "Site");
        return jdbc.queryForList("""
                select a.id as "assignmentId", a.employee_id as "employeeId", e.employee_code as "employeeCode",
                       e.first_name || coalesce(' ' || e.last_name, '') as "employeeName", e.has_smartphone as "hasSmartphone",
                       e.profile_photo_id as "photoId", st.name as "serviceType", p.name as "postName", a.role,
                       a.start_at as "startAt", a.end_at as "endAt", a.status as "assignmentStatus",
                       r.id as "recordId", r.status, r.approval_status as "approvalStatus", r.check_in_at as "checkInAt",
                       r.check_out_at as "checkOutAt", r.sources, r.flags,
                       exists (select 1 from attendance_adjustments x where x.record_id = r.id and x.status = 'PENDING') as "pendingCorrection"
                from assignments a join shifts s on s.id = a.shift_id join posts p on p.id = s.post_id
                join employees e on e.id = a.employee_id join service_types st on st.id = s.service_type_id
                left join attendance_records r on r.assignment_id = a.id
                where s.site_id = ? and s.duty_date = ? and a.status <> 'CANCELLED' and s.status <> 'CANCELLED'
                order by a.start_at, e.employee_code""", siteId, date);
    }

    @PostMapping("/adjustments/{id}/decision")
    @Idempotent
    public AttendanceAdjustment decide(@PathVariable UUID id, @RequestBody AttendanceService.AdjustmentDecision d) {
        return service.decideAdjustment(id, d);
    }

    public record FinalizeRequest(Instant cutoff) {}

    /** Close out ended duties now (the scheduler also does this every 30 minutes). */
    @PostMapping("/finalize")
    public AttendanceService.FinalizeResult finalizeNow(@RequestBody(required = false) FinalizeRequest r) {
        Authz.require(Perm.ATTENDANCE_APPROVE);
        Instant cutoff = r == null || r.cutoff() == null ? Instant.now().minusSeconds(2 * 3600) : r.cutoff();
        return service.finalizeEnded(cutoff);
    }

    /** Muster roll: employee x day grid of status codes for a month. */
    @GetMapping("/muster")
    @Transactional(readOnly = true)
    public Map<String, Object> muster(@RequestParam String month, @RequestParam(required = false) UUID siteId) {
        Authz.require(Perm.ATTENDANCE_READ);
        YearMonth ym = YearMonth.parse(month);
        var u = Authz.user();
        StringBuilder sql = new StringBuilder("""
                select r.employee_id, e.employee_code, e.first_name || coalesce(' ' || e.last_name, '') as name,
                       extract(day from r.duty_date)::int as day, r.status, r.pay_units, r.approval_status, s.code as site_code
                from attendance_records r join employees e on e.id = r.employee_id join sites s on s.id = r.site_id
                where r.duty_date between ? and ?""");
        List<Object> args = new ArrayList<>(List.of(ym.atDay(1), ym.atEndOfMonth()));
        if (siteId != null) { sql.append(" and r.site_id = ?"); args.add(siteId); }
        if (u.siteIds() != null) { sql.append(" and r.site_id = any(?)"); args.add(u.siteIds().toArray(UUID[]::new)); }
        sql.append(" order by e.employee_code, r.duty_date");
        Map<UUID, Map<String, Object>> rows = new LinkedHashMap<>();
        for (var row : jdbc.queryForList(sql.toString(), args.toArray())) {
            var emp = rows.computeIfAbsent((UUID) row.get("employee_id"), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("employeeId", k);
                m.put("code", row.get("employee_code"));
                m.put("name", row.get("name"));
                m.put("days", new TreeMap<Integer, List<String>>());
                m.put("units", java.math.BigDecimal.ZERO);
                return m;
            });
            @SuppressWarnings("unchecked") var days = (TreeMap<Integer, List<String>>) emp.get("days");
            String code = switch ((String) row.get("status")) {
                case "PRESENT" -> "P"; case "HALF_DAY" -> "H"; case "ABSENT" -> "A"; case "ON_LEAVE" -> "L"; default -> "?";
            };
            days.computeIfAbsent((Integer) row.get("day"), k -> new ArrayList<>()).add(code + "@" + row.get("site_code"));
            emp.put("units", ((java.math.BigDecimal) emp.get("units")).add((java.math.BigDecimal) row.get("pay_units")));
        }
        return Map.of("month", month, "daysInMonth", ym.lengthOfMonth(), "rows", rows.values());
    }
}

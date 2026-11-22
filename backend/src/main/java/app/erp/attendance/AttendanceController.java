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
                                            @RequestParam(required = false) String status, @RequestParam(defaultValue = "200") int size) {
        Authz.require(Perm.ATTENDANCE_READ);
        return events.findAll((r, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (employeeId != null) p.add(cb.equal(r.get("employeeId"), employeeId));
            if (assignmentId != null) p.add(cb.equal(r.get("assignmentId"), assignmentId));
            if (status != null) p.add(cb.equal(r.get("status"), status));
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
                       r.assignment_role, r.shift_kind, r.payroll_run_id is not null as payroll_locked, r.invoice_id is not null as invoice_locked,
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

    @GetMapping("/adjustments")
    @Transactional(readOnly = true)
    public List<AttendanceAdjustment> listAdjustments(@RequestParam(defaultValue = "PENDING") String status) {
        Authz.require(Perm.ATTENDANCE_READ);
        return adjustments.findAll((r, q, cb) -> cb.equal(r.get("status"), status), Sort.by("createdAt").descending());
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

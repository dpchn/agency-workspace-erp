package app.erp.attendance;

import app.erp.client.Site;
import app.erp.client.SiteRepository;
import app.erp.common.ApiException;
import app.erp.common.AuditService;
import app.erp.common.OutboxService;
import app.erp.platform.TenantService;
import app.erp.platform.TenantSettings;
import app.erp.roster.*;
import app.erp.security.Authz;
import app.erp.security.Perm;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Raw events are append-only evidence; the attendance record (one per assignment) is a projection
 * that payroll and billing consume. Suspicious events are FLAGGED for human review, never auto-penalised.
 */
@Service
public class AttendanceService {
    private final AttendanceEventRepository events;
    private final AttendanceRecordRepository records;
    private final AttendanceAdjustmentRepository adjustments;
    private final AssignmentRepository assignments;
    private final ShiftRepository shifts;
    private final SiteRepository sites;
    private final RosterService roster;
    private final TenantService tenantService;
    private final AuditService audit;
    private final OutboxService outbox;
    private final JdbcTemplate jdbc;

    public AttendanceService(AttendanceEventRepository events, AttendanceRecordRepository records,
                             AttendanceAdjustmentRepository adjustments, AssignmentRepository assignments, ShiftRepository shifts,
                             SiteRepository sites, RosterService roster, TenantService tenantService, AuditService audit,
                             OutboxService outbox, JdbcTemplate jdbc) {
        this.events = events;
        this.records = records;
        this.adjustments = adjustments;
        this.assignments = assignments;
        this.shifts = shifts;
        this.sites = sites;
        this.roster = roster;
        this.tenantService = tenantService;
        this.audit = audit;
        this.outbox = outbox;
        this.jdbc = jdbc;
    }

    public record EventRequest(UUID clientEventId, UUID employeeId, UUID assignmentId, String kind, Instant occurredAt,
                               Double latitude, Double longitude, Double accuracyM, String deviceId, String selfieHash,
                               Boolean offline) {}

    public record EventResult(UUID clientEventId, UUID eventId, String status, List<String> flags, UUID assignmentId,
                              UUID recordId, String recordStatus, boolean duplicate, String message) {}

    // ------------------------------------------------------------------ ingest

    @Transactional
    public EventResult ingest(EventRequest req) {
        var u = Authz.user();
        if (req.clientEventId() == null) throw ApiException.field("clientEventId", "Device-generated UUID required");
        if (!Set.of("CHECK_IN", "CHECK_OUT").contains(String.valueOf(req.kind()))) throw ApiException.field("kind", "CHECK_IN or CHECK_OUT");
        if (req.occurredAt() == null) throw ApiException.field("occurredAt", "Required");

        Optional<AttendanceEvent> dup = events.findByClientEventId(req.clientEventId());
        if (dup.isPresent()) {
            AttendanceEvent e = dup.get();
            AttendanceRecord r = e.getAssignmentId() == null ? null : records.findByAssignmentId(e.getAssignmentId()).orElse(null);
            return new EventResult(e.getClientEventId(), e.getId(), e.getStatus(), List.of(e.getFlags()), e.getAssignmentId(),
                    r == null ? null : r.getId(), r == null ? null : r.getStatus(), true, "Already received");
        }

        // Who: guards can only mark themselves; supervisors may mark others (source SUPERVISOR).
        UUID employeeId;
        String source;
        if (req.employeeId() == null || req.employeeId().equals(u.employeeId())) {
            if (u.employeeId() == null) throw ApiException.field("employeeId", "Required");
            Authz.requireAny(Perm.ATTENDANCE_SELF, Perm.ATTENDANCE_MARK);
            employeeId = u.employeeId();
            source = "MOBILE";
        } else {
            Authz.require(Perm.ATTENDANCE_MARK);
            employeeId = req.employeeId();
            source = "SUPERVISOR";
        }

        TenantSettings st = tenantService.settings();
        Instant received = Instant.now();
        List<String> flags = new ArrayList<>();
        if (req.occurredAt().isAfter(received.plus(Duration.ofMinutes(5)))) flags.add("CLOCK_SKEW");
        if (Duration.between(req.occurredAt(), received).toHours() >= st.integer("lateSubmissionHours")) flags.add("LATE_SUBMISSION");
        if (req.accuracyM() != null && req.accuracyM() > st.integer("maxAccuracyMeters")) flags.add("LOW_ACCURACY");
        if (req.latitude() == null && "MOBILE".equals(source)) flags.add("NO_LOCATION");

        Assignment a = req.assignmentId() != null ? assignments.findById(req.assignmentId()).orElse(null) : match(employeeId, req.kind(), req.occurredAt(), st);
        if (a != null && !a.getEmployeeId().equals(employeeId)) throw ApiException.notFound("Assignment");
        if (a != null && Set.of("CANCELLED").contains(a.getStatus())) a = null;

        AttendanceEvent ev = new AttendanceEvent();
        ev.setClientEventId(req.clientEventId());
        ev.setEmployeeId(employeeId);
        ev.setKind(req.kind());
        ev.setOccurredAt(req.occurredAt());
        ev.setReceivedAt(received);
        ev.setLatitude(req.latitude());
        ev.setLongitude(req.longitude());
        ev.setAccuracyM(req.accuracyM());
        ev.setDeviceId(req.deviceId());
        ev.setSelfieHash(req.selfieHash());
        ev.setOffline(Boolean.TRUE.equals(req.offline()));
        ev.setSource(source);
        ev.setRecordedBy(u.userId());

        String status = "ACCEPTED";
        String message = null;
        Shift shift = null;
        Site site = null;
        if (a == null) {
            flags.add("NO_ASSIGNMENT");
            status = "FLAGGED";
            message = "No rostered duty found for this time; supervisor will review";
        } else {
            shift = shifts.findById(a.getShiftId()).orElseThrow();
            site = sites.findById(shift.getSiteId()).orElseThrow();
            ev.setAssignmentId(a.getId());
            if (req.latitude() != null && site.getLatitude() != null && !"OFF".equals(site.getGeofencePolicy())) {
                double dist = Geo.distanceMeters(req.latitude(), req.longitude(), site.getLatitude(), site.getLongitude());
                ev.setDistanceM(Math.round(dist * 10) / 10.0);
                double tolerance = site.getGeofenceRadiusM() + (req.accuracyM() == null ? 0 : Math.min(req.accuracyM(), 50));
                if (dist > tolerance) {
                    flags.add("OUTSIDE_GEOFENCE");
                    if ("ENFORCE".equals(site.getGeofencePolicy()) && "MOBILE".equals(source)) {
                        status = "REJECTED";
                        message = "You are " + Math.round(dist) + " m from the site; move inside the site boundary and retry";
                    }
                }
            } else if ("ENFORCE".equals(site.getGeofencePolicy()) && req.latitude() == null && "MOBILE".equals(source)) {
                status = "REJECTED";
                message = "Location is required at this site";
            }
            if (req.deviceId() != null) {
                Integer others = jdbc.queryForObject("""
                        select count(distinct employee_id) from attendance_events
                        where device_id = ? and employee_id <> ? and occurred_at between ? and ?""", Integer.class,
                        req.deviceId(), employeeId, java.sql.Timestamp.from(req.occurredAt().minus(Duration.ofHours(12))),
                        java.sql.Timestamp.from(req.occurredAt().plus(Duration.ofHours(12))));
                if (others != null && others > 0 && "MOBILE".equals(source)) flags.add("DEVICE_SHARED");
            }
            impossibleTravel(employeeId, req, st).ifPresent(flags::add);
        }
        if ("ACCEPTED".equals(status) && !flags.isEmpty()) status = "FLAGGED";
        ev.setFlags(flags.toArray(String[]::new));
        ev.setStatus(status);
        events.saveAndFlush(ev);

        AttendanceRecord rec = null;
        if (a != null && !"REJECTED".equals(status)) {
            rec = recordFor(a, shift);
            if (rec.getPayrollRunId() != null || rec.getInvoiceId() != null) {
                ev.setStatus("FLAGGED");
                ev.setFlags(append(ev.getFlags(), "LOCKED_PERIOD"));
                message = "Period is locked; raise an adjustment";
            } else {
                if ("CHECK_IN".equals(req.kind())) {
                    if (rec.getCheckInAt() == null || req.occurredAt().isBefore(rec.getCheckInAt())) rec.setCheckInAt(req.occurredAt());
                    if ("PLANNED".equals(a.getStatus()) || "NO_SHOW".equals(a.getStatus())) a.setStatus("CONFIRMED");
                } else {
                    if (rec.getCheckOutAt() == null || req.occurredAt().isAfter(rec.getCheckOutAt())) rec.setCheckOutAt(req.occurredAt());
                    if (rec.getCheckInAt() != null) a.setStatus("COMPLETED");
                    else flags.add("CHECKOUT_WITHOUT_CHECKIN");
                }
                rec.setFlags(merge(rec.getFlags(), flags));
                recompute(rec, a, shift, st);
                records.saveAndFlush(rec);
                roster.refreshShiftStatus(shift.getId());
            }
        }
        return new EventResult(ev.getClientEventId(), ev.getId(), ev.getStatus(), List.of(ev.getFlags()), ev.getAssignmentId(),
                rec == null ? null : rec.getId(), rec == null ? null : rec.getStatus(), false, message);
    }

    /** Finds the duty an event belongs to: overlapping window first, then nearest start within the allowed window. */
    private Assignment match(UUID employeeId, String kind, Instant at, TenantSettings st) {
        Duration window = Duration.ofMinutes(st.integer("checkInWindowMinutes"));
        List<Assignment> cands = assignments.activeBetween(employeeId, at.minus(window), at.plus(window));
        if (cands.isEmpty()) {
            cands = assignments.findAll((r, q, cb) -> cb.and(cb.equal(r.get("employeeId"), employeeId), cb.equal(r.get("status"), "NO_SHOW"),
                    cb.lessThan(r.get("startAt"), at.plus(window)), cb.greaterThan(r.get("endAt"), at.minus(window))));
        }
        if (cands.isEmpty()) return null;
        if ("CHECK_OUT".equals(kind)) {
            for (Assignment a : cands) {
                var rec = records.findByAssignmentId(a.getId());
                if (rec.isPresent() && rec.get().getCheckInAt() != null && rec.get().getCheckOutAt() == null) return a;
            }
            return cands.stream().min(Comparator.comparing(a -> Duration.between(a.getEndAt(), at).abs())).orElse(null);
        }
        return cands.stream().min(Comparator.comparing(a -> Duration.between(a.getStartAt(), at).abs())).orElse(null);
    }

    private Optional<String> impossibleTravel(UUID employeeId, EventRequest req, TenantSettings st) {
        if (req.latitude() == null) return Optional.empty();
        var prev = jdbc.query("""
                select latitude, longitude, occurred_at from attendance_events
                where employee_id = ? and latitude is not null and occurred_at < ? and status <> 'REJECTED'
                order by occurred_at desc limit 1""",
                (rs, i) -> new double[]{rs.getDouble(1), rs.getDouble(2), rs.getTimestamp(3).toInstant().toEpochMilli()},
                employeeId, java.sql.Timestamp.from(req.occurredAt()));
        if (prev.isEmpty()) return Optional.empty();
        double[] p = prev.get(0);
        double km = Geo.distanceMeters(p[0], p[1], req.latitude(), req.longitude()) / 1000.0;
        double hours = Math.max((req.occurredAt().toEpochMilli() - (long) p[2]) / 3_600_000.0, 1.0 / 60);
        return km > 1 && km / hours > st.integer("maxTravelKmph") ? Optional.of("IMPOSSIBLE_TRAVEL") : Optional.empty();
    }

    // ------------------------------------------------------------------ projection

    AttendanceRecord recordFor(Assignment a, Shift s) {
        return records.findByAssignmentId(a.getId()).orElseGet(() -> {
            AttendanceRecord r = new AttendanceRecord();
            Site site = sites.findById(s.getSiteId()).orElseThrow();
            r.setAssignmentId(a.getId());
            r.setEmployeeId(a.getEmployeeId());
            r.setShiftId(s.getId());
            r.setSiteId(s.getSiteId());
            r.setPostId(s.getPostId());
            r.setClientId(site.getClientId());
            r.setServiceTypeId(s.getServiceTypeId());
            r.setDutyDate(s.getDutyDate());
            r.setAssignmentRole(a.getRole());
            r.setShiftKind(s.getKind());
            r.setBillable(!"EXTRA".equals(s.getKind()) || Boolean.TRUE.equals(s.getClientRequested()));
            Integer hol = jdbc.queryForObject("""
                    select count(*) from holidays h where h.holiday_date = ?
                      and (h.branch_id is null or h.branch_id = (select branch_id from sites where id = ?))""", Integer.class, s.getDutyDate(), s.getSiteId());
            r.setIsHoliday(hol != null && hol > 0);
            return r;
        });
    }

    /** Derives worked/late/OT minutes, status and pay/bill units from check-in/out and tenant policy. */
    void recompute(AttendanceRecord r, Assignment a, Shift s, TenantSettings st) {
        long planned = Duration.between(a.getStartAt(), a.getEndAt()).toMinutes();
        if (r.getCheckInAt() != null) {
            long late = Duration.between(a.getStartAt(), r.getCheckInAt()).toMinutes();
            r.setLateMinutes(late > st.integer("lateGraceMinutes") ? (int) late : 0);
        }
        if (r.getCheckInAt() == null || r.getCheckOutAt() == null) {
            if (!"ABSENT".equals(r.getStatus()) && !"ON_LEAVE".equals(r.getStatus())) r.setStatus("PENDING");
            r.setPayUnits(BigDecimal.ZERO);
            r.setBillUnits(BigDecimal.ZERO);
            return;
        }
        long worked = Math.max(0, Duration.between(r.getCheckInAt(), r.getCheckOutAt()).toMinutes());
        r.setWorkedMinutes((int) worked);
        long extra = worked - planned;
        r.setOtMinutes(extra > st.integer("otThresholdMinutes") ? (int) extra : 0);
        double pct = planned == 0 ? 0 : worked * 100.0 / planned;
        // Handover splits are judged against the person's own (shorter) window, so both halves count fully
        // for pay; billing counts the slot once (see bill units below).
        if (pct >= st.integer("fullDayMinPercent")) {
            r.setStatus("PRESENT");
            r.setPayUnits(BigDecimal.ONE);
        } else if (pct >= st.integer("halfDayMinPercent")) {
            r.setStatus("HALF_DAY");
            r.setPayUnits(new BigDecimal("0.5"));
        } else {
            r.setStatus("ABSENT");
            r.setPayUnits(BigDecimal.ZERO);
        }
        long shiftMinutes = Duration.between(s.getStartAt(), s.getEndAt()).toMinutes();
        BigDecimal share = shiftMinutes == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(Math.min(planned, shiftMinutes)).divide(BigDecimal.valueOf(shiftMinutes), 2, java.math.RoundingMode.HALF_UP);
        // bill the fraction of the shift this person covered (a split shift bills 1 slot in total)
        r.setBillUnits(Boolean.TRUE.equals(r.getBillable()) && r.getPayUnits().signum() > 0
                ? share.multiply(r.getPayUnits()).setScale(2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO);
        if (st.bool("autoApproveCleanAttendance") && (r.getFlags() == null || r.getFlags().length == 0)
                && "PENDING".equals(r.getApprovalStatus()) && r.getLateMinutes() == 0) {
            r.setApprovalStatus("APPROVED");
            r.setApprovedAt(Instant.now());
            outbox.publish(OutboxService.ATTENDANCE_APPROVED, r.getAssignmentId(), Map.of("employeeId", r.getEmployeeId().toString()));
        }
    }

    // ------------------------------------------------------------------ day close: absences & missing check-outs

    public record FinalizeResult(int absentMarked, int missingCheckout) {}

    /** Marks duties that ended (plus grace) without check-in as NO_SHOW/ABSENT and flags missing check-outs. */
    @Transactional
    public FinalizeResult finalizeEnded(Instant cutoff) {
        TenantSettings st = tenantService.settings();
        List<Assignment> ended = assignments.findAll((r, q, cb) -> cb.and(r.get("status").in("PLANNED", "CONFIRMED"),
                cb.lessThan(r.get("endAt"), cutoff)));
        int absent = 0, missing = 0;
        for (Assignment a : ended) {
            Shift s = shifts.findById(a.getShiftId()).orElseThrow();
            if ("CANCELLED".equals(s.getStatus())) continue;
            AttendanceRecord rec = recordFor(a, s);
            if (rec.getPayrollRunId() != null) continue;
            if (rec.getCheckInAt() == null) {
                a.setStatus("NO_SHOW");
                rec.setStatus("ABSENT");
                rec.setPayUnits(BigDecimal.ZERO);
                rec.setBillUnits(BigDecimal.ZERO);
                rec.setFlags(merge(rec.getFlags(), List.of("NO_SHOW")));
                absent++;
            } else {
                // Provisionally close at planned end; supervisor must confirm.
                rec.setCheckOutAt(a.getEndAt());
                rec.setFlags(merge(rec.getFlags(), List.of("MISSING_CHECKOUT")));
                a.setStatus("COMPLETED");
                recompute(rec, a, s, st);
                missing++;
            }
            records.save(rec);
            roster.refreshShiftStatus(s.getId());
        }
        return new FinalizeResult(absent, missing);
    }

    // ------------------------------------------------------------------ approvals & adjustments

    public record ApprovalRequest(List<UUID> recordIds, String decision, String note) {}

    @Transactional
    public Map<String, Object> approve(ApprovalRequest req) {
        Authz.require(Perm.ATTENDANCE_APPROVE);
        if (!Set.of("APPROVED", "REJECTED").contains(req.decision())) throw ApiException.field("decision", "APPROVED or REJECTED");
        if (req.recordIds() == null || req.recordIds().isEmpty()) throw ApiException.field("recordIds", "Required");
        int done = 0;
        List<String> skipped = new ArrayList<>();
        for (UUID id : req.recordIds()) {
            AttendanceRecord r = records.findById(id).orElse(null);
            if (r == null || !Authz.user().siteAllowed(r.getSiteId())) { skipped.add(id + ": not found"); continue; }
            if (r.getPayrollRunId() != null || r.getInvoiceId() != null) { skipped.add(id + ": locked"); continue; }
            if ("PENDING".equals(r.getStatus())) { skipped.add(id + ": duty not complete"); continue; }
            r.setApprovalStatus(req.decision());
            r.setApprovedBy(Authz.userId());
            r.setApprovedAt(Instant.now());
            if ("APPROVED".equals(req.decision()))
                outbox.publish(OutboxService.ATTENDANCE_APPROVED, r.getAssignmentId(), Map.of("employeeId", r.getEmployeeId().toString()));
            done++;
        }
        audit.record("ATTENDANCE_" + req.decision(), "AttendanceRecord", null, Map.of("count", done, "note", String.valueOf(req.note())));
        return Map.of("updated", done, "skipped", skipped);
    }

    public record AdjustmentRequest(UUID recordId, UUID assignmentId, Instant checkInAt, Instant checkOutAt, String status,
                                    String reason, String evidenceKey) {}

    /** Correction request (forgot phone, GPS failure, wrong status). Original events are never touched. */
    @Transactional
    public AttendanceAdjustment requestAdjustment(AdjustmentRequest req) {
        Authz.require(Perm.ATTENDANCE_ADJUST);
        if (req.reason() == null || req.reason().isBlank()) throw ApiException.field("reason", "Reason required");
        if (req.status() != null && !Set.of("PRESENT", "HALF_DAY", "ABSENT", "ON_LEAVE").contains(req.status()))
            throw ApiException.field("status", "Invalid status");
        AttendanceRecord rec;
        if (req.recordId() != null) {
            rec = records.findById(req.recordId()).orElseThrow(() -> ApiException.notFound("Attendance record"));
        } else if (req.assignmentId() != null) {
            Assignment a = assignments.findById(req.assignmentId()).orElseThrow(() -> ApiException.notFound("Assignment"));
            Shift s = shifts.findById(a.getShiftId()).orElseThrow();
            rec = records.saveAndFlush(recordFor(a, s));
        } else throw ApiException.field("recordId", "recordId or assignmentId required");
        Authz.requireSite(rec.getSiteId(), "Attendance record");
        if (rec.getPayrollRunId() != null || rec.getInvoiceId() != null)
            throw ApiException.invalidState("Period is locked by payroll/invoice; correct via next period adjustment");
        if (req.checkInAt() != null && req.checkOutAt() != null && !req.checkOutAt().isAfter(req.checkInAt()))
            throw ApiException.field("checkOutAt", "Must be after check-in");
        AttendanceAdjustment adj = new AttendanceAdjustment();
        adj.setRecordId(rec.getId());
        adj.setProposedCheckInAt(req.checkInAt());
        adj.setProposedCheckOutAt(req.checkOutAt());
        adj.setProposedStatus(req.status());
        adj.setReason(req.reason());
        adj.setEvidenceKey(req.evidenceKey());
        adj.setRequestedBy(Authz.userId());
        Map<String, Object> before = new HashMap<>();
        before.put("checkInAt", String.valueOf(rec.getCheckInAt()));
        before.put("checkOutAt", String.valueOf(rec.getCheckOutAt()));
        before.put("status", rec.getStatus());
        before.put("approvalStatus", rec.getApprovalStatus());
        adj.setBeforeState(before);
        adjustments.saveAndFlush(adj);
        audit.record("ATTENDANCE_ADJUST_REQUEST", "AttendanceAdjustment", adj.getId(), Map.of("recordId", rec.getId()));
        return adj;
    }

    public record AdjustmentDecision(boolean approve, String note) {}

    @Transactional
    public AttendanceAdjustment decideAdjustment(UUID id, AdjustmentDecision d) {
        Authz.require(Perm.ATTENDANCE_APPROVE);
        AttendanceAdjustment adj = adjustments.findById(id).orElseThrow(() -> ApiException.notFound("Adjustment"));
        if (!"PENDING".equals(adj.getStatus())) throw ApiException.invalidState("Already decided");
        TenantSettings st = tenantService.settings();
        if (st.bool("requireApprovalSeparation") && adj.getRequestedBy().equals(Authz.userId()))
            throw ApiException.forbidden("Requester cannot approve their own adjustment");
        AttendanceRecord rec = records.findById(adj.getRecordId()).orElseThrow();
        Authz.requireSite(rec.getSiteId(), "Attendance record");
        adj.setDecidedBy(Authz.userId());
        adj.setDecidedAt(Instant.now());
        adj.setDecisionNote(d.note());
        if (!d.approve()) {
            adj.setStatus("REJECTED");
            return adj;
        }
        if (rec.getPayrollRunId() != null || rec.getInvoiceId() != null) throw ApiException.invalidState("Period is locked");
        Assignment a = assignments.findById(rec.getAssignmentId()).orElseThrow();
        Shift s = shifts.findById(rec.getShiftId()).orElseThrow();
        if (adj.getProposedCheckInAt() != null) rec.setCheckInAt(adj.getProposedCheckInAt());
        if (adj.getProposedCheckOutAt() != null) rec.setCheckOutAt(adj.getProposedCheckOutAt());
        rec.setFlags(merge(rec.getFlags(), List.of("ADJUSTED")));
        rec.setApprovalStatus("PENDING");
        recompute(rec, a, s, st);
        if (adj.getProposedStatus() != null) {
            rec.setStatus(adj.getProposedStatus());
            BigDecimal units = switch (adj.getProposedStatus()) {
                case "PRESENT" -> BigDecimal.ONE;
                case "HALF_DAY" -> new BigDecimal("0.5");
                default -> BigDecimal.ZERO;
            };
            rec.setPayUnits(units);
            rec.setBillUnits(Boolean.TRUE.equals(rec.getBillable()) ? units : BigDecimal.ZERO);
        }
        if (rec.getPayUnits().signum() > 0 && Set.of("NO_SHOW", "PLANNED", "CONFIRMED").contains(a.getStatus())) a.setStatus("COMPLETED");
        if (rec.getPayUnits().signum() == 0 && "ABSENT".equals(rec.getStatus())) a.setStatus("NO_SHOW");
        rec.setApprovalStatus("APPROVED");
        rec.setApprovedBy(Authz.userId());
        rec.setApprovedAt(Instant.now());
        adj.setStatus("APPROVED");
        roster.refreshShiftStatus(s.getId());
        audit.record("ATTENDANCE_ADJUST_APPROVE", "AttendanceRecord", rec.getId(), adj.getBeforeState(),
                Map.of("checkInAt", String.valueOf(rec.getCheckInAt()), "checkOutAt", String.valueOf(rec.getCheckOutAt()), "status", rec.getStatus()),
                Map.of("adjustmentId", adj.getId()));
        return adj;
    }

    static String[] merge(String[] existing, List<String> add) {
        LinkedHashSet<String> s = new LinkedHashSet<>(existing == null ? List.of() : List.of(existing));
        s.addAll(add);
        return s.toArray(String[]::new);
    }

    private static String[] append(String[] arr, String v) { return merge(arr, List.of(v)); }
}

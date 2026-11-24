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
import app.erp.hr.Employee;
import app.erp.hr.EmployeeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    @Autowired private EmployeeRepository employees;
    @Autowired private PasswordEncoder encoder;

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
                               Boolean offline, UUID photoId) {}

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

        return record(req, employeeId, source, null);
    }

    /**
     * Stores one punch for a resolved employee. {@code source} says how it was evidenced:
     * MOBILE (own phone), KIOSK (shared site device + PIN), SUPERVISOR (marked by a supervisor).
     * Site-scoped markers can only mark duties at their own sites.
     */
    EventResult record(EventRequest req, UUID employeeId, String source, UUID batchId) {
        var u = Authz.user();
        TenantSettings st = tenantService.settings();
        Instant received = Instant.now();
        List<String> flags = new ArrayList<>();
        boolean selfMarked = "MOBILE".equals(source);
        if (req.occurredAt().isAfter(received.plus(Duration.ofMinutes(5)))) flags.add("CLOCK_SKEW");
        if (Duration.between(req.occurredAt(), received).toHours() >= st.integer("lateSubmissionHours")) flags.add("LATE_SUBMISSION");
        if (req.accuracyM() != null && req.accuracyM() > st.integer("maxAccuracyMeters")) flags.add("LOW_ACCURACY");
        if (req.latitude() == null && selfMarked) flags.add("NO_LOCATION");
        if (req.photoId() != null && !photoExists(req.photoId())) throw ApiException.field("photoId", "Unknown photo");

        Assignment a = req.assignmentId() != null ? assignments.findById(req.assignmentId()).orElse(null) : match(employeeId, req.kind(), req.occurredAt(), st);
        if (a != null && !a.getEmployeeId().equals(employeeId)) throw ApiException.notFound("Assignment");
        if (a != null && Set.of("CANCELLED").contains(a.getStatus())) a = null;
        if (a != null && !selfMarked) {
            Shift sh = shifts.findById(a.getShiftId()).orElseThrow();
            if (!u.siteAllowed(sh.getSiteId())) {
                if (req.assignmentId() != null) throw ApiException.notFound("Assignment");
                a = null;
                flags.add("WRONG_SITE");
            }
        }

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
        ev.setPhotoId(req.photoId());
        ev.setBatchId(batchId);

        String status = "ACCEPTED";
        String message = null;
        Shift shift = null;
        Site site = null;
        if (a == null) {
            if (!flags.contains("WRONG_SITE")) flags.add("NO_ASSIGNMENT");
            status = "FLAGGED";
            message = flags.contains("WRONG_SITE") ? "This person has no duty at this site now; supervisor will review"
                    : "No rostered duty found for this time; supervisor will review";
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
                    if ("ENFORCE".equals(site.getGeofencePolicy()) && (selfMarked || "KIOSK".equals(source))) {
                        status = "REJECTED";
                        message = "You are " + Math.round(dist) + " m from the site; move inside the site boundary and retry";
                    }
                }
            } else if ("ENFORCE".equals(site.getGeofencePolicy()) && req.latitude() == null && selfMarked) {
                status = "REJECTED";
                message = "Location is required at this site";
            }
            if (req.deviceId() != null) {
                Integer others = jdbc.queryForObject("""
                        select count(distinct employee_id) from attendance_events
                        where device_id = ? and employee_id <> ? and occurred_at between ? and ?""", Integer.class,
                        req.deviceId(), employeeId, java.sql.Timestamp.from(req.occurredAt().minus(Duration.ofHours(12))),
                        java.sql.Timestamp.from(req.occurredAt().plus(Duration.ofHours(12))));
                if (others != null && others > 0 && selfMarked) flags.add("DEVICE_SHARED");
            }
            impossibleTravel(employeeId, req, st).ifPresent(flags::add);
        }
        // A punch naming a specific duty (roll-call, single mark) must fall inside that duty's time window,
        // so nobody is marked present hours before their shift starts.
        if (a != null && req.assignmentId() != null && !"REJECTED".equals(status)) {
            Duration w = Duration.ofMinutes(st.integer("checkInWindowMinutes"));
            boolean in = "CHECK_IN".equals(req.kind())
                    ? !req.occurredAt().isBefore(a.getStartAt().minus(w)) && req.occurredAt().isBefore(a.getEndAt())
                    : req.occurredAt().isAfter(a.getStartAt()) && !req.occurredAt().isAfter(a.getEndAt().plus(w));
            if (!in) {
                flags.add("OUTSIDE_SHIFT_TIME");
                status = "REJECTED";
                java.time.format.DateTimeFormatter hm = java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(tenantService.zone());
                message = "Not this duty's time: it runs " + hm.format(a.getStartAt()) + " to " + hm.format(a.getEndAt());
            }
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
                rec.setSources(merge(rec.getSources(), List.of(source)));
                rec.setMarkedBy(mergeIds(rec.getMarkedBy(), u.userId()));
                recompute(rec, a, shift, st);
                records.saveAndFlush(rec);
                roster.refreshShiftStatus(shift.getId());
            }
        }
        return new EventResult(ev.getClientEventId(), ev.getId(), ev.getStatus(), List.of(ev.getFlags()), ev.getAssignmentId(),
                rec == null ? null : rec.getId(), rec == null ? null : rec.getStatus(), false, message);
    }

    // ------------------------------------------------------------------ workers without a phone

    public record KioskPunch(UUID clientEventId, String employeeCode, UUID employeeId, String pin, String kind, Instant occurredAt,
                             Double latitude, Double longitude, Double accuracyM, String deviceId, UUID photoId, Boolean offline) {}

    /**
     * Step 1 of a kiosk punch: checks the worker's PIN. Runs in its own transaction that is NOT rolled
     * back on a wrong PIN, so failed attempts are counted and the PIN locks after too many.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public UUID verifyPin(KioskPunch p) {
        Authz.require(Perm.ATTENDANCE_KIOSK);
        TenantSettings st = tenantService.settings();
        Employee e = null;
        if (p.employeeId() != null) e = employees.findById(p.employeeId()).orElse(null);
        else if (p.employeeCode() != null && !p.employeeCode().isBlank())
            e = employees.findOne((r, q, cb) -> cb.equal(cb.upper(r.get("employeeCode")), p.employeeCode().trim().toUpperCase())).orElse(null);
        if (e == null) throw ApiException.field("employeeCode", "Unknown employee code");
        if (!"ACTIVE".equals(e.getStatus())) throw ApiException.invalidState(e.fullName() + " is not active");
        if (e.getAttendancePinHash() == null) throw ApiException.invalidState("No attendance PIN set for " + e.fullName() + "; ask the office to set one");
        Instant now = Instant.now();
        if (e.getPinLockedUntil() != null && e.getPinLockedUntil().isAfter(now))
            throw ApiException.invalidState("PIN locked after wrong attempts. Try after "
                    + Math.max(1, Duration.between(now, e.getPinLockedUntil()).toMinutes() + 1) + " min, or ask your supervisor to mark you");
        if (p.pin() == null || !encoder.matches(p.pin(), e.getAttendancePinHash())) {
            int failed = (e.getPinFailedCount() == null ? 0 : e.getPinFailedCount()) + 1;
            e.setPinFailedCount(failed);
            if (failed >= st.integer("pinMaxAttempts")) {
                e.setPinFailedCount(0);
                e.setPinLockedUntil(now.plus(Duration.ofMinutes(st.integer("pinLockMinutes"))));
                audit.record("ATTENDANCE_PIN_LOCKED", "Employee", e.getId(), Map.of("deviceId", String.valueOf(p.deviceId())));
            }
            employees.saveAndFlush(e);
            throw ApiException.field("pin", "Wrong PIN");
        }
        if (e.getPinFailedCount() != null && e.getPinFailedCount() > 0 || e.getPinLockedUntil() != null) {
            e.setPinFailedCount(0);
            e.setPinLockedUntil(null);
        }
        return e.getId();
    }

    /** Step 2: the punch itself, evidenced by PIN (+ photo) on a shared site device. */
    @Transactional
    public EventResult kioskPunch(KioskPunch p, UUID employeeId) {
        Authz.require(Perm.ATTENDANCE_KIOSK);
        if (p.clientEventId() == null) throw ApiException.field("clientEventId", "Device-generated UUID required");
        if (p.photoId() == null && tenantService.settings().bool("kioskPhotoRequired"))
            throw ApiException.field("photoId", "Photo required at the kiosk");
        var req = new EventRequest(p.clientEventId(), employeeId, null, p.kind(), p.occurredAt(), p.latitude(), p.longitude(),
                p.accuracyM(), p.deviceId(), null, p.offline(), p.photoId());
        validate(req);
        Optional<EventResult> dup = duplicate(req.clientEventId());
        return dup.orElseGet(() -> record(req, employeeId, "KIOSK", null));
    }

    public Optional<EventResult> duplicate(UUID clientEventId) {
        return events.findByClientEventId(clientEventId).map(e -> {
            AttendanceRecord r = e.getAssignmentId() == null ? null : records.findByAssignmentId(e.getAssignmentId()).orElse(null);
            return new EventResult(e.getClientEventId(), e.getId(), e.getStatus(), List.of(e.getFlags()), e.getAssignmentId(),
                    r == null ? null : r.getId(), r == null ? null : r.getStatus(), true, "Already received");
        });
    }

    private static void validate(EventRequest req) {
        if (!Set.of("CHECK_IN", "CHECK_OUT").contains(String.valueOf(req.kind()))) throw ApiException.field("kind", "CHECK_IN or CHECK_OUT");
        if (req.occurredAt() == null) throw ApiException.field("occurredAt", "Required");
    }

    public record RollCallRequest(UUID batchId, UUID siteId, String kind, Instant occurredAt, List<UUID> assignmentIds, UUID photoId,
                                  Double latitude, Double longitude, Double accuracyM, String deviceId, Boolean offline) {}

    /** Validates a supervisor roll-call (a group photo + location marks the whole team in one go). */
    public void checkRollCall(RollCallRequest r) {
        Authz.require(Perm.ATTENDANCE_MARK);
        if (r.batchId() == null) throw ApiException.field("batchId", "Device-generated UUID required");
        if (r.siteId() == null) throw ApiException.field("siteId", "Required");
        Authz.requireSite(r.siteId(), "Site");
        if (r.assignmentIds() == null || r.assignmentIds().isEmpty()) throw ApiException.field("assignmentIds", "Select at least one person");
        if (r.assignmentIds().size() > 200) throw ApiException.field("assignmentIds", "Max 200 per roll-call");
        if (r.photoId() == null && tenantService.settings().bool("rollCallPhotoRequired"))
            throw ApiException.field("photoId", "Take a photo of the team on duty");
        if (r.photoId() != null && !photoExists(r.photoId())) throw ApiException.field("photoId", "Unknown photo");
        validate(new EventRequest(r.batchId(), null, null, r.kind(), r.occurredAt(), null, null, null, null, null, null, null));
    }

    /** One person of a roll-call, in its own transaction so one failure does not undo the rest. */
    @Transactional
    public EventResult rollCallOne(RollCallRequest r, UUID assignmentId) {
        Assignment a = assignments.findById(assignmentId).orElseThrow(() -> ApiException.notFound("Assignment"));
        Shift sh = shifts.findById(a.getShiftId()).orElseThrow();
        if (!sh.getSiteId().equals(r.siteId())) throw ApiException.field("assignmentIds", "Duty is not at this site");
        UUID cid = UUID.nameUUIDFromBytes((r.batchId() + ":" + assignmentId + ":" + r.kind()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var req = new EventRequest(cid, a.getEmployeeId(), a.getId(), r.kind(), r.occurredAt(), r.latitude(), r.longitude(), r.accuracyM(),
                r.deviceId(), null, r.offline(), r.photoId());
        return duplicate(cid).orElseGet(() -> record(req, a.getEmployeeId(), "SUPERVISOR", r.batchId()));
    }

    public record RegisterLine(UUID assignmentId, String status, Instant checkInAt, Instant checkOutAt) {}

    public record RegisterRequest(UUID siteId, LocalDate date, UUID photoId, String note, List<RegisterLine> entries) {}

    /**
     * Office entry from a paper attendance register (workers with no phone and no kiosk). Every line becomes
     * a correction request with the register photo as evidence; a different person approves them.
     */
    @Transactional
    public Map<String, Object> registerEntry(RegisterRequest r) {
        Authz.require(Perm.ATTENDANCE_ADJUST);
        if (r.siteId() == null) throw ApiException.field("siteId", "Required");
        if (r.date() == null) throw ApiException.field("date", "Required");
        Authz.requireSite(r.siteId(), "Site");
        if (r.photoId() == null || !photoExists(r.photoId())) throw ApiException.field("photoId", "Attach a photo of the signed register page");
        if (r.entries() == null || r.entries().isEmpty()) throw ApiException.field("entries", "Nothing to submit");
        UUID batch = UUID.randomUUID();
        List<UUID> ids = new ArrayList<>();
        for (RegisterLine l : r.entries()) {
            if (l.status() == null) throw ApiException.field("entries", "Status required for every line");
            Assignment a = assignments.findById(l.assignmentId()).orElseThrow(() -> ApiException.notFound("Assignment"));
            Shift sh = shifts.findById(a.getShiftId()).orElseThrow();
            if (!sh.getSiteId().equals(r.siteId()) || !sh.getDutyDate().equals(r.date()))
                throw ApiException.field("entries", "Duty is not at this site on " + r.date());
            Instant in = l.checkInAt(), out = l.checkOutAt();
            if ("PRESENT".equals(l.status()) && in == null && out == null) { in = a.getStartAt(); out = a.getEndAt(); }
            String reason = "Register entry" + (r.note() == null || r.note().isBlank() ? "" : ": " + r.note());
            AttendanceAdjustment adj = requestAdjustment(new AdjustmentRequest(null, a.getId(), in, out, l.status(), reason, "photo:" + r.photoId()));
            adj.setPhotoId(r.photoId());
            adj.setBatchId(batch);
            ids.add(adj.getId());
        }
        audit.record("ATTENDANCE_REGISTER_ENTRY", "Site", r.siteId(), Map.of("date", r.date().toString(), "lines", ids.size(), "batchId", batch));
        return Map.of("batchId", batch, "adjustmentIds", ids, "count", ids.size());
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
                && "PENDING".equals(r.getApprovalStatus()) && r.getLateMinutes() == 0 && trustedSources(r, st)) {
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
            if ("APPROVED".equals(req.decision()) && markedByMe(r)) { skipped.add(id + ": you marked this duty; another person must approve it"); continue; }
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
        before.put("checkInAt", rec.getCheckInAt() == null ? null : rec.getCheckInAt().toString());
        before.put("checkOutAt", rec.getCheckOutAt() == null ? null : rec.getCheckOutAt().toString());
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
        if (adj.getBatchId() != null) rec.setSources(merge(rec.getSources(), List.of("MANUAL")));
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

    /** Auto-approval only when every way this duty was marked is trusted (default: own phone, site kiosk). */
    static boolean trustedSources(AttendanceRecord r, TenantSettings st) {
        List<String> trusted = st.list("autoApproveSources");
        return r.getSources() == null || Arrays.stream(r.getSources()).allMatch(trusted::contains);
    }

    /** Maker-checker: whoever punched a duty for someone else may not also approve it. */
    private boolean markedByMe(AttendanceRecord r) {
        if (!tenantService.settings().bool("requireApprovalSeparation") || r.getMarkedBy() == null) return false;
        UUID me = Authz.userId();
        boolean proxyMarked = r.getSources() != null && Arrays.stream(r.getSources()).anyMatch(x -> !"MOBILE".equals(x));
        return proxyMarked && Arrays.asList(r.getMarkedBy()).contains(me);
    }

    boolean photoExists(UUID id) {
        Integer n = jdbc.queryForObject("select count(*) from attendance_photos where id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    static UUID[] mergeIds(UUID[] existing, UUID add) {
        LinkedHashSet<UUID> s = new LinkedHashSet<>(existing == null ? List.of() : List.of(existing));
        if (add != null) s.add(add);
        return s.toArray(UUID[]::new);
    }

    static String[] merge(String[] existing, List<String> add) {
        LinkedHashSet<String> s = new LinkedHashSet<>(existing == null ? List.of() : List.of(existing));
        s.addAll(add);
        return s.toArray(String[]::new);
    }

    private static String[] append(String[] arr, String v) { return merge(arr, List.of(v)); }
}

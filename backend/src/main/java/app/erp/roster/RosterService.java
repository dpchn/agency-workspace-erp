package app.erp.roster;

import app.erp.client.*;
import app.erp.common.ApiException;
import app.erp.common.AuditService;
import app.erp.common.OutboxService;
import app.erp.hr.Employee;
import app.erp.hr.EmployeeRepository;
import app.erp.hr.HrService;
import app.erp.org.ShiftTemplate;
import app.erp.org.ShiftTemplateRepository;
import app.erp.platform.TenantService;
import app.erp.platform.TenantSettings;
import app.erp.security.Authz;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deployment (who is posted where, over time) and roster (who covers which shift).
 *
 * Key rules:
 *  - A guard has at most one PERMANENT deployment at a time; TEMPORARY deployments override it for their dates.
 *  - Transfers end the old deployment and start a new one; history is never rewritten.
 *  - Assignments can never overlap (DB exclusion constraint); rest-interval breaches require role DOUBLE_DUTY.
 *  - Replacements keep the original assignment (as NO_SHOW or truncated) so pay/bill reconcile per person.
 */
@Service
public class RosterService {
    private final ShiftRepository shifts;
    private final AssignmentRepository assignments;
    private final DeploymentRepository deployments;
    private final PostRepository posts;
    private final PostShiftRequirementRepository requirements;
    private final SiteRepository sites;
    private final ShiftTemplateRepository templates;
    private final EmployeeRepository employees;
    private final HrService hr;
    private final TenantService tenantService;
    private final AuditService audit;
    private final OutboxService outbox;
    private final JdbcTemplate jdbc;

    public RosterService(ShiftRepository shifts, AssignmentRepository assignments, DeploymentRepository deployments,
                         PostRepository posts, PostShiftRequirementRepository requirements, SiteRepository sites,
                         ShiftTemplateRepository templates, EmployeeRepository employees, HrService hr,
                         TenantService tenantService, AuditService audit, OutboxService outbox, JdbcTemplate jdbc) {
        this.shifts = shifts;
        this.assignments = assignments;
        this.deployments = deployments;
        this.posts = posts;
        this.requirements = requirements;
        this.sites = sites;
        this.templates = templates;
        this.employees = employees;
        this.hr = hr;
        this.tenantService = tenantService;
        this.audit = audit;
        this.outbox = outbox;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ deployments

    @Transactional
    public Deployment deploy(Deployment d) {
        validateDeployment(d);
        d.setCreatedBy(Authz.userId());
        d.setStatus("ACTIVE");
        Deployment saved = deployments.saveAndFlush(d);
        audit.record("DEPLOY", "Deployment", saved.getId(), Map.of("employeeId", d.getEmployeeId(), "siteId", d.getSiteId(),
                "type", d.getDeploymentType(), "from", d.getStartDate()));
        return saved;
    }

    public record TransferRequest(UUID employeeId, UUID toSiteId, UUID toPostId, UUID serviceTypeId, UUID shiftTemplateId,
                                  LocalDate effectiveDate, String reason) {}

    /**
     * Permanent transfer: old posting ends the day before, new one starts on effectiveDate,
     * future planned duties at the old site are released (shift becomes open) and the guard is
     * rostered at the new site by the next roster generation.
     */
    @Transactional
    public Deployment transfer(TransferRequest r) {
        if (r.effectiveDate() == null) throw ApiException.field("effectiveDate", "Required");
        if (r.reason() == null || r.reason().isBlank()) throw ApiException.field("reason", "Required");
        Employee e = hr.get(r.employeeId());
        Deployment current = deployments.activeFor(e.getId(), r.effectiveDate()).stream()
                .filter(x -> "PERMANENT".equals(x.getDeploymentType())).findFirst()
                .orElseGet(() -> deployments.activeFor(e.getId(), r.effectiveDate().minusDays(1)).stream()
                        .filter(x -> "PERMANENT".equals(x.getDeploymentType())).findFirst().orElse(null));
        if (current != null) {
            if (current.getSiteId().equals(r.toSiteId()) && Objects.equals(current.getPostId(), r.toPostId()))
                throw ApiException.invalidState("Employee is already posted there");
            if (!current.getStartDate().isBefore(r.effectiveDate())) {
                current.setStatus("CANCELLED");
            } else {
                current.setEndDate(r.effectiveDate().minusDays(1));
            }
            deployments.saveAndFlush(current);
            releaseFutureAssignments(e.getId(), current.getSiteId(), r.effectiveDate(), "Transferred: " + r.reason());
        }
        Deployment d = new Deployment();
        d.setEmployeeId(e.getId());
        d.setSiteId(r.toSiteId());
        d.setPostId(r.toPostId());
        d.setServiceTypeId(r.serviceTypeId() != null ? r.serviceTypeId() : current != null ? current.getServiceTypeId() : e.getServiceTypeId());
        d.setShiftTemplateId(r.shiftTemplateId());
        d.setDeploymentType("PERMANENT");
        d.setStartDate(r.effectiveDate());
        d.setReason(r.reason());
        Deployment saved = deploy(d);
        audit.record("TRANSFER", "Employee", e.getId(), Map.of("fromSiteId", current == null ? "" : current.getSiteId(),
                "toSiteId", r.toSiteId(), "effectiveDate", r.effectiveDate(), "reason", r.reason()));
        return saved;
    }

    @Transactional
    public Deployment endDeployment(UUID id, LocalDate endDate, String reason) {
        Deployment d = deployments.findById(id).orElseThrow(() -> ApiException.notFound("Deployment"));
        Authz.requireSite(d.getSiteId(), "Deployment");
        if (endDate == null) endDate = LocalDate.now();
        if (endDate.isBefore(d.getStartDate())) {
            d.setStatus("CANCELLED");
        } else {
            d.setEndDate(endDate);
        }
        releaseFutureAssignments(d.getEmployeeId(), d.getSiteId(), endDate.plusDays(1), reason == null ? "Deployment ended" : reason);
        audit.record("DEPLOYMENT_END", "Deployment", id, Map.of("endDate", endDate));
        return d;
    }

    private void validateDeployment(Deployment d) {
        if (d.getEmployeeId() == null) throw ApiException.field("employeeId", "Required");
        if (d.getSiteId() == null) throw ApiException.field("siteId", "Required");
        if (d.getStartDate() == null) throw ApiException.field("startDate", "Required");
        if (d.getEndDate() != null && d.getEndDate().isBefore(d.getStartDate())) throw ApiException.field("endDate", "Before start");
        if (!Set.of("PERMANENT", "TEMPORARY", "RELIEVER").contains(d.getDeploymentType())) throw ApiException.field("deploymentType", "Invalid");
        if ("TEMPORARY".equals(d.getDeploymentType()) && d.getEndDate() == null) throw ApiException.field("endDate", "Temporary deployment needs an end date");
        Site site = sites.findById(d.getSiteId()).orElseThrow(() -> ApiException.field("siteId", "Unknown site"));
        Authz.requireSite(site.getId(), "Site");
        if (d.getPostId() != null) {
            Post p = posts.findById(d.getPostId()).orElseThrow(() -> ApiException.field("postId", "Unknown post"));
            if (!p.getSiteId().equals(site.getId())) throw ApiException.field("postId", "Post is not at this site");
            if (d.getServiceTypeId() == null) d.setServiceTypeId(p.getServiceTypeId());
        }
        Employee e = hr.get(d.getEmployeeId());
        if (d.getServiceTypeId() == null) d.setServiceTypeId(e.getServiceTypeId());
        hr.requireDeployable(e, d.getServiceTypeId(), d.getStartDate());
    }

    private void releaseFutureAssignments(UUID employeeId, UUID siteId, LocalDate from, String reason) {
        List<UUID> shiftIds = jdbc.queryForList("""
                update assignments a set status = 'CANCELLED', reason = ?, updated_at = now()
                from shifts s where s.id = a.shift_id and a.employee_id = ? and s.site_id = ? and s.duty_date >= ?
                  and a.status = 'PLANNED' returning a.shift_id""", UUID.class, reason, employeeId, siteId, from);
        new HashSet<>(shiftIds).forEach(this::refreshShiftStatus);
    }

    // ------------------------------------------------------------------ roster generation

    public record GenerateResult(int shiftsCreated, int assignmentsCreated, int openSlots, List<String> warnings) {}

    /**
     * Builds shifts from post requirements for [from, to] and auto-assigns deployed guards.
     * Safe to re-run: existing shifts/assignments are kept, only gaps are filled.
     */
    @Transactional
    public GenerateResult generate(LocalDate from, LocalDate to, UUID siteId) {
        if (from == null || to == null || to.isBefore(from)) throw ApiException.badRequest("Invalid date range");
        if (from.plusDays(62).isBefore(to)) throw ApiException.badRequest("Generate at most 62 days at a time");
        var u = Authz.user();
        List<Site> siteList = siteId != null ? List.of(sites.findById(siteId).orElseThrow(() -> ApiException.notFound("Site")))
                : sites.findAll((r, q, cb) -> cb.equal(r.get("status"), "ACTIVE"));
        siteList = siteList.stream().filter(s -> u.siteAllowed(s.getId()) && u.branchAllowed(s.getBranchId())).toList();
        Map<UUID, Site> siteById = siteList.stream().collect(Collectors.toMap(Site::getId, s -> s));
        if (siteById.isEmpty()) return new GenerateResult(0, 0, 0, List.of("No sites in scope"));

        List<Post> postList = posts.findAll((r, q, cb) -> cb.and(r.get("siteId").in(siteById.keySet()), cb.isTrue(r.get("active"))));
        Map<UUID, Post> postById = postList.stream().collect(Collectors.toMap(Post::getId, p -> p));
        Map<UUID, ShiftTemplate> tpl = templates.findAll().stream().collect(Collectors.toMap(ShiftTemplate::getId, t -> t));
        List<PostShiftRequirement> reqs = postById.isEmpty() ? List.of()
                : requirements.findAll((r, q, cb) -> r.get("postId").in(postById.keySet()));
        ZoneId tenantZone = tenantService.zone();

        int created = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            for (PostShiftRequirement req : reqs) {
                if (!req.activeOn(d)) continue;
                Post post = postById.get(req.getPostId());
                ShiftTemplate t = tpl.get(req.getShiftTemplateId());
                Site site = siteById.get(post.getSiteId());
                ZoneId zone = site.getTimezone() != null ? ZoneId.of(site.getTimezone()) : tenantZone;
                Instant start = ZonedDateTime.of(d, t.getStartTime(), zone).toInstant();
                Instant end = start.plus(t.length());
                created += jdbc.update("""
                        insert into shifts(id, tenant_id, site_id, post_id, service_type_id, shift_template_id, duty_date,
                                           start_at, end_at, required_headcount, kind)
                        values (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, 'REGULAR')
                        on conflict (tenant_id, post_id, shift_template_id, duty_date) where kind = 'REGULAR' do nothing""",
                        UUID.randomUUID(), site.getId(), post.getId(), post.getServiceTypeId(), t.getId(), d,
                        java.sql.Timestamp.from(start), java.sql.Timestamp.from(end), req.getHeadcount());
            }
        }

        // ---- auto-assign from deployments
        List<Shift> shiftList = shifts.findAll((r, q, cb) -> cb.and(r.get("siteId").in(siteById.keySet()),
                cb.between(r.get("dutyDate"), from, to), cb.notEqual(r.get("status"), "CANCELLED")));
        Map<UUID, List<Assignment>> byShift = assignments.findByShiftIdIn(shiftList.stream().map(Shift::getId).toList())
                .stream().collect(Collectors.groupingBy(Assignment::getShiftId));
        List<Deployment> deps = deployments.activeBetween(from, to);
        Map<UUID, Employee> emps = employees.findAllById(deps.stream().map(Deployment::getEmployeeId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Employee::getId, e -> e));
        Set<String> onLeave = new HashSet<>(jdbc.queryForList("""
                select employee_id || '|' || gs::date from leave_requests l,
                  generate_series(greatest(l.from_date, ?::date), least(l.to_date, ?::date), interval '1 day') gs
                where l.status = 'APPROVED'""", String.class, from, to));

        int assigned = 0, open = 0;
        List<String> warnings = new ArrayList<>();
        shiftList.sort(Comparator.comparing(Shift::getStartAt));
        for (Shift s : shiftList) {
            List<Assignment> current = byShift.getOrDefault(s.getId(), new ArrayList<>());
            long filled = current.stream().filter(a -> ACTIVE.contains(a.getStatus())).count();
            Set<UUID> alreadyOnShift = current.stream().map(Assignment::getEmployeeId).collect(Collectors.toSet());
            if (filled < s.getRequiredHeadcount()) {
                for (Deployment dep : candidatesFor(s, deps)) {
                    if (filled >= s.getRequiredHeadcount()) break;
                    Employee e = emps.get(dep.getEmployeeId());
                    if (e == null || alreadyOnShift.contains(e.getId()) || !"ACTIVE".equals(e.getStatus())) continue;
                    if (e.getWeeklyOffDay() != null && e.getWeeklyOffDay() == s.getDutyDate().getDayOfWeek().getValue()) continue;
                    if (onLeave.contains(e.getId() + "|" + s.getDutyDate())) continue;
                    if (overriddenByTemporary(dep, s.getDutyDate(), deps)) continue;
                    if (!assignments.activeBetween(e.getId(), s.getStartAt(), s.getEndAt()).isEmpty()) continue;
                    if (restViolation(e.getId(), s.getStartAt(), s.getEndAt(), null)) continue;
                    Assignment a = new Assignment();
                    a.setShiftId(s.getId());
                    a.setEmployeeId(e.getId());
                    a.setStartAt(s.getStartAt());
                    a.setEndAt(s.getEndAt());
                    a.setRole("RELIEVER".equals(dep.getDeploymentType()) ? "RELIEF" : "REGULAR");
                    a.setCreatedBy(Authz.userId());
                    assignments.save(a);
                    alreadyOnShift.add(e.getId());
                    filled++;
                    assigned++;
                }
            }
            assignments.flush();
            if (filled < s.getRequiredHeadcount()) open += s.getRequiredHeadcount() - filled;
            s.setStatus(filled == 0 ? "OPEN" : filled < s.getRequiredHeadcount() ? "PARTIAL" : "FILLED");
        }
        if (open > 0) warnings.add(open + " slot(s) still open; assign relievers or use the replacement finder");
        audit.record("ROSTER_GENERATE", "Roster", null, Map.of("from", from, "to", to, "shifts", created, "assignments", assigned));
        return new GenerateResult(created, assigned, open, warnings);
    }

    private static final Set<String> ACTIVE = Set.of("PLANNED", "CONFIRMED", "COMPLETED");

    /** Deployments that may cover this shift: post-specific first, then site-level with matching service type. */
    private List<Deployment> candidatesFor(Shift s, List<Deployment> deps) {
        return deps.stream()
                .filter(d -> d.activeOn(s.getDutyDate()) && d.getSiteId().equals(s.getSiteId()))
                .filter(d -> d.getPostId() == null ? Objects.equals(d.getServiceTypeId(), s.getServiceTypeId()) : d.getPostId().equals(s.getPostId()))
                .filter(d -> d.getShiftTemplateId() == null || d.getShiftTemplateId().equals(s.getShiftTemplateId()))
                .filter(d -> !"RELIEVER".equals(d.getDeploymentType()))
                .sorted(Comparator.comparing((Deployment d) -> d.getPostId() == null ? 1 : 0)
                        .thenComparing(d -> d.getShiftTemplateId() == null ? 1 : 0)
                        .thenComparing(d -> "TEMPORARY".equals(d.getDeploymentType()) ? 0 : 1))
                .toList();
    }

    /** A permanent posting is suspended on days the guard is temporarily deployed elsewhere. */
    private boolean overriddenByTemporary(Deployment dep, LocalDate day, List<Deployment> all) {
        if (!"PERMANENT".equals(dep.getDeploymentType())) return false;
        return all.stream().anyMatch(o -> o != dep && o.getEmployeeId().equals(dep.getEmployeeId())
                && "TEMPORARY".equals(o.getDeploymentType()) && o.activeOn(day));
    }

    // ------------------------------------------------------------------ manual assignment & replacement

    public record AssignRequest(UUID shiftId, UUID employeeId, String role, String reason, Instant startAt, Instant endAt) {}

    @Transactional
    public Assignment assign(AssignRequest r) {
        Shift s = shift(r.shiftId());
        if ("CANCELLED".equals(s.getStatus())) throw ApiException.invalidState("Shift is cancelled");
        Employee e = hr.get(r.employeeId());
        hr.requireDeployable(e, s.getServiceTypeId(), s.getDutyDate());
        Instant start = r.startAt() != null ? r.startAt() : s.getStartAt();
        Instant end = r.endAt() != null ? r.endAt() : s.getEndAt();
        if (start.isBefore(s.getStartAt()) || end.isAfter(s.getEndAt()) || !end.isAfter(start))
            throw ApiException.badRequest("Assignment must lie within the shift window");
        String role = r.role() == null ? "REGULAR" : r.role();
        if (!Set.of("REGULAR", "RELIEF", "REPLACEMENT", "DOUBLE_DUTY").contains(role)) throw ApiException.field("role", "Invalid role");
        checkWorkingTime(e.getId(), start, end, role, null);
        if (!"REGULAR".equals(role) && (r.reason() == null || r.reason().isBlank())) throw ApiException.field("reason", "Reason required");
        Assignment a = new Assignment();
        a.setShiftId(s.getId());
        a.setEmployeeId(e.getId());
        a.setStartAt(start);
        a.setEndAt(end);
        a.setRole(role);
        a.setReason(r.reason());
        a.setCreatedBy(Authz.userId());
        a.setApprovedBy("DOUBLE_DUTY".equals(role) ? Authz.userId() : null);
        assignments.saveAndFlush(a);
        refreshShiftStatus(s.getId());
        audit.record("ASSIGN", "Assignment", a.getId(), Map.of("shiftId", s.getId(), "employeeId", e.getId(), "role", role));
        return a;
    }

    public record ReplaceRequest(UUID employeeId, String reason, Instant handoverAt, boolean doubleDuty) {}

    /**
     * Absence & replacement. Without handoverAt the original guard is marked NO_SHOW and the replacement
     * covers the full window. With handoverAt (mid-shift relief) the original assignment is truncated
     * and the replacement covers the remainder - each person is paid for exactly what they worked.
     */
    @Transactional
    public Assignment replace(UUID assignmentId, ReplaceRequest r) {
        if (r.reason() == null || r.reason().isBlank()) throw ApiException.field("reason", "Reason required");
        Assignment orig = assignments.findById(assignmentId).orElseThrow(() -> ApiException.notFound("Assignment"));
        Shift s = shift(orig.getShiftId());
        if (!Set.of("PLANNED", "CONFIRMED", "NO_SHOW").contains(orig.getStatus())) throw ApiException.invalidState("Assignment is " + orig.getStatus());
        if (orig.getEmployeeId().equals(r.employeeId())) throw ApiException.badRequest("Replacement must be a different employee");
        Employee e = hr.get(r.employeeId());
        hr.requireDeployable(e, s.getServiceTypeId(), s.getDutyDate());

        Instant start = orig.getStartAt();
        Instant end = orig.getEndAt();
        if (r.handoverAt() != null) {
            if (!r.handoverAt().isAfter(orig.getStartAt()) || !r.handoverAt().isBefore(orig.getEndAt()))
                throw ApiException.badRequest("handoverAt must be inside the assignment window");
            start = r.handoverAt();
            orig.setEndAt(r.handoverAt());
            orig.setReason("Relieved at handover: " + r.reason());
        } else {
            orig.setStatus("NO_SHOW");
            orig.setReason(r.reason());
        }
        assignments.saveAndFlush(orig);
        String role = r.doubleDuty() ? "DOUBLE_DUTY" : "REPLACEMENT";
        checkWorkingTime(e.getId(), start, end, role, null);
        Assignment a = new Assignment();
        a.setShiftId(s.getId());
        a.setEmployeeId(e.getId());
        a.setStartAt(start);
        a.setEndAt(end);
        a.setRole(role);
        a.setReplacesAssignmentId(orig.getId());
        a.setReason(r.reason());
        a.setCreatedBy(Authz.userId());
        a.setApprovedBy(Authz.userId());
        assignments.saveAndFlush(a);
        refreshShiftStatus(s.getId());
        audit.record("REPLACE", "Assignment", a.getId(), Map.of("replaces", orig.getId(), "employeeId", e.getId(),
                "handoverAt", String.valueOf(r.handoverAt()), "reason", r.reason()));
        return a;
    }

    @Transactional
    public Assignment cancelAssignment(UUID id, String reason) {
        if (reason == null || reason.isBlank()) throw ApiException.field("reason", "Required");
        Assignment a = assignments.findById(id).orElseThrow(() -> ApiException.notFound("Assignment"));
        shift(a.getShiftId());
        if (!Set.of("PLANNED", "CONFIRMED").contains(a.getStatus())) throw ApiException.invalidState("Assignment is " + a.getStatus());
        Integer checkedIn = jdbc.queryForObject("select count(*) from attendance_records where assignment_id = ? and check_in_at is not null",
                Integer.class, id);
        if (checkedIn != null && checkedIn > 0) throw ApiException.invalidState("Guard already checked in; use replacement with handover");
        a.setStatus("CANCELLED");
        a.setReason(reason);
        refreshShiftStatus(a.getShiftId());
        audit.record("ASSIGNMENT_CANCEL", "Assignment", id, Map.of("reason", reason));
        return a;
    }

    public record ExtraShiftRequest(UUID postId, LocalDate dutyDate, UUID shiftTemplateId, Instant startAt, Instant endAt,
                                    Integer headcount, boolean clientRequested, String notes) {}

    /** Additional shift: billable when the client asked for it, internal cost otherwise. */
    @Transactional
    public Shift createExtraShift(ExtraShiftRequest r) {
        Post p = posts.findById(r.postId()).orElseThrow(() -> ApiException.notFound("Post"));
        Authz.requireSite(p.getSiteId(), "Post");
        Site site = sites.findById(p.getSiteId()).orElseThrow();
        Instant start = r.startAt(), end = r.endAt();
        LocalDate day = r.dutyDate();
        if (r.shiftTemplateId() != null) {
            ShiftTemplate t = templates.findById(r.shiftTemplateId()).orElseThrow(() -> ApiException.notFound("Shift template"));
            if (day == null) throw ApiException.field("dutyDate", "Required");
            ZoneId zone = site.getTimezone() != null ? ZoneId.of(site.getTimezone()) : tenantService.zone();
            start = ZonedDateTime.of(day, t.getStartTime(), zone).toInstant();
            end = start.plus(t.length());
        }
        if (start == null || end == null || !end.isAfter(start)) throw ApiException.badRequest("Valid start/end required");
        if (day == null) day = LocalDate.ofInstant(start, tenantService.zone());
        Shift s = new Shift();
        s.setSiteId(p.getSiteId());
        s.setPostId(p.getId());
        s.setServiceTypeId(p.getServiceTypeId());
        s.setShiftTemplateId(r.shiftTemplateId());
        s.setDutyDate(day);
        s.setStartAt(start);
        s.setEndAt(end);
        s.setRequiredHeadcount(r.headcount() == null ? 1 : r.headcount());
        s.setKind("EXTRA");
        s.setClientRequested(r.clientRequested());
        s.setNotes(r.notes());
        shifts.saveAndFlush(s);
        audit.record("EXTRA_SHIFT", "Shift", s.getId(), Map.of("clientRequested", r.clientRequested()));
        return s;
    }

    @Transactional
    public Shift cancelShift(UUID id, String reason) {
        Shift s = shift(id);
        long worked = assignments.findByShiftId(id).stream().filter(a -> "COMPLETED".equals(a.getStatus())).count();
        if (worked > 0) throw ApiException.invalidState("Shift has completed duties");
        assignments.findByShiftId(id).forEach(a -> {
            if (Set.of("PLANNED", "CONFIRMED").contains(a.getStatus())) {
                a.setStatus("CANCELLED");
                a.setReason("Shift cancelled: " + reason);
            }
        });
        s.setStatus("CANCELLED");
        s.setNotes(reason);
        audit.record("SHIFT_CANCEL", "Shift", id, Map.of("reason", String.valueOf(reason)));
        return s;
    }

    // ------------------------------------------------------------------ eligibility / replacement finder

    public record Candidate(UUID employeeId, String code, String name, String phone, String source, int score, List<String> notes) {}

    /** Ranks available, qualified people for a shift: relievers & same-site off-duty first. */
    @Transactional(readOnly = true)
    public List<Candidate> eligibleFor(UUID shiftId) {
        Shift s = shift(shiftId);
        Site site = sites.findById(s.getSiteId()).orElseThrow();
        List<Deployment> deps = deployments.activeBetween(s.getDutyDate(), s.getDutyDate());
        Map<UUID, List<Deployment>> depByEmp = deps.stream().collect(Collectors.groupingBy(Deployment::getEmployeeId));
        Set<UUID> onShift = assignments.findByShiftId(shiftId).stream().filter(a -> ACTIVE.contains(a.getStatus()))
                .map(Assignment::getEmployeeId).collect(Collectors.toSet());
        Set<UUID> onLeave = new HashSet<>(jdbc.queryForList(
                "select employee_id from leave_requests where status='APPROVED' and ? between from_date and to_date", UUID.class, s.getDutyDate()));
        List<Employee> pool = employees.findAll((r, q, cb) -> cb.and(cb.equal(r.get("status"), "ACTIVE"), cb.equal(r.get("branchId"), site.getBranchId())));
        List<Candidate> out = new ArrayList<>();
        for (Employee e : pool) {
            if (onShift.contains(e.getId()) || onLeave.contains(e.getId()) || !e.qualifiedFor(s.getServiceTypeId())) continue;
            if (!assignments.activeBetween(e.getId(), s.getStartAt(), s.getEndAt()).isEmpty()) continue;
            if (!hr.missingDocuments(e.getId(), s.getServiceTypeId(), s.getDutyDate()).isEmpty()) continue;
            List<String> notes = new ArrayList<>();
            int score = 0;
            String source = "BRANCH_POOL";
            for (Deployment d : depByEmp.getOrDefault(e.getId(), List.of())) {
                if ("RELIEVER".equals(d.getDeploymentType())) { score += 50; source = "RELIEVER"; }
                if (d.getSiteId().equals(s.getSiteId())) { score += 30; if (!"RELIEVER".equals(source)) source = "SAME_SITE_OFF_DUTY"; }
            }
            if (depByEmp.getOrDefault(e.getId(), List.of()).isEmpty()) { score += 20; notes.add("Not currently deployed"); }
            boolean rest = restViolation(e.getId(), s.getStartAt(), s.getEndAt(), null);
            if (rest) { score -= 40; notes.add("Would breach rest interval - DOUBLE_DUTY only"); }
            if (e.getWeeklyOffDay() != null && e.getWeeklyOffDay() == s.getDutyDate().getDayOfWeek().getValue()) {
                score -= 10;
                notes.add("Weekly off day");
            }
            out.add(new Candidate(e.getId(), e.getEmployeeCode(), e.fullName(), e.getPhone(), source, score, notes));
        }
        out.sort(Comparator.comparingInt(Candidate::score).reversed());
        return out.size() > 50 ? out.subList(0, 50) : out;
    }

    // ------------------------------------------------------------------ helpers

    private void checkWorkingTime(UUID employeeId, Instant start, Instant end, String role, UUID ignoreAssignment) {
        List<Assignment> overlap = assignments.activeBetween(employeeId, start, end).stream()
                .filter(a -> !a.getId().equals(ignoreAssignment)).toList();
        if (!overlap.isEmpty()) throw ApiException.conflict("Employee already has an overlapping assignment");
        if (!"DOUBLE_DUTY".equals(role) && restViolation(employeeId, start, end, ignoreAssignment))
            throw ApiException.invalidState("Minimum rest interval not met; assign as DOUBLE_DUTY with a reason if intended");
        TenantSettings st = tenantService.settings();
        LocalDate day = LocalDate.ofInstant(start, tenantService.zone());
        Instant dayStart = day.atStartOfDay(tenantService.zone()).toInstant();
        long sameDay = assignments.activeBetween(employeeId, dayStart, dayStart.plus(Duration.ofDays(1))).size();
        if (sameDay >= st.integer("maxShiftsPerDay")) throw ApiException.invalidState("Maximum shifts per day reached");
    }

    private boolean restViolation(UUID employeeId, Instant start, Instant end, UUID ignore) {
        Duration rest = Duration.ofHours(tenantService.settings().integer("minRestHours"));
        return assignments.activeBetween(employeeId, start.minus(rest), end.plus(rest)).stream()
                .anyMatch(a -> !a.getId().equals(ignore));
    }

    public void refreshShiftStatus(UUID shiftId) {
        Shift s = shifts.findById(shiftId).orElse(null);
        if (s == null || "CANCELLED".equals(s.getStatus())) return;
        // slots are "covered" by whoever is on post at shift end (a mid-shift handover splits one slot)
        long covered = assignments.findByShiftId(shiftId).stream()
                .filter(a -> ACTIVE.contains(a.getStatus()) && !a.getEndAt().isBefore(s.getEndAt())).count();
        String status = covered == 0 ? "OPEN" : covered < s.getRequiredHeadcount() ? "PARTIAL" : "FILLED";
        if (!status.equals(s.getStatus()) && !"FILLED".equals(status)) {
            outbox.publish(OutboxService.SHIFT_UNCOVERED, s.getId(), Map.of("siteId", s.getSiteId(), "dutyDate", s.getDutyDate().toString()));
        }
        s.setStatus(status);
    }

    public Shift shift(UUID id) {
        Shift s = shifts.findById(id).orElseThrow(() -> ApiException.notFound("Shift"));
        Authz.requireSite(s.getSiteId(), "Shift");
        return s;
    }
}

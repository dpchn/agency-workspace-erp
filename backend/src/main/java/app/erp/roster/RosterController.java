package app.erp.roster;

import app.erp.client.PostRepository;
import app.erp.client.SiteRepository;
import app.erp.common.ApiException;
import app.erp.hr.EmployeeRepository;
import app.erp.security.Authz;
import app.erp.security.Perm;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
public class RosterController {
    private final RosterService roster;
    private final DeploymentRepository deployments;
    private final ShiftRepository shifts;
    private final AssignmentRepository assignments;
    private final EmployeeRepository employees;
    private final SiteRepository sites;
    private final PostRepository posts;

    public RosterController(RosterService roster, DeploymentRepository deployments, ShiftRepository shifts,
                            AssignmentRepository assignments, EmployeeRepository employees, SiteRepository sites, PostRepository posts) {
        this.roster = roster;
        this.deployments = deployments;
        this.shifts = shifts;
        this.assignments = assignments;
        this.employees = employees;
        this.sites = sites;
        this.posts = posts;
    }

    // ---------------- deployments
    @GetMapping("/deployments")
    @Transactional(readOnly = true)
    public List<Deployment> listDeployments(@RequestParam(required = false) UUID employeeId, @RequestParam(required = false) UUID siteId,
                                            @RequestParam(required = false) LocalDate activeOn) {
        Authz.require(Perm.ROSTER_READ);
        var u = Authz.user();
        return deployments.findAll((r, q, cb) -> {
            var p = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (employeeId != null) p.add(cb.equal(r.get("employeeId"), employeeId));
            if (siteId != null) p.add(cb.equal(r.get("siteId"), siteId));
            if (u.siteIds() != null) p.add(r.get("siteId").in(u.siteIds()));
            if (activeOn != null) {
                p.add(cb.equal(r.get("status"), "ACTIVE"));
                p.add(cb.lessThanOrEqualTo(r.get("startDate"), activeOn));
                p.add(cb.or(cb.isNull(r.get("endDate")), cb.greaterThanOrEqualTo(r.get("endDate"), activeOn)));
            }
            return cb.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));
        }, Sort.by("startDate").descending());
    }

    @PostMapping("/deployments")
    @ResponseStatus(HttpStatus.CREATED)
    public Deployment deploy(@RequestBody Deployment d) {
        Authz.require(Perm.ROSTER_WRITE);
        return roster.deploy(d);
    }

    @PostMapping("/deployments/transfer")
    @ResponseStatus(HttpStatus.CREATED)
    public Deployment transfer(@RequestBody RosterService.TransferRequest r) {
        Authz.require(Perm.ROSTER_WRITE);
        return roster.transfer(r);
    }

    public record EndRequest(LocalDate endDate, String reason) {}

    @PostMapping("/deployments/{id}/end")
    public Deployment end(@PathVariable UUID id, @RequestBody EndRequest r) {
        Authz.require(Perm.ROSTER_WRITE);
        return roster.endDeployment(id, r.endDate(), r.reason());
    }

    // ---------------- roster
    public record GenerateRequest(LocalDate from, LocalDate to, UUID siteId) {}

    @PostMapping("/roster/generate")
    public RosterService.GenerateResult generate(@RequestBody GenerateRequest r) {
        Authz.require(Perm.ROSTER_WRITE);
        return roster.generate(r.from(), r.to(), r.siteId());
    }

    public record AssignmentView(UUID id, UUID employeeId, String employeeCode, String employeeName, String role, String status,
                                 Object startAt, Object endAt, UUID replacesAssignmentId, String reason) {}

    public record ShiftView(Shift shift, String siteName, String postName, List<AssignmentView> assignments) {}

    /** Roster board: shifts with their assignments for a date range. */
    @GetMapping("/roster")
    @Transactional(readOnly = true)
    public List<ShiftView> board(@RequestParam LocalDate from, @RequestParam LocalDate to, @RequestParam(required = false) UUID siteId,
                                 @RequestParam(required = false) String status) {
        Authz.require(Perm.ROSTER_READ);
        if (from.plusDays(62).isBefore(to)) throw ApiException.badRequest("Range too large");
        var u = Authz.user();
        List<Shift> list = shifts.findAll((r, q, cb) -> {
            var p = new ArrayList<jakarta.persistence.criteria.Predicate>();
            p.add(cb.between(r.get("dutyDate"), from, to));
            if (siteId != null) p.add(cb.equal(r.get("siteId"), siteId));
            if (status != null) p.add(cb.equal(r.get("status"), status));
            if (u.siteIds() != null) p.add(r.get("siteId").in(u.siteIds()));
            return cb.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));
        }, Sort.by("startAt"));
        var byShift = assignments.findByShiftIdIn(list.stream().map(Shift::getId).toList()).stream()
                .collect(Collectors.groupingBy(Assignment::getShiftId));
        Set<UUID> empIds = byShift.values().stream().flatMap(List::stream).map(Assignment::getEmployeeId).collect(Collectors.toSet());
        var emps = employees.findAllById(empIds).stream().collect(Collectors.toMap(e -> e.getId(), e -> e));
        var siteNames = sites.findAllById(list.stream().map(Shift::getSiteId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(s -> s.getId(), s -> s.getName()));
        var postNames = posts.findAllById(list.stream().map(Shift::getPostId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(p -> p.getId(), p -> p.getName()));
        return list.stream().map(s -> new ShiftView(s, siteNames.get(s.getSiteId()), postNames.get(s.getPostId()),
                byShift.getOrDefault(s.getId(), List.of()).stream().map(a -> {
                    var e = emps.get(a.getEmployeeId());
                    return new AssignmentView(a.getId(), a.getEmployeeId(), e == null ? null : e.getEmployeeCode(),
                            e == null ? null : e.fullName(), a.getRole(), a.getStatus(), a.getStartAt(), a.getEndAt(),
                            a.getReplacesAssignmentId(), a.getReason());
                }).toList())).toList();
    }

    @PostMapping("/shifts")
    @ResponseStatus(HttpStatus.CREATED)
    public Shift extraShift(@RequestBody RosterService.ExtraShiftRequest r) {
        Authz.require(Perm.ROSTER_WRITE);
        return roster.createExtraShift(r);
    }

    public record ReasonRequest(String reason) {}

    @PostMapping("/shifts/{id}/cancel")
    public Shift cancelShift(@PathVariable UUID id, @RequestBody ReasonRequest r) {
        Authz.require(Perm.ROSTER_WRITE);
        return roster.cancelShift(id, r.reason());
    }

    @GetMapping("/shifts/{id}/eligible-employees")
    public List<RosterService.Candidate> eligible(@PathVariable UUID id) {
        Authz.require(Perm.ROSTER_READ);
        return roster.eligibleFor(id);
    }

    @PostMapping("/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public Assignment assign(@RequestBody RosterService.AssignRequest r) {
        Authz.require(Perm.ROSTER_WRITE);
        return roster.assign(r);
    }

    @PostMapping("/assignments/{id}/replace")
    @ResponseStatus(HttpStatus.CREATED)
    public Assignment replace(@PathVariable UUID id, @RequestBody RosterService.ReplaceRequest r) {
        Authz.require(Perm.ROSTER_WRITE);
        return roster.replace(id, r);
    }

    @PostMapping("/assignments/{id}/cancel")
    public Assignment cancel(@PathVariable UUID id, @RequestBody ReasonRequest r) {
        Authz.require(Perm.ROSTER_WRITE);
        return roster.cancelAssignment(id, r.reason());
    }
}

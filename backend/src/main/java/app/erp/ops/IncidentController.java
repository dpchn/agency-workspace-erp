package app.erp.ops;

import app.erp.common.*;
import app.erp.platform.TenantService;
import app.erp.security.Authz;
import app.erp.security.Perm;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Incident workflow: OPEN -> ACKNOWLEDGED -> IN_PROGRESS -> RESOLVED -> CLOSED, with SLA-driven escalation. */
@RestController
@RequestMapping("/v1/incidents")
public class IncidentController {
    private final IncidentRepository incidents;
    private final TenantService tenantService;
    private final AuditService audit;
    private final OutboxService outbox;

    public IncidentController(IncidentRepository incidents, TenantService tenantService, AuditService audit, OutboxService outbox) {
        this.incidents = incidents;
        this.tenantService = tenantService;
        this.audit = audit;
        this.outbox = outbox;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<Incident> list(@RequestParam(required = false) String status, @RequestParam(required = false) UUID siteId,
                               @RequestParam(required = false) String severity) {
        var u = Authz.user();
        Authz.requireAny(Perm.INCIDENT_READ, Perm.INCIDENT_REPORT);
        return incidents.findAll((r, q, cb) -> {
            var p = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (status != null) p.add(cb.equal(r.get("status"), status));
            if (siteId != null) p.add(cb.equal(r.get("siteId"), siteId));
            if (severity != null) p.add(cb.equal(r.get("severity"), severity));
            if (u.siteIds() != null) p.add(r.get("siteId").in(u.siteIds()));
            if (u.isClientUser()) {
                p.add(cb.isTrue(r.get("clientVisible")));
                p.add(r.get("siteId").in(clientSites(u.clientId())));
            }
            if (u.isGuard()) p.add(cb.equal(r.get("reportedBy"), u.userId()));
            return cb.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));
        }, Sort.by("occurredAt").descending());
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private List<UUID> clientSites(UUID clientId) {
        return jdbc.queryForList("select id from sites where client_id = ?", UUID.class, clientId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Incident report(@RequestBody Incident i) {
        var u = Authz.require(Perm.INCIDENT_REPORT);
        if (i.getSiteId() == null) throw ApiException.field("siteId", "Required");
        if (u.isClientUser() && !clientSites(u.clientId()).contains(i.getSiteId())) throw ApiException.notFound("Site");
        Authz.requireSite(i.getSiteId(), "Site");
        if (i.getTitle() == null || i.getTitle().isBlank()) throw ApiException.field("title", "Required");
        if (!Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(String.valueOf(i.getSeverity()))) throw ApiException.field("severity", "LOW/MEDIUM/HIGH/CRITICAL");
        i.setReportedBy(u.userId());
        if (u.employeeId() != null) i.setReporterEmployeeId(u.employeeId());
        if (i.getOccurredAt() == null) i.setOccurredAt(Instant.now());
        i.setResponseDueAt(Instant.now().plus(Duration.ofMinutes(tenantService.settings().incidentResponseMinutes(i.getSeverity()))));
        if (u.isClientUser()) i.setClientVisible(true);
        incidents.saveAndFlush(i);
        outbox.publish(OutboxService.INCIDENT_RAISED, i.getId(), Map.of("severity", i.getSeverity(), "siteId", i.getSiteId().toString(), "title", i.getTitle()));
        audit.record("INCIDENT_REPORT", "Incident", i.getId(), Map.of("severity", i.getSeverity()));
        return i;
    }

    public record Action(String notes, UUID assignTo, Boolean clientVisible) {}

    @PostMapping("/{id}/{action:acknowledge|start|resolve|close|reopen|assign}")
    @Idempotent
    @Transactional
    public Incident act(@PathVariable UUID id, @PathVariable String action, @RequestBody(required = false) Action a) {
        Authz.require(Perm.INCIDENT_MANAGE);
        Incident i = incidents.findById(id).orElseThrow(() -> ApiException.notFound("Incident"));
        Authz.requireSite(i.getSiteId(), "Incident");
        UUID me = Authz.userId();
        switch (action) {
            case "acknowledge" -> {
                if (!"OPEN".equals(i.getStatus())) throw ApiException.invalidState("Already acknowledged");
                i.setStatus("ACKNOWLEDGED");
                i.setAcknowledgedAt(Instant.now());
                i.setAcknowledgedBy(me);
                if (i.getAssignedTo() == null) i.setAssignedTo(me);
            }
            case "start" -> { need(i, "ACKNOWLEDGED"); i.setStatus("IN_PROGRESS"); }
            case "resolve" -> {
                if (!Set.of("ACKNOWLEDGED", "IN_PROGRESS").contains(i.getStatus())) throw ApiException.invalidState("Cannot resolve from " + i.getStatus());
                if (a == null || a.notes() == null || a.notes().isBlank()) throw ApiException.field("notes", "Resolution notes required");
                i.setStatus("RESOLVED");
                i.setClosureNotes(a.notes());
            }
            case "close" -> {
                need(i, "RESOLVED");
                i.setStatus("CLOSED");
                i.setClosedAt(Instant.now());
                i.setClosedBy(me);
            }
            case "reopen" -> {
                if (!Set.of("RESOLVED", "CLOSED").contains(i.getStatus())) throw ApiException.invalidState("Not closed");
                i.setStatus("IN_PROGRESS");
            }
            case "assign" -> {
                if (a == null || a.assignTo() == null) throw ApiException.field("assignTo", "Required");
                i.setAssignedTo(a.assignTo());
            }
            default -> throw ApiException.badRequest("Unknown action");
        }
        if (a != null && a.clientVisible() != null) i.setClientVisible(a.clientVisible());
        audit.record("INCIDENT_" + action.toUpperCase(), "Incident", id, Map.of("status", i.getStatus()));
        return i;
    }

    private static void need(Incident i, String s) {
        if (!s.equals(i.getStatus())) throw ApiException.invalidState("Incident is " + i.getStatus());
    }
}

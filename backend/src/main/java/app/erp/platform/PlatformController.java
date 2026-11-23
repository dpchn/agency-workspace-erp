package app.erp.platform;

import app.erp.common.ApiException;
import app.erp.common.AppProperties;
import app.erp.common.AuditService;
import app.erp.common.TenantContext;
import app.erp.security.Authz;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
public class PlatformController {
    private final SignupService signup;
    private final TenantRepository tenants;
    private final MembershipRepository memberships;
    private final AppProperties props;
    private final AuditService audit;

    public PlatformController(SignupService signup, TenantRepository tenants, MembershipRepository memberships,
                              AppProperties props, AuditService audit) {
        this.signup = signup;
        this.tenants = tenants;
        this.memberships = memberships;
        this.props = props;
        this.audit = audit;
    }

    /** Public agency signup. */
    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@RequestBody SignupService.SignupRequest req) {
        if (!props.signup().enabled()) throw ApiException.forbidden("Self signup is disabled");
        Tenant t = signup.signup(req);
        return Map.of("tenantId", t.getId(), "slug", t.getSlug(), "status", t.getStatus(), "trialEndsAt", t.getTrialEndsAt());
    }

    /** Product brand for login screens (white-label). */
    @GetMapping("/public/brand")
    public AppProperties.Brand brand() { return props.brand(); }

    @GetMapping("/public/tenants/{slug}/brand")
    @Transactional(readOnly = true)
    public Map<String, Object> tenantBrand(@PathVariable String slug) {
        Tenant t = tenants.findBySlug(slug).orElseThrow(() -> ApiException.notFound("Organisation"));
        return Map.of("name", t.getName(), "slug", t.getSlug(), "branding", t.getBranding());
    }

    // ---------------- platform operator: onboarding, plans, limits and health only; never agency HR/attendance/finance data

    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired private UserRepository users;
    @org.springframework.beans.factory.annotation.Autowired private app.erp.security.TokenRevocationValidator revocation;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.security.crypto.password.PasswordEncoder encoder;

    private UUID requirePlatform() {
        if (!Authz.user().platformAdmin()) throw ApiException.notFound("Resource");
        return Authz.userId();
    }

    /** Office-staff roles that count towards a plan's user limit (guards, clients and site devices never do). */
    static final List<String> STAFF_ROLES = List.of("OWNER", "HR", "OPERATIONS", "SUPERVISOR", "FINANCE", "AUDITOR");

    /** Aggregate counts only, read under the agency's own row-level security. */
    private Map<String, Object> usage(UUID tenantId) {
        return TenantContext.runAs(tenantId, () -> {
            Map<String, Object> u = new java.util.LinkedHashMap<>();
            u.put("employees", jdbc.queryForObject("select count(*) from employees where status <> 'EXITED'", Long.class));
            u.put("sites", jdbc.queryForObject("select count(*) from sites where status = 'ACTIVE'", Long.class));
            u.put("clients", jdbc.queryForObject("select count(*) from clients where status = 'ACTIVE'", Long.class));
            u.put("duties30d", jdbc.queryForObject("select count(*) from attendance_records where duty_date >= current_date - 30", Long.class));
            u.put("lastActivityAt", jdbc.queryForObject("select max(created_at) from audit_events", java.sql.Timestamp.class));
            return u;
        });
    }

    private Map<String, Object> view(Tenant t) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("slug", t.getSlug());
        m.put("name", t.getName());
        m.put("status", t.getStatus());
        m.put("trialExpired", t.trialExpired());
        m.put("statusReason", t.getStatusReason());
        m.put("plan", t.getPlan());
        m.put("trialEndsAt", t.getTrialEndsAt());
        m.put("createdAt", t.getCreatedAt());
        m.put("stateCode", t.getStateCode());
        m.put("gstin", t.getGstin());
        m.put("contactName", t.getContactName());
        m.put("contactEmail", t.getContactEmail());
        m.put("contactPhone", t.getContactPhone());
        m.put("notes", t.getPlatformNotes());
        m.put("limits", java.util.Map.of("employees", lim(t.getMaxEmployees()), "sites", lim(t.getMaxSites()), "users", lim(t.getMaxUsers())));
        Map<String, Object> usage = new java.util.LinkedHashMap<>(usage(t.getId()));
        usage.put("staffUsers", jdbc.queryForObject("select count(*) from memberships where tenant_id = ? and status = 'ACTIVE' and role = any(?)",
                Long.class, t.getId(), STAFF_ROLES.toArray(String[]::new)));
        usage.put("guardLogins", jdbc.queryForObject("select count(*) from memberships where tenant_id = ? and status = 'ACTIVE' and role = 'GUARD'",
                Long.class, t.getId()));
        m.put("usage", usage);
        m.put("owner", jdbc.queryForList("""
                select u.full_name as name, u.email, u.phone, u.last_login_at as "lastLoginAt" from memberships ms join users u on u.id = ms.user_id
                where ms.tenant_id = ? and ms.role = 'OWNER' and ms.status = 'ACTIVE' order by ms.created_at limit 1""", t.getId())
                .stream().findFirst().orElse(null));
        return m;
    }

    private static Object lim(Integer v) { return v == null ? "UNLIMITED" : v; }

    @GetMapping("/platform/plans")
    public List<Map<String, Object>> plans() {
        requirePlatform();
        return PlanCatalog.PLANS.stream().map(PlanCatalog::view).toList();
    }

    @GetMapping("/platform/tenants")
    public List<Map<String, Object>> list() {
        requirePlatform();
        return tenants.findAll(org.springframework.data.domain.Sort.by("createdAt").descending()).stream().map(this::view).toList();
    }

    @GetMapping("/platform/tenants/{id}")
    public Map<String, Object> one(@PathVariable UUID id) {
        requirePlatform();
        return view(tenants.findById(id).orElseThrow(() -> ApiException.notFound("Tenant")));
    }

    /** Totals across all agencies for the operator's dashboard. */
    @GetMapping("/platform/summary")
    public Map<String, Object> summary() {
        requirePlatform();
        Map<String, Long> byStatus = new java.util.TreeMap<>();
        long employees = 0, sites = 0, expired = 0;
        for (Tenant t : tenants.findAll()) {
            byStatus.merge(t.trialExpired() ? "TRIAL_EXPIRED" : t.getStatus(), 1L, Long::sum);
            if (t.trialExpired()) expired++;
            var u = usage(t.getId());
            employees += (Long) u.get("employees");
            sites += (Long) u.get("sites");
        }
        return Map.of("agencies", tenants.count(), "byStatus", byStatus, "employees", employees, "sites", sites, "trialExpired", expired,
                "selfSignupEnabled", props.signup().enabled());
    }

    /** Operator onboards an agency; the owner's temporary password is returned once and never stored in clear. */
    @PostMapping("/platform/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> onboard(@RequestBody SignupService.OnboardRequest req) {
        UUID operator = requirePlatform();
        var r = signup.onboard(req, operator);
        TenantContext.runAs(r.tenant().getId(), () -> {
            audit.record("PLATFORM_TENANT_ONBOARDED", "Tenant", r.tenant().getId(), Map.of("plan", r.tenant().getPlan(), "by", operator));
            return null;
        });
        Map<String, Object> out = new java.util.LinkedHashMap<>(view(r.tenant()));
        out.put("ownerLogin", r.ownerLogin());
        out.put("temporaryPassword", r.temporaryPassword());
        out.put("existingUser", r.existingUser());
        return out;
    }

    public record TenantUpdate(String status, String plan, Integer maxEmployees, Integer maxSites, Integer maxUsers, Boolean unlimited,
                               Integer extendTrialDays, String reason, String notes, String contactName, String contactPhone) {}

    /**
     * Change status, plan or limits, or extend a trial. Suspending or closing signs every member out at once.
     * Choosing a plan resets limits to the plan's defaults unless limits are given in the same request.
     */
    // Not one transaction: the audit entry is written under the agency's own row-level security (TenantContext.runAs),
    // which needs a connection bound to that agency.
    @PatchMapping("/platform/tenants/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody TenantUpdate req) {
        requirePlatform();
        Tenant t = tenants.findById(id).orElseThrow(() -> ApiException.notFound("Tenant"));
        String before = t.getStatus();
        if (req.status() != null) {
            if (!List.of("TRIAL", "ACTIVE", "SUSPENDED", "CLOSED").contains(req.status())) throw ApiException.field("status", "Invalid status");
            if (List.of("SUSPENDED", "CLOSED").contains(req.status()) && (req.reason() == null || req.reason().isBlank()))
                throw ApiException.field("reason", "Say why (shown to the agency's users)");
            t.setStatus(req.status());
            t.setStatusReason(req.reason());
            if ("ACTIVE".equals(req.status())) t.setTrialEndsAt(null);
        }
        if (req.plan() != null) {
            var p = PlanCatalog.get(req.plan());
            if (p == null) throw ApiException.field("plan", "Unknown plan");
            t.setPlan(p.code());
            SignupService.applyPlan(t, p);
        }
        if (Boolean.TRUE.equals(req.unlimited())) { t.setMaxEmployees(null); t.setMaxSites(null); t.setMaxUsers(null); }
        if (req.maxEmployees() != null) t.setMaxEmployees(positive("maxEmployees", req.maxEmployees()));
        if (req.maxSites() != null) t.setMaxSites(positive("maxSites", req.maxSites()));
        if (req.maxUsers() != null) t.setMaxUsers(positive("maxUsers", req.maxUsers()));
        if (req.extendTrialDays() != null) {
            if (req.extendTrialDays() < 1 || req.extendTrialDays() > 365) throw ApiException.field("extendTrialDays", "1 to 365");
            java.time.Instant base = t.getTrialEndsAt() == null || t.getTrialEndsAt().isBefore(java.time.Instant.now())
                    ? java.time.Instant.now() : t.getTrialEndsAt();
            t.setTrialEndsAt(base.plus(req.extendTrialDays(), java.time.temporal.ChronoUnit.DAYS));
            t.setStatus("TRIAL");
        }
        if (req.notes() != null) t.setPlatformNotes(req.notes());
        if (req.contactName() != null) t.setContactName(req.contactName());
        if (req.contactPhone() != null) t.setContactPhone(req.contactPhone());
        tenants.saveAndFlush(t);
        if (List.of("SUSPENDED", "CLOSED").contains(t.getStatus()) && !t.getStatus().equals(before)) {
            List<UUID> members = jdbc.queryForList("select user_id from memberships where tenant_id = ? and status = 'ACTIVE'", UUID.class, id);
            for (UUID u : members) {
                if (users.findById(u).map(User::isPlatformAdmin).orElse(false)) continue;
                jdbc.update("update users set token_epoch = token_epoch + 1, updated_at = now() where id = ?", u);
                revocation.evict(u);
            }
        }
        TenantContext.runAs(id, () -> {
            audit.record("PLATFORM_TENANT_UPDATE", "Tenant", id, Map.of("status", t.getStatus(), "plan", t.getPlan(),
                    "reason", req.reason() == null ? "" : req.reason()));
            return null;
        });
        return view(t);
    }

    /** Agency owner lost their password: one-time temporary password, all their sessions ended. */
    @PostMapping("/platform/tenants/{id}/reset-owner-password")
    public Map<String, Object> resetOwnerPassword(@PathVariable UUID id) {
        requirePlatform();
        tenants.findById(id).orElseThrow(() -> ApiException.notFound("Tenant"));
        List<UUID> owners = jdbc.queryForList("""
                select user_id from memberships where tenant_id = ? and role = 'OWNER' and status = 'ACTIVE' order by created_at limit 1""", UUID.class, id);
        if (owners.isEmpty()) throw ApiException.invalidState("This agency has no active owner");
        User u = users.findById(owners.get(0)).orElseThrow();
        if (u.isPlatformAdmin()) throw ApiException.invalidState("The owner is a platform admin; they must change it themselves");
        Long other = jdbc.queryForObject("select count(*) from memberships where user_id = ? and tenant_id <> ? and status = 'ACTIVE'",
                Long.class, u.getId(), id);
        if (other != null && other > 0) throw ApiException.invalidState("The owner also uses another agency; they must reset it themselves");
        String temp = SignupService.temporaryPassword();
        u.setPasswordHash(encoder.encode(temp));
        u.setTokenEpoch(u.getTokenEpoch() + 1);
        users.save(u);
        revocation.evict(u.getId());
        TenantContext.runAs(id, () -> {
            audit.record("PLATFORM_OWNER_PASSWORD_RESET", "Tenant", id, Map.of());
            return null;
        });
        return Map.of("login", u.getEmail() != null ? u.getEmail() : u.getPhone(), "temporaryPassword", temp);
    }

    private static Integer positive(String f, Integer v) {
        if (v < 1) throw ApiException.field(f, "Must be at least 1");
        return v;
    }
}

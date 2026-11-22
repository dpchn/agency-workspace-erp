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

    // ---------------- platform operator: plans and tenant health only, no tenant HR/finance data
    private void requirePlatform() {
        if (!Authz.user().platformAdmin()) throw ApiException.notFound("Resource");
    }

    public record TenantSummary(UUID id, String slug, String name, String status, String plan, long activeUsers, Object trialEndsAt) {}

    @GetMapping("/platform/tenants")
    @Transactional(readOnly = true)
    public List<TenantSummary> list() {
        requirePlatform();
        return tenants.findAll().stream().map(t -> new TenantSummary(t.getId(), t.getSlug(), t.getName(), t.getStatus(), t.getPlan(),
                memberships.countByTenantIdAndStatus(t.getId(), "ACTIVE"), t.getTrialEndsAt())).toList();
    }

    public record TenantStatusChange(String status, String plan, Integer maxEmployees, String reason) {}

    @PatchMapping("/platform/tenants/{id}")
    @Transactional
    public TenantSummary changeStatus(@PathVariable UUID id, @RequestBody TenantStatusChange req) {
        requirePlatform();
        Tenant t = tenants.findById(id).orElseThrow(() -> ApiException.notFound("Tenant"));
        if (req.status() != null) {
            if (!List.of("TRIAL", "ACTIVE", "SUSPENDED", "CLOSED").contains(req.status())) throw ApiException.field("status", "Invalid status");
            t.setStatus(req.status());
        }
        if (req.plan() != null) t.setPlan(req.plan());
        if (req.maxEmployees() != null) t.setMaxEmployees(req.maxEmployees());
        TenantContext.runAs(id, () -> {
            audit.record("PLATFORM_TENANT_UPDATE", "Tenant", id, Map.of("status", t.getStatus(), "plan", t.getPlan(),
                    "reason", req.reason() == null ? "" : req.reason()));
            return null;
        });
        return new TenantSummary(t.getId(), t.getSlug(), t.getName(), t.getStatus(), t.getPlan(),
                memberships.countByTenantIdAndStatus(id, "ACTIVE"), t.getTrialEndsAt());
    }
}

package app.erp.platform;

import app.erp.common.AuditService;
import app.erp.security.Authz;
import app.erp.security.Perm;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Organisation profile, business policies and white-label branding of the current tenant. */
@RestController
@RequestMapping("/v1/settings")
public class SettingsController {
    private final TenantService tenantService;
    private final AuditService audit;

    public SettingsController(TenantService tenantService, AuditService audit) {
        this.tenantService = tenantService;
        this.audit = audit;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Map<String, Object> get() {
        Authz.require(Perm.MASTER_READ);
        return view(tenantService.current());
    }

    public record SettingsUpdate(String name, String legalName, String gstin, String stateCode, String timezone,
                                 Map<String, Object> settings, Map<String, Object> branding) {}

    @PutMapping
    @Transactional
    public Map<String, Object> update(@RequestBody SettingsUpdate req) {
        Authz.require(Perm.SETTINGS_WRITE);
        Tenant t = tenantService.current();
        Map<String, Object> before = view(t);
        if (req.name() != null) t.setName(req.name());
        if (req.legalName() != null) t.setLegalName(req.legalName());
        if (req.gstin() != null) t.setGstin(req.gstin());
        if (req.stateCode() != null) t.setStateCode(req.stateCode());
        if (req.timezone() != null) {
            java.time.ZoneId.of(req.timezone());
            t.setTimezone(req.timezone());
        }
        if (req.settings() != null) {
            Map<String, Object> s = new HashMap<>(t.getSettings());
            req.settings().forEach((k, v) -> {
                if (!TenantSettings.DEFAULTS.containsKey(k)) throw app.erp.common.ApiException.field("settings." + k, "Unknown setting");
                if (v == null) s.remove(k); else s.put(k, v);
            });
            t.setSettings(s);
        }
        if (req.branding() != null) {
            Map<String, Object> b = new HashMap<>(t.getBranding());
            b.putAll(req.branding());
            t.setBranding(b);
        }
        Map<String, Object> after = view(t);
        audit.record("SETTINGS_UPDATE", "Tenant", t.getId(), before, after, Map.of());
        return after;
    }

    private Map<String, Object> view(Tenant t) {
        Map<String, Object> effective = new LinkedHashMap<>(TenantSettings.DEFAULTS);
        effective.putAll(t.getSettings());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.getId());
        out.put("name", t.getName());
        out.put("legalName", t.getLegalName());
        out.put("slug", t.getSlug());
        out.put("status", t.getStatus());
        out.put("plan", t.getPlan());
        out.put("gstin", t.getGstin());
        out.put("stateCode", t.getStateCode());
        out.put("timezone", t.getTimezone());
        out.put("currency", t.getCurrency());
        out.put("settings", effective);
        out.put("branding", t.getBranding());
        return out;
    }
}

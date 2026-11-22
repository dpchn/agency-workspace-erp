package app.erp.platform;

import app.erp.common.ApiException;
import app.erp.common.TenantContext;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TenantService {
    private final TenantRepository tenants;

    public TenantService(TenantRepository tenants) { this.tenants = tenants; }

    public Tenant current() {
        UUID id = TenantContext.requireTenant();
        return tenants.findById(id).orElseThrow(() -> ApiException.notFound("Tenant"));
    }

    public TenantSettings settings() { return new TenantSettings(current().getSettings()); }

    public ZoneId zone() { return ZoneId.of(current().getTimezone()); }
}

package app.erp.common;

import java.util.UUID;

/** Holds the tenant + principal for the current request / job. Never populated from client input. */
public final class TenantContext {
    private static final ThreadLocal<CurrentUser> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<UUID> SYSTEM_TENANT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(CurrentUser user) { CURRENT.set(user); }
    public static CurrentUser user() { return CURRENT.get(); }

    public static UUID tenantId() {
        UUID sys = SYSTEM_TENANT.get();
        if (sys != null) return sys;
        CurrentUser u = CURRENT.get();
        return u == null ? null : u.tenantId();
    }

    public static UUID requireTenant() {
        UUID t = tenantId();
        if (t == null) throw ApiException.forbidden("No tenant selected");
        return t;
    }

    /** Run work as the system for a given tenant (background jobs, signup seeding). */
    public static <T> T runAs(UUID tenantId, java.util.function.Supplier<T> work) {
        UUID previous = SYSTEM_TENANT.get();
        SYSTEM_TENANT.set(tenantId);
        try {
            return work.get();
        } finally {
            if (previous == null) SYSTEM_TENANT.remove(); else SYSTEM_TENANT.set(previous);
        }
    }

    public static void clear() {
        CURRENT.remove();
        SYSTEM_TENANT.remove();
    }
}

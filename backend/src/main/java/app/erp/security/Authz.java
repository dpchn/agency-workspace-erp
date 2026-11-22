package app.erp.security;

import app.erp.common.ApiException;
import app.erp.common.CurrentUser;
import app.erp.common.TenantContext;
import java.util.UUID;

/** Programmatic authorization helpers used by services (explicit deny by default). */
public final class Authz {
    private Authz() {}

    public static CurrentUser user() {
        CurrentUser u = TenantContext.user();
        if (u == null) throw ApiException.forbidden("Not authenticated");
        return u;
    }

    public static boolean has(Perm p) {
        CurrentUser u = TenantContext.user();
        return u != null && u.role() != null && u.role().perms().contains(p);
    }

    public static CurrentUser require(Perm p) {
        CurrentUser u = user();
        if (u.role() == null || !u.role().perms().contains(p)) throw ApiException.forbidden("Missing permission " + p);
        return u;
    }

    public static CurrentUser requireAny(Perm... ps) {
        for (Perm p : ps) if (has(p)) return user();
        throw ApiException.forbidden("Missing permission");
    }

    /** Site-scoped users (supervisors, client users) may only touch their sites. Non-disclosing. */
    public static void requireSite(UUID siteId, String entity) {
        CurrentUser u = user();
        if (!u.siteAllowed(siteId)) throw ApiException.notFound(entity);
    }

    public static UUID userId() {
        CurrentUser u = TenantContext.user();
        return u == null ? null : u.userId();
    }
}

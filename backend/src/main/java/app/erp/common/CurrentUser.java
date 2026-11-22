package app.erp.common;

import app.erp.security.Role;
import java.util.List;
import java.util.UUID;

public record CurrentUser(
        UUID userId,
        UUID tenantId,
        UUID membershipId,
        Role role,
        List<UUID> branchIds,   // null = unrestricted
        List<UUID> siteIds,     // null = unrestricted
        UUID clientId,
        UUID employeeId,
        boolean platformAdmin,
        String name) {

    public boolean siteAllowed(UUID siteId) {
        return siteIds == null || siteIds.contains(siteId);
    }

    public boolean branchAllowed(UUID branchId) {
        return branchIds == null || branchIds.contains(branchId);
    }

    public boolean isClientUser() { return role == Role.CLIENT_ADMIN || role == Role.CLIENT_VIEWER; }
    public boolean isGuard() { return role == Role.GUARD; }
}

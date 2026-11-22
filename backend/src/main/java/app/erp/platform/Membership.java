package app.erp.platform;

import app.erp.common.BaseEntity;
import app.erp.security.Role;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Global table (no RLS) so login can list a user's tenants; always queried by user or tenant explicitly. */
@Getter
@Setter
@Entity
@Table(name = "memberships")
public class Membership extends BaseEntity {
    private UUID tenantId;
    private UUID userId;
    @Enumerated(EnumType.STRING)
    private Role role;
    private UUID[] branchIds;
    private UUID[] siteIds;
    private UUID clientId;
    private UUID employeeId;
    private String status = "ACTIVE";
}

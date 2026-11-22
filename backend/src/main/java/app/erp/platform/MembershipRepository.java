package app.erp.platform;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {
    List<Membership> findByUserIdAndStatus(UUID userId, String status);
    Optional<Membership> findByTenantIdAndUserId(UUID tenantId, UUID userId);
    List<Membership> findByTenantId(UUID tenantId);
    Optional<Membership> findByIdAndTenantId(UUID id, UUID tenantId);
    long countByTenantIdAndStatus(UUID tenantId, String status);
}

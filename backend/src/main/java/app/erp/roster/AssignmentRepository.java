package app.erp.roster;

import app.erp.common.TenantRepo;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;

public interface AssignmentRepository extends TenantRepo<Assignment> {
    List<Assignment> findByShiftIdIn(Collection<UUID> shiftIds);
    List<Assignment> findByShiftId(UUID shiftId);

    @Query("""
        select a from Assignment a where a.employeeId = :employeeId
          and a.status in ('PLANNED','CONFIRMED','COMPLETED')
          and a.startAt < :to and a.endAt > :from order by a.startAt""")
    List<Assignment> activeBetween(UUID employeeId, Instant from, Instant to);
}

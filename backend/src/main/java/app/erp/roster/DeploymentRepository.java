package app.erp.roster;

import app.erp.common.TenantRepo;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;

public interface DeploymentRepository extends TenantRepo<Deployment> {
    @Query("""
        select d from Deployment d where d.status = 'ACTIVE' and d.startDate <= :to
          and (d.endDate is null or d.endDate >= :from)""")
    List<Deployment> activeBetween(LocalDate from, LocalDate to);

    @Query("""
        select d from Deployment d where d.employeeId = :employeeId and d.status = 'ACTIVE'
          and d.startDate <= :on and (d.endDate is null or d.endDate >= :on)""")
    List<Deployment> activeFor(UUID employeeId, LocalDate on);

    List<Deployment> findByEmployeeIdOrderByStartDateDesc(UUID employeeId);
}

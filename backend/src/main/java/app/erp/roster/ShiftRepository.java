package app.erp.roster;

import app.erp.common.TenantRepo;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ShiftRepository extends TenantRepo<Shift> {
    List<Shift> findByDutyDateBetweenOrderByStartAt(LocalDate from, LocalDate to);
    List<Shift> findBySiteIdAndDutyDateBetweenOrderByStartAt(UUID siteId, LocalDate from, LocalDate to);
}

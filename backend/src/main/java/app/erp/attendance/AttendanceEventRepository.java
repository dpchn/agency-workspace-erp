package app.erp.attendance;

import app.erp.common.TenantRepo;
import java.util.Optional;
import java.util.UUID;

public interface AttendanceEventRepository extends TenantRepo<AttendanceEvent> {
    Optional<AttendanceEvent> findByClientEventId(UUID clientEventId);
}

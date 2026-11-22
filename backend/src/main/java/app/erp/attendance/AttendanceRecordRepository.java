package app.erp.attendance;

import app.erp.common.TenantRepo;
import java.util.Optional;
import java.util.UUID;

public interface AttendanceRecordRepository extends TenantRepo<AttendanceRecord> {
    Optional<AttendanceRecord> findByAssignmentId(UUID assignmentId);
}

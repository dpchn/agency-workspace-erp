package app.erp.attendance;

import app.erp.common.TenantEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "attendance_records")
public class AttendanceRecord extends TenantEntity {
    private UUID assignmentId;
    private UUID employeeId;
    private UUID shiftId;
    private UUID siteId;
    private UUID postId;
    private UUID clientId;
    private UUID serviceTypeId;
    private LocalDate dutyDate;
    private Instant checkInAt;
    private Instant checkOutAt;
    private Integer workedMinutes = 0;
    private Integer lateMinutes = 0;
    private Integer otMinutes = 0;
    /** PENDING | PRESENT | HALF_DAY | ABSENT | ON_LEAVE */
    private String status = "PENDING";
    /** PENDING | APPROVED | REJECTED */
    private String approvalStatus = "PENDING";
    /** Units used by payroll (1 = full duty, 0.5 = half). */
    private BigDecimal payUnits = BigDecimal.ZERO;
    /** Units used by billing; may differ from pay units (e.g. internal cover is not billable). */
    private BigDecimal billUnits = BigDecimal.ZERO;
    private Boolean billable = true;
    private Boolean isHoliday = false;
    private String assignmentRole = "REGULAR";
    private String shiftKind = "REGULAR";
    private String[] flags = new String[0];
    /** How this duty was evidenced: MOBILE, KIOSK, SUPERVISOR, MANUAL (union over its events). */
    private String[] sources = new String[0];
    /** Users who recorded its punches; they may not approve it themselves. */
    @JsonIgnore
    private UUID[] markedBy = new UUID[0];
    private UUID approvedBy;
    private Instant approvedAt;
    private UUID payrollRunId;
    private UUID invoiceId;

    public boolean lockedForPayroll() { return payrollRunId != null; }
}

package app.erp.attendance;

import app.erp.common.TenantEntity;
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
@Table(name = "attendance_adjustments")
public class AttendanceAdjustment extends TenantEntity {
    private UUID recordId;
    private Instant proposedCheckInAt;
    private Instant proposedCheckOutAt;
    private String proposedStatus;
    private String reason;
    private String evidenceKey;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> beforeState;
    private String status = "PENDING";
    private UUID requestedBy;
    private UUID decidedBy;
    private Instant decidedAt;
    private String decisionNote;
}

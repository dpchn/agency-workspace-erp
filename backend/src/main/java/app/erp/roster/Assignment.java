package app.erp.roster;

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
@Table(name = "assignments")
public class Assignment extends TenantEntity {
    private UUID shiftId;
    private UUID employeeId;
    private Instant startAt;
    private Instant endAt;
    /** REGULAR | RELIEF | REPLACEMENT | DOUBLE_DUTY */
    private String role = "REGULAR";
    private UUID replacesAssignmentId;
    /** PLANNED | CONFIRMED | COMPLETED | NO_SHOW | CANCELLED */
    private String status = "PLANNED";
    private String reason;
    private UUID createdBy;
    private UUID approvedBy;
}

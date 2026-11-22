package app.erp.payroll;

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
@Table(name = "employee_adjustments")
public class EmployeeAdjustment extends TenantEntity {
    private UUID employeeId;
    private LocalDate periodMonth;
    /** ADVANCE_RECOVERY | LOAN_EMI | UNIFORM | FINE | BONUS | ALLOWANCE | OTHER_EARNING | OTHER_DEDUCTION */
    private String kind;
    private BigDecimal amount;
    private String notes;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private UUID payrollRunId;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private UUID createdBy;

    public boolean isEarning() { return Set.of("BONUS", "ALLOWANCE", "OTHER_EARNING").contains(kind); }
}

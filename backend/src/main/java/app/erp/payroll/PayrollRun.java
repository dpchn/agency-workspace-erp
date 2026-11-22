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
@Table(name = "payroll_runs")
public class PayrollRun extends TenantEntity {
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private UUID branchId;
    private Integer versionNo = 1;
    private String status = "DRAFT";
    private Integer employeeCount = 0;
    private BigDecimal totalGross = BigDecimal.ZERO;
    private BigDecimal totalDeductions = BigDecimal.ZERO;
    private BigDecimal totalNet = BigDecimal.ZERO;
    private BigDecimal totalEmployerCost = BigDecimal.ZERO;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<Map<String, Object>> exceptions = new ArrayList<>();
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> ruleSnapshot;
    private UUID createdBy;
    private UUID reviewedBy;
    private UUID approvedBy;
    private Instant lockedAt;
    private Instant paidAt;
}

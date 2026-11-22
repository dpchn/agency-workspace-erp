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
@Table(name = "payslips")
public class Payslip extends TenantEntity {
    private UUID runId;
    private UUID employeeId;
    private BigDecimal duties = BigDecimal.ZERO;
    private BigDecimal otHours = BigDecimal.ZERO;
    private BigDecimal leaveDays = BigDecimal.ZERO;
    private BigDecimal gross;
    private BigDecimal totalDeductions;
    private BigDecimal net;
    private BigDecimal employerContributions;
    private String paymentStatus = "UNPAID";
    private String paymentRef;
}

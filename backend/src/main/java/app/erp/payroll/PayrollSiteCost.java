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
@Table(name = "payroll_site_costs")
public class PayrollSiteCost extends TenantEntity {
    private UUID runId;
    private UUID employeeId;
    private UUID siteId;
    private UUID clientId;
    private BigDecimal duties;
    private BigDecimal wageAmount;
    private BigDecimal employerCost;
}

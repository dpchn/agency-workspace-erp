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
@Table(name = "payroll_lines")
public class PayrollLine extends TenantEntity {
    private UUID runId;
    private UUID employeeId;
    private String componentCode;
    private String componentName;
    private String kind;
    private BigDecimal quantity;
    private BigDecimal rate;
    private BigDecimal amount;
    private UUID siteId;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> source;
}

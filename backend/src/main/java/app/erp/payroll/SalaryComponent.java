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
@Table(name = "salary_components")
public class SalaryComponent extends TenantEntity {
    private String code;
    private String name;
    /** EARNING | DEDUCTION | EMPLOYER_CONTRIBUTION */
    private String kind;
    /** PERCENT | FIXED | SLAB */
    private String calcType;
    /** DUTY_WAGE | OT_PAY | GROSS | another component code */
    private String base;
    private BigDecimal percent;
    private BigDecimal fixedAmount;
    private BigDecimal baseCeiling;
    private BigDecimal appliesIfGrossUpto;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<Map<String, Object>> slabs;
    private String stateCode;
    private Integer sequence = 100;
    private Boolean active = true;
    private LocalDate effectiveFrom = LocalDate.of(2000, 1, 1);
    private LocalDate effectiveTo;
}

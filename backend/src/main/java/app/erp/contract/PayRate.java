package app.erp.contract;

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
@Table(name = "pay_rates")
public class PayRate extends TenantEntity {
    /** Scope: leave a field null to match any. More specific matches win. */
    private UUID serviceTypeId;
    private UUID clientId;
    private UUID siteId;
    private UUID employeeId;
    private UUID branchId;
    /** MONTHLY | DAILY | HOURLY */
    private String wageBasis;
    private BigDecimal rate;
    private BigDecimal dutyAllowance = BigDecimal.ZERO;
    private BigDecimal extraDutyRate;
    private BigDecimal otHourlyRate;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private String notes;

    public boolean coversDate(LocalDate d) {
        return !d.isBefore(effectiveFrom) && (effectiveTo == null || !d.isAfter(effectiveTo));
    }
}

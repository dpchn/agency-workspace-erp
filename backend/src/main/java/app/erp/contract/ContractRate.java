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
@Table(name = "contract_rates")
public class ContractRate extends TenantEntity {
    private UUID contractVersionId;
    private UUID siteId;
    private UUID serviceTypeId;
    /** MONTHLY_PER_POST | PER_SHIFT | PER_DAY | PER_HOUR | PER_HEADCOUNT_MONTH | LUMPSUM_MONTHLY */
    private String billingModel;
    private BigDecimal rate;
    private BigDecimal quantity = BigDecimal.ONE;
    private BigDecimal shiftHours = new BigDecimal("8");
    private Boolean attendanceLinked = true;
    private BigDecimal extraShiftRate;
    private BigDecimal otHourlyRate;
    private BigDecimal holidayMultiplier = BigDecimal.ONE;
    private BigDecimal minimumUnits;
    private String description;
}

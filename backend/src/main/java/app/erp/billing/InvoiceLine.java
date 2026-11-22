package app.erp.billing;

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
@Table(name = "invoice_lines")
public class InvoiceLine extends TenantEntity {
    private UUID invoiceId;
    private UUID siteId;
    private UUID serviceTypeId;
    private UUID contractRateId;
    /** SERVICE | EXTRA_SHIFT | OVERTIME | DEDUCTION | PENALTY | MINIMUM_TOPUP | MANUAL */
    private String lineType = "SERVICE";
    private String description;
    private BigDecimal quantity;
    private String unit;
    private BigDecimal rate;
    private BigDecimal amount;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> sourceRef;
}

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
@Table(name = "receipts")
public class Receipt extends TenantEntity {
    private UUID clientId;
    private BigDecimal amount;
    private LocalDate receivedOn;
    private String mode = "BANK_TRANSFER";
    private String externalRef;
    private BigDecimal tdsAmount = BigDecimal.ZERO;
    private BigDecimal unallocated = BigDecimal.ZERO;
    private String notes;
    private UUID createdBy;
}

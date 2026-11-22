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
@Table(name = "receipt_allocations")
public class ReceiptAllocation extends TenantEntity {
    private UUID receiptId;
    private UUID invoiceId;
    private BigDecimal amount;
}

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
@Table(name = "invoices")
public class Invoice extends TenantEntity {
    private UUID clientId;
    private UUID contractId;
    /** INVOICE | CREDIT_NOTE | DEBIT_NOTE */
    private String kind = "INVOICE";
    private UUID refInvoiceId;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String invoiceNo;
    private Integer versionNo = 1;
    private String status = "DRAFT";
    private LocalDate issueDate;
    private LocalDate dueDate;
    private String placeOfSupply;
    private String supplierState;
    private BigDecimal subtotal = BigDecimal.ZERO;
    private BigDecimal cgst = BigDecimal.ZERO;
    private BigDecimal sgst = BigDecimal.ZERO;
    private BigDecimal igst = BigDecimal.ZERO;
    private BigDecimal total = BigDecimal.ZERO;
    private BigDecimal amountPaid = BigDecimal.ZERO;
    private BigDecimal gstRatePct = new BigDecimal("18");
    private String notes;
    private String disputeReason;
    private UUID createdBy;
    private UUID approvedBy;

    public BigDecimal balance() { return total.subtract(amountPaid); }
}

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
@Table(name = "contract_versions")
public class ContractVersion extends TenantEntity {
    private UUID contractId;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Integer versionNo;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String status = "DRAFT";
    private BigDecimal gstRatePct = new BigDecimal("18");
    /** Optional penalty per uncovered duty, on top of attendance-linked reduction. */
    private BigDecimal absencePenaltyPerDuty;
    private String notes;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private UUID createdBy;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private UUID approvedBy;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Instant approvedAt;

    public boolean coversDate(LocalDate d) {
        return !d.isBefore(effectiveFrom) && (effectiveTo == null || !d.isAfter(effectiveTo));
    }
}

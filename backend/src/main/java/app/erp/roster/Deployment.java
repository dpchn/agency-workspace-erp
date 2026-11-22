package app.erp.roster;

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
@Table(name = "deployments")
public class Deployment extends TenantEntity {
    private UUID employeeId;
    private UUID siteId;
    private UUID postId;
    private UUID serviceTypeId;
    private UUID shiftTemplateId;
    /** PERMANENT (home posting) | TEMPORARY (short-term elsewhere, overrides permanent) | RELIEVER (floating pool) */
    private String deploymentType = "PERMANENT";
    private LocalDate startDate;
    private LocalDate endDate;
    private String reason;
    private String status = "ACTIVE";
    private UUID createdBy;

    public boolean activeOn(LocalDate d) {
        return "ACTIVE".equals(status) && !d.isBefore(startDate) && (endDate == null || !d.isAfter(endDate));
    }
}

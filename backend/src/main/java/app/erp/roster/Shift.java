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
@Table(name = "shifts")
public class Shift extends TenantEntity {
    private UUID siteId;
    private UUID postId;
    private UUID serviceTypeId;
    private UUID shiftTemplateId;
    private LocalDate dutyDate;
    private Instant startAt;
    private Instant endAt;
    private Integer requiredHeadcount = 1;
    /** REGULAR (contracted) | EXTRA (additional) */
    private String kind = "REGULAR";
    /** EXTRA shifts the client asked for are billable; internal cover is not. */
    private Boolean clientRequested = false;
    private String status = "OPEN";
    private String notes;
}

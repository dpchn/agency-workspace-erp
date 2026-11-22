package app.erp.ops;

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
@Table(name = "incidents")
public class Incident extends TenantEntity {
    private UUID siteId;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private UUID reportedBy;
    private UUID reporterEmployeeId;
    private String title;
    private String description;
    private String category;
    /** LOW | MEDIUM | HIGH | CRITICAL */
    private String severity;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String status = "OPEN";
    private UUID assignedTo;
    private Instant occurredAt;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Instant responseDueAt;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Instant acknowledgedAt;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private UUID acknowledgedBy;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Integer escalationLevel = 0;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Instant closedAt;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private UUID closedBy;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String closureNotes;
    private Boolean clientVisible = false;
    private String[] attachments = new String[0];
}

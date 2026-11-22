package app.erp.platform;

import app.erp.common.BaseEntity;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "tenants")
public class Tenant extends BaseEntity {
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String slug;
    private String name;
    private String legalName;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String status;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String plan;
    private String gstin;
    private String stateCode;
    private String timezone;
    private String currency;
    private Integer fiscalYearStartMonth;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> settings = new HashMap<>();
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> branding = new HashMap<>();
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Integer maxEmployees;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Instant trialEndsAt;
}

package app.erp.org;

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
@Table(name = "service_types")
public class ServiceType extends TenantEntity {
    private String code;
    private String name;
    private String category = "SECURITY";
    private BigDecimal defaultShiftHours = new BigDecimal("8");
    /** Documents that must be VERIFIED and unexpired before deployment, e.g. POLICE_VERIFICATION. */
    private String[] requiredDocuments;
    private Boolean active = true;
}

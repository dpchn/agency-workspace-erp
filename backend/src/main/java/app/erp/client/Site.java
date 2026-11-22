package app.erp.client;

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
@Table(name = "sites")
public class Site extends TenantEntity {
    private UUID clientId;
    private UUID branchId;
    private String code;
    private String name;
    private String address;
    private String stateCode;
    private String timezone;
    private Double latitude;
    private Double longitude;
    @Column(name = "geofence_radius_m")
    private Integer geofenceRadiusM = 200;
    /** ENFORCE rejects check-ins outside the fence, FLAG accepts but flags for review, OFF ignores location. */
    private String geofencePolicy = "FLAG";
    private String status = "ACTIVE";
}

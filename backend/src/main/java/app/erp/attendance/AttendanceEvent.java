package app.erp.attendance;

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
@Table(name = "attendance_events")
public class AttendanceEvent extends TenantEntity {
    private UUID clientEventId;
    private UUID employeeId;
    private UUID assignmentId;
    private String kind;
    private Instant occurredAt;
    private Instant receivedAt;
    private Double latitude;
    private Double longitude;
    @Column(name = "accuracy_m")
    private Double accuracyM;
    @Column(name = "distance_m")
    private Double distanceM;
    private String deviceId;
    private String selfieHash;
    private String source = "MOBILE";
    private Boolean offline = false;
    private String[] flags = new String[0];
    private String status = "ACCEPTED";
    private UUID recordedBy;
}

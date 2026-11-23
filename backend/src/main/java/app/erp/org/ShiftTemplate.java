package app.erp.org;

import app.erp.common.TenantEntity;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.descriptor.jdbc.LocalTimeJdbcType;

@Getter
@Setter
@Entity
@Table(name = "shift_templates")
public class ShiftTemplate extends TenantEntity {
    private String code;
    private String name;
    // Wall-clock times: read/written as-is. Without this, hibernate.jdbc.time_zone=UTC shifts them by the
    // server's UTC offset (a server in IST would roster an 08:00 shift at 13:30).
    @JdbcType(LocalTimeJdbcType.class)
    private LocalTime startTime;
    @JdbcType(LocalTimeJdbcType.class)
    private LocalTime endTime;
    private Integer breakMinutes = 0;
    private Boolean active = true;

    public boolean crossesMidnight() { return !endTime.isAfter(startTime); }

    public Duration length() {
        Duration d = Duration.between(startTime, endTime);
        return d.isNegative() || d.isZero() ? d.plusDays(1) : d;
    }
}

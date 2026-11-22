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
@Table(name = "shift_templates")
public class ShiftTemplate extends TenantEntity {
    private String code;
    private String name;
    private LocalTime startTime;
    private LocalTime endTime;
    private Integer breakMinutes = 0;
    private Boolean active = true;

    public boolean crossesMidnight() { return !endTime.isAfter(startTime); }

    public Duration length() {
        Duration d = Duration.between(startTime, endTime);
        return d.isNegative() || d.isZero() ? d.plusDays(1) : d;
    }
}

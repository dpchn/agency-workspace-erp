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
@Table(name = "post_shift_requirements")
public class PostShiftRequirement extends TenantEntity {
    private UUID postId;
    private UUID shiftTemplateId;
    private Integer headcount = 1;
    /** ISO weekdays 1=Mon..7=Sun on which this shift is required. */
    private Integer[] daysOfWeek = {1, 2, 3, 4, 5, 6, 7};
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;

    public boolean activeOn(LocalDate d) {
        if (d.isBefore(effectiveFrom) || (effectiveTo != null && d.isAfter(effectiveTo))) return false;
        return Arrays.asList(daysOfWeek).contains(d.getDayOfWeek().getValue());
    }
}

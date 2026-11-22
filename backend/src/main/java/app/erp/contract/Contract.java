package app.erp.contract;

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
@Table(name = "contracts")
public class Contract extends TenantEntity {
    private UUID clientId;
    private String contractNo;
    private String title;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String status = "DRAFT";
    private LocalDate startDate;
    private LocalDate endDate;
    private Boolean autoRenew = false;
    private Integer paymentTermsDays;
}

package app.erp.hr;

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
@Table(name = "employee_documents")
public class EmployeeDocument extends TenantEntity {
    private UUID employeeId;
    private String docType;
    private String docNumber;
    private LocalDate issuedOn;
    private LocalDate expiresOn;
    private String objectKey;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String verificationStatus = "PENDING";
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private UUID verifiedBy;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Instant verifiedAt;
    private String notes;
}

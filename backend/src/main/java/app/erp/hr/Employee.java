package app.erp.hr;

import app.erp.common.TenantEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
@Table(name = "employees")
public class Employee extends TenantEntity {
    private String employeeCode;
    private String firstName;
    private String lastName;
    private String phone;
    private String email;
    private String gender;
    private LocalDate dateOfBirth;
    private UUID branchId;
    private UUID serviceTypeId;
    private UUID[] qualifiedServiceTypeIds;
    /** DRAFT -> PENDING_VERIFICATION -> ACTIVE <-> SUSPENDED -> EXITED. Changed only via lifecycle endpoints. */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String status = "DRAFT";
    private LocalDate joinedOn;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDate exitedOn;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String exitReason;
    private Integer weeklyOffDay;
    private String uan;
    private String esicNo;
    private String pan;
    private String idNumberLast4;
    private String bankAccountNo;
    private String bankIfsc;
    private String bankName;
    private String address;
    private String emergencyContact;
    private Instant consentAt;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private UUID userId;
    /** False for workers who punch on a site kiosk or are marked by their supervisor. */
    private Boolean hasSmartphone = true;
    @JsonIgnore
    private String attendancePinHash;
    @JsonIgnore
    private Integer pinFailedCount = 0;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Instant pinLockedUntil;
    /** Reference photo shown on the kiosk / roll-call so the supervisor can match the face. */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private UUID profilePhotoId;

    @JsonProperty(value = "kioskPinSet", access = JsonProperty.Access.READ_ONLY)
    public boolean isKioskPinSet() { return attendancePinHash != null; }

    public String fullName() { return lastName == null || lastName.isBlank() ? firstName : firstName + " " + lastName; }

    public boolean qualifiedFor(UUID st) {
        if (st.equals(serviceTypeId)) return true;
        return qualifiedServiceTypeIds != null && Arrays.asList(qualifiedServiceTypeIds).contains(st);
    }
}

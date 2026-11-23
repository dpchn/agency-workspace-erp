package app.erp.attendance;

import app.erp.common.TenantEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Photo evidence: a selfie, a team photo from a roll-call, a scanned paper register, or a profile photo. */
@Getter
@Setter
@Entity
@Table(name = "attendance_photos")
public class AttendancePhoto extends TenantEntity {
    private String kind;
    private String contentType;
    private String sha256;
    private Integer sizeBytes;
    @JsonIgnore
    @Basic(fetch = FetchType.LAZY)
    private byte[] data;
    private UUID siteId;
    private UUID employeeId;
    private Instant takenAt;
    private UUID uploadedBy;
}

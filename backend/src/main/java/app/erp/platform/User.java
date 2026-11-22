package app.erp.platform;

import app.erp.common.BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "users")
public class User extends BaseEntity {
    private String email;
    private String phone;
    private String fullName;
    @JsonIgnore
    private String passwordHash;
    private boolean platformAdmin;
    private String status = "ACTIVE";
    @JsonIgnore
    private int tokenEpoch;
    private Instant lastLoginAt;
}

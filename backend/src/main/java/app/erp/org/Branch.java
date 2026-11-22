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
@Table(name = "branches")
public class Branch extends TenantEntity {
    private String code;
    private String name;
    private String stateCode;
    private String address;
    private String gstin;
    private Boolean active = true;
}

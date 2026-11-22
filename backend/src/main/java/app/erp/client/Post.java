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
@Table(name = "posts")
public class Post extends TenantEntity {
    private UUID siteId;
    private UUID serviceTypeId;
    private String code;
    private String name;
    private String instructions;
    private Boolean active = true;
}

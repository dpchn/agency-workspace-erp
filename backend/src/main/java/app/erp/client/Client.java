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
@Table(name = "clients")
public class Client extends TenantEntity {
    private String code;
    private String legalName;
    private String displayName;
    private String gstin;
    private String pan;
    private String billingAddress;
    private String stateCode;
    private String contactName;
    private String contactEmail;
    private String contactPhone;
    private Integer paymentTermsDays = 30;
    private BigDecimal creditLimit;
    private String status = "ACTIVE";
}

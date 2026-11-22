package app.erp.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

/**
 * Tenant-owned row. Hibernate fills and filters tenant_id automatically from
 * {@link TenantContext}; PostgreSQL RLS enforces the same rule underneath.
 */
@Getter
@Setter
@MappedSuperclass
public abstract class TenantEntity extends BaseEntity {
    @TenantId
    @JsonIgnore
    private UUID tenantId;
}

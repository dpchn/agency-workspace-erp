package app.erp.common;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;

@NoRepositoryBean
public interface TenantRepo<E extends TenantEntity> extends JpaRepository<E, UUID>, JpaSpecificationExecutor<E> {}

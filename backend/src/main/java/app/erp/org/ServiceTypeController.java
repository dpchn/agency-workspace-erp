package app.erp.org;

import app.erp.common.*;
import app.erp.security.Authz;
import app.erp.security.Perm;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/service-types")
public class ServiceTypeController extends CrudSupport<ServiceType> {
    public ServiceTypeController(ServiceTypeRepository repo, ObjectMapper mapper, AuditService audit) {
        super(ServiceType.class, repo, mapper, audit, Perm.MASTER_READ, Perm.MASTER_WRITE);
    }
    @Override protected Set<String> filterable() { return Set.of("active", "category", "code"); }
    @Override protected List<String> searchable() { return List.of("name", "code"); }
    @Override protected Sort defaultSort() { return Sort.by("name"); }
    @Override protected void checkRead() { Authz.requireAny(Perm.MASTER_READ, Perm.SELF_SERVICE, Perm.CLIENT_PORTAL); }
    @Override protected void validate(ServiceType e, boolean isNew) {
        if (e.getCode() == null || e.getCode().isBlank()) throw ApiException.field("code", "Required");
        if (e.getName() == null || e.getName().isBlank()) throw ApiException.field("name", "Required");
    }
}

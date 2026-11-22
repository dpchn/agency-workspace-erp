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
@RequestMapping("/v1/branches")
public class BranchController extends CrudSupport<Branch> {
    public BranchController(BranchRepository repo, ObjectMapper mapper, AuditService audit) {
        super(Branch.class, repo, mapper, audit, Perm.MASTER_READ, Perm.MASTER_WRITE);
    }
    @Override protected Set<String> filterable() { return Set.of("active", "code"); }
    @Override protected List<String> searchable() { return List.of("name", "code"); }
    @Override protected Sort defaultSort() { return Sort.by("code"); }
    @Override protected Specification<Branch> scope() {
        var u = Authz.user();
        return u.branchIds() == null ? null : (r, q, cb) -> r.get("id").in(u.branchIds());
    }
    @Override protected void validate(Branch e, boolean isNew) {
        if (e.getCode() == null || e.getCode().isBlank()) throw ApiException.field("code", "Required");
        if (e.getName() == null || e.getName().isBlank()) throw ApiException.field("name", "Required");
    }
}

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
@RequestMapping("/v1/shift-templates")
public class ShiftTemplateController extends CrudSupport<ShiftTemplate> {
    public ShiftTemplateController(ShiftTemplateRepository repo, ObjectMapper mapper, AuditService audit) {
        super(ShiftTemplate.class, repo, mapper, audit, Perm.MASTER_READ, Perm.MASTER_WRITE);
    }
    @Override protected Set<String> filterable() { return Set.of("active", "code"); }
    @Override protected Sort defaultSort() { return Sort.by("startTime"); }
    @Override protected void checkRead() { Authz.requireAny(Perm.MASTER_READ, Perm.SELF_SERVICE); }
    @Override protected void validate(ShiftTemplate e, boolean isNew) {
        if (e.getStartTime() == null || e.getEndTime() == null) throw ApiException.field("startTime", "Start and end time required");
        if (e.getStartTime().equals(e.getEndTime())) throw ApiException.field("endTime", "Use a 24h post as two 12h shifts");
    }
}

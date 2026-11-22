package app.erp.client;

import app.erp.common.*;
import app.erp.security.Authz;
import app.erp.security.Perm;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/post-requirements")
public class PostShiftRequirementController extends CrudSupport<PostShiftRequirement> {
    public PostShiftRequirementController(PostShiftRequirementRepository repo, ObjectMapper mapper, AuditService audit) {
        super(PostShiftRequirement.class, repo, mapper, audit, Perm.CLIENT_READ, Perm.CLIENT_WRITE);
    }
    @Override protected Set<String> filterable() { return Set.of("postId", "shiftTemplateId"); }
    @Override protected void validate(PostShiftRequirement e, boolean isNew) {
        if (e.getPostId() == null || e.getShiftTemplateId() == null) throw ApiException.field("postId", "Post and shift template required");
        if (e.getEffectiveFrom() == null) throw ApiException.field("effectiveFrom", "Required");
        if (e.getHeadcount() == null || e.getHeadcount() < 1) throw ApiException.field("headcount", "Must be at least 1");
        if (e.getDaysOfWeek() == null || e.getDaysOfWeek().length == 0) throw ApiException.field("daysOfWeek", "Pick at least one day");
        for (Integer d : e.getDaysOfWeek()) if (d == null || d < 1 || d > 7) throw ApiException.field("daysOfWeek", "1=Mon .. 7=Sun");
    }
}

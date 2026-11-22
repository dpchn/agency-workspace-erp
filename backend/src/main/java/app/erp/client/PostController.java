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
@RequestMapping("/v1/posts")
public class PostController extends CrudSupport<Post> {
    public PostController(PostRepository repo, ObjectMapper mapper, AuditService audit) {
        super(Post.class, repo, mapper, audit, Perm.CLIENT_READ, Perm.CLIENT_WRITE);
    }
    private SiteRepository sites;
    @org.springframework.beans.factory.annotation.Autowired
    void deps(SiteRepository s) { sites = s; }

    @Override protected Set<String> filterable() { return Set.of("siteId", "serviceTypeId", "active"); }
    @Override protected List<String> searchable() { return List.of("name", "code"); }
    @Override protected Sort defaultSort() { return Sort.by("code"); }
    @Override protected Specification<Post> scope() {
        var u = Authz.user();
        return u.siteIds() == null ? null : (r, q, cb) -> r.get("siteId").in(u.siteIds());
    }
    @Override protected void validate(Post e, boolean isNew) {
        if (e.getSiteId() == null || !sites.existsById(e.getSiteId())) throw ApiException.field("siteId", "Valid site required");
        if (e.getServiceTypeId() == null) throw ApiException.field("serviceTypeId", "Required");
        if (e.getCode() == null || e.getName() == null) throw ApiException.field("code", "Code and name required");
    }
}

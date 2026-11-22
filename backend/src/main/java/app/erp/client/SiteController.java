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
@RequestMapping("/v1/sites")
public class SiteController extends CrudSupport<Site> {
    public SiteController(SiteRepository repo, ObjectMapper mapper, AuditService audit) {
        super(Site.class, repo, mapper, audit, Perm.CLIENT_READ, Perm.CLIENT_WRITE);
    }
    private ClientRepository clients;
    private app.erp.org.BranchRepository branches;
    @org.springframework.beans.factory.annotation.Autowired
    void deps(ClientRepository c, app.erp.org.BranchRepository b) { clients = c; branches = b; }

    @Override protected Set<String> filterable() { return Set.of("clientId", "branchId", "status", "geofencePolicy"); }
    @Override protected List<String> searchable() { return List.of("name", "code", "address"); }
    @Override protected Sort defaultSort() { return Sort.by("name"); }
    @Override protected void checkRead() { Authz.requireAny(Perm.CLIENT_READ, Perm.CLIENT_PORTAL, Perm.SELF_SERVICE); }
    @Override protected Specification<Site> scope() {
        var u = Authz.user();
        Specification<Site> s = null;
        if (u.isClientUser()) s = (r, q, cb) -> cb.equal(r.get("clientId"), u.clientId());
        if (u.siteIds() != null) s = Specification.where(s).and((r, q, cb) -> r.get("id").in(u.siteIds()));
        if (u.branchIds() != null) s = Specification.where(s).and((r, q, cb) -> r.get("branchId").in(u.branchIds()));
        return s;
    }
    @Override protected void validate(Site e, boolean isNew) {
        if (e.getClientId() == null || !clients.existsById(e.getClientId())) throw ApiException.field("clientId", "Valid client required");
        if (e.getBranchId() == null || !branches.existsById(e.getBranchId())) throw ApiException.field("branchId", "Valid branch required");
        if (e.getName() == null || e.getName().isBlank()) throw ApiException.field("name", "Required");
        if (!Set.of("ENFORCE", "FLAG", "OFF").contains(e.getGeofencePolicy())) throw ApiException.field("geofencePolicy", "ENFORCE, FLAG or OFF");
        if ("ENFORCE".equals(e.getGeofencePolicy()) && (e.getLatitude() == null || e.getLongitude() == null))
            throw ApiException.field("latitude", "Coordinates required to enforce geofence");
        if (e.getTimezone() != null) java.time.ZoneId.of(e.getTimezone());
    }
}

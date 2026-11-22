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
@RequestMapping("/v1/clients")
public class ClientController extends CrudSupport<Client> {
    public ClientController(ClientRepository repo, ObjectMapper mapper, AuditService audit) {
        super(Client.class, repo, mapper, audit, Perm.CLIENT_READ, Perm.CLIENT_WRITE);
    }
    @Override protected Set<String> filterable() { return Set.of("status", "stateCode"); }
    @Override protected List<String> searchable() { return List.of("legalName", "displayName", "code", "gstin"); }
    @Override protected Sort defaultSort() { return Sort.by("legalName"); }
    @Override protected void checkRead() { Authz.requireAny(Perm.CLIENT_READ, Perm.CLIENT_PORTAL); }
    @Override protected Specification<Client> scope() {
        var u = Authz.user();
        if (u.isClientUser()) return (r, q, cb) -> cb.equal(r.get("id"), u.clientId());
        return null;
    }
    @Override protected void validate(Client e, boolean isNew) {
        if (e.getCode() == null || e.getCode().isBlank()) throw ApiException.field("code", "Required");
        if (e.getLegalName() == null || e.getLegalName().isBlank()) throw ApiException.field("legalName", "Required");
        if (e.getGstin() != null && !e.getGstin().isBlank() && !e.getGstin().matches("[0-9A-Z]{15}"))
            throw ApiException.field("gstin", "GSTIN must be 15 characters");
        if (e.getGstin() != null && e.getGstin().length() == 15 && e.getStateCode() == null) e.setStateCode(e.getGstin().substring(0, 2));
    }
}

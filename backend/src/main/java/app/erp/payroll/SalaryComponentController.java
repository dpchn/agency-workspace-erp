package app.erp.payroll;

import app.erp.common.*;
import app.erp.security.Authz;
import app.erp.security.Perm;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/salary-components")
public class SalaryComponentController extends CrudSupport<SalaryComponent> {
    public SalaryComponentController(SalaryComponentRepository repo, ObjectMapper mapper, AuditService audit) {
        super(SalaryComponent.class, repo, mapper, audit, Perm.PAYROLL_READ, Perm.PAYROLL_APPROVE);
    }
    @Override protected Set<String> filterable() { return Set.of("kind", "active", "stateCode"); }
    @Override protected Sort defaultSort() { return Sort.by("sequence"); }
    @Override protected void validate(SalaryComponent e, boolean isNew) {
        if (!Set.of("EARNING", "DEDUCTION", "EMPLOYER_CONTRIBUTION").contains(String.valueOf(e.getKind()))) throw ApiException.field("kind", "Invalid");
        if (!Set.of("PERCENT", "FIXED", "SLAB").contains(String.valueOf(e.getCalcType()))) throw ApiException.field("calcType", "Invalid");
        if ("PERCENT".equals(e.getCalcType()) && (e.getPercent() == null || e.getBase() == null)) throw ApiException.field("percent", "Percent and base required");
        if ("FIXED".equals(e.getCalcType()) && e.getFixedAmount() == null) throw ApiException.field("fixedAmount", "Required");
        if ("SLAB".equals(e.getCalcType()) && (e.getSlabs() == null || e.getSlabs().isEmpty())) throw ApiException.field("slabs", "Required");
    }
}

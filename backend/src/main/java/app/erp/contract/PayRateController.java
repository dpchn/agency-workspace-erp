package app.erp.contract;

import app.erp.common.*;
import app.erp.security.Authz;
import app.erp.security.Perm;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/pay-rates")
public class PayRateController extends CrudSupport<PayRate> {
    public PayRateController(PayRateRepository repo, ObjectMapper mapper, AuditService audit) {
        super(PayRate.class, repo, mapper, audit, Perm.PAYRATE_READ, Perm.PAYRATE_WRITE);
    }
    @Override protected Set<String> filterable() { return Set.of("serviceTypeId", "clientId", "siteId", "employeeId", "branchId", "wageBasis"); }
    @Override protected Sort defaultSort() { return Sort.by("effectiveFrom").descending(); }
    @Override protected void validate(PayRate e, boolean isNew) {
        if (!Set.of("MONTHLY", "DAILY", "HOURLY").contains(String.valueOf(e.getWageBasis()))) throw ApiException.field("wageBasis", "MONTHLY, DAILY or HOURLY");
        if (e.getRate() == null || e.getRate().signum() < 0) throw ApiException.field("rate", "Required, non-negative");
        if (e.getEffectiveFrom() == null) throw ApiException.field("effectiveFrom", "Required");
        if (e.getEffectiveTo() != null && e.getEffectiveTo().isBefore(e.getEffectiveFrom())) throw ApiException.field("effectiveTo", "Before start");
        if (e.getServiceTypeId() == null && e.getEmployeeId() == null) throw ApiException.field("serviceTypeId", "Service type or employee required");
    }
}

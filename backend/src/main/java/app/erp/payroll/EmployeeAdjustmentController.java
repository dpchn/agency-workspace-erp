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
@RequestMapping("/v1/employee-adjustments")
public class EmployeeAdjustmentController extends CrudSupport<EmployeeAdjustment> {
    public EmployeeAdjustmentController(EmployeeAdjustmentRepository repo, ObjectMapper mapper, AuditService audit) {
        super(EmployeeAdjustment.class, repo, mapper, audit, Perm.PAYROLL_READ, Perm.PAYROLL_RUN);
    }
    @Override protected Set<String> filterable() { return Set.of("employeeId", "periodMonth", "kind"); }
    @Override protected void validate(EmployeeAdjustment e, boolean isNew) {
        if (e.getPayrollRunId() != null) throw ApiException.invalidState("Adjustment already consumed by a locked payroll");
        if (e.getEmployeeId() == null) throw ApiException.field("employeeId", "Required");
        if (e.getPeriodMonth() == null) throw ApiException.field("periodMonth", "Required");
        e.setPeriodMonth(e.getPeriodMonth().withDayOfMonth(1));
        if (e.getAmount() == null || e.getAmount().signum() <= 0) throw ApiException.field("amount", "Positive amount required");
        if (isNew) e.setCreatedBy(Authz.userId());
    }
}

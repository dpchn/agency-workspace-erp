package app.erp.hr;

import app.erp.common.*;
import app.erp.security.Authz;
import app.erp.security.Perm;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/employee-documents")
public class EmployeeDocumentController extends CrudSupport<EmployeeDocument> {
    @Autowired private EmployeeRepository employees;
    @Autowired private JdbcTemplate jdbc;

    public EmployeeDocumentController(EmployeeDocumentRepository repo, ObjectMapper mapper, AuditService audit) {
        super(EmployeeDocument.class, repo, mapper, audit, Perm.HR_READ, Perm.HR_WRITE);
    }

    @Override protected Set<String> filterable() { return Set.of("employeeId", "docType", "verificationStatus"); }
    @Override protected Sort defaultSort() { return Sort.by("expiresOn"); }

    @Override protected void validate(EmployeeDocument d, boolean isNew) {
        if (d.getEmployeeId() == null || !employees.existsById(d.getEmployeeId())) throw ApiException.field("employeeId", "Valid employee required");
        if (d.getDocType() == null || d.getDocType().isBlank()) throw ApiException.field("docType", "Required");
        if (!isNew) d.setVerificationStatus("PENDING"); // any edit needs re-verification
    }

    public record Verify(String status, String notes) {}

    @PostMapping("/{id}/verify")
    @Transactional
    public EmployeeDocument verify(@PathVariable UUID id, @RequestBody Verify v) {
        Authz.require(Perm.HR_VERIFY);
        if (!Set.of("VERIFIED", "REJECTED").contains(v.status())) throw ApiException.field("status", "VERIFIED or REJECTED");
        EmployeeDocument d = load(id);
        d.setVerificationStatus(v.status());
        d.setVerifiedBy(Authz.userId());
        d.setVerifiedAt(Instant.now());
        if (v.notes() != null) d.setNotes(v.notes());
        audit.record("DOCUMENT_" + v.status(), "EmployeeDocument", id, Map.of("type", d.getDocType()));
        return d;
    }

    /** Compliance calendar: documents expiring in the next N days (or already expired). */
    @GetMapping("/expiring")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> expiring(@RequestParam(defaultValue = "30") int days) {
        Authz.require(Perm.HR_READ);
        return jdbc.queryForList("""
                select d.id, d.doc_type, d.doc_number, d.expires_on, d.verification_status,
                       e.id as employee_id, e.employee_code, e.first_name || coalesce(' ' || e.last_name, '') as employee_name,
                       (d.expires_on < current_date) as expired
                from employee_documents d join employees e on e.id = d.employee_id
                where d.expires_on is not null and d.expires_on <= ? and e.status <> 'EXITED'
                order by d.expires_on""", LocalDate.now().plusDays(days));
    }
}

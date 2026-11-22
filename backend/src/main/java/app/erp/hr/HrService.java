package app.erp.hr;

import app.erp.common.ApiException;
import app.erp.org.ServiceTypeRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class HrService {
    private final EmployeeRepository employees;
    private final EmployeeDocumentRepository documents;
    private final ServiceTypeRepository serviceTypes;

    public HrService(EmployeeRepository employees, EmployeeDocumentRepository documents, ServiceTypeRepository serviceTypes) {
        this.employees = employees;
        this.documents = documents;
        this.serviceTypes = serviceTypes;
    }

    public Employee get(UUID id) {
        return employees.findById(id).orElseThrow(() -> ApiException.notFound("Employee"));
    }

    public List<String> missingDocuments(UUID employeeId, UUID serviceTypeId, LocalDate onDate) {
        var st = serviceTypes.findById(serviceTypeId).orElseThrow(() -> ApiException.notFound("Service type"));
        if (st.getRequiredDocuments() == null || st.getRequiredDocuments().length == 0) return List.of();
        List<EmployeeDocument> docs = documents.findAll((r, q, cb) -> cb.equal(r.get("employeeId"), employeeId));
        List<String> missing = new ArrayList<>();
        for (String req : st.getRequiredDocuments()) {
            boolean ok = docs.stream().anyMatch(d -> req.equals(d.getDocType()) && "VERIFIED".equals(d.getVerificationStatus())
                    && (d.getExpiresOn() == null || !d.getExpiresOn().isBefore(onDate)));
            if (!ok) missing.add(req);
        }
        return missing;
    }

    /** Reasons this employee cannot work this service type on this date (empty = deployable). */
    public List<String> deployabilityProblems(Employee e, UUID serviceTypeId, LocalDate onDate) {
        List<String> problems = new ArrayList<>();
        if (!"ACTIVE".equals(e.getStatus())) problems.add("Employee is " + e.getStatus());
        if (!e.qualifiedFor(serviceTypeId)) problems.add("Not qualified for this service type");
        if (e.getExitedOn() != null && !onDate.isBefore(e.getExitedOn())) problems.add("Exited");
        missingDocuments(e.getId(), serviceTypeId, onDate).forEach(d -> problems.add("Missing/expired document: " + d));
        return problems;
    }

    public void requireDeployable(Employee e, UUID serviceTypeId, LocalDate onDate) {
        List<String> p = deployabilityProblems(e, serviceTypeId, onDate);
        if (!p.isEmpty()) throw ApiException.invalidState(e.getEmployeeCode() + ": " + String.join("; ", p));
    }
}

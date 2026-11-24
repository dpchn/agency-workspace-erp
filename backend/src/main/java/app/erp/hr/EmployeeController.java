package app.erp.hr;

import app.erp.common.*;
import app.erp.org.ServiceTypeRepository;
import app.erp.platform.*;
import app.erp.security.Authz;
import app.erp.security.Perm;
import app.erp.security.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Employee master + lifecycle: DRAFT -> PENDING_VERIFICATION -> ACTIVE <-> SUSPENDED -> EXITED.
 * Only ACTIVE employees can be deployed or rostered.
 */
@RestController
@RequestMapping("/v1/employees")
public class EmployeeController extends CrudSupport<Employee> {
    @Autowired private ServiceTypeRepository serviceTypes;
    @Autowired private EmployeeDocumentRepository documents;
    @Autowired private TenantService tenantService;
    @Autowired private EmployeeRepository employees;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private MembershipRepository memberships;
    @Autowired private PasswordEncoder encoder;
    @Autowired private HrService hrService;

    public EmployeeController(EmployeeRepository repo, ObjectMapper mapper, AuditService audit) {
        super(Employee.class, repo, mapper, audit, Perm.HR_READ, Perm.HR_WRITE);
    }

    @Override protected Set<String> filterable() { return Set.of("status", "branchId", "serviceTypeId", "employeeCode", "gender"); }
    @Override protected List<String> searchable() { return List.of("firstName", "lastName", "employeeCode", "phone"); }
    @Override protected Sort defaultSort() { return Sort.by("employeeCode"); }

    @Override protected Specification<Employee> scope() {
        var u = Authz.user();
        return u.branchIds() == null ? null : (r, q, cb) -> r.get("branchId").in(u.branchIds());
    }

    @Override protected void validate(Employee e, boolean isNew) {
        if (e.getEmployeeCode() == null || e.getEmployeeCode().isBlank()) throw ApiException.field("employeeCode", "Required");
        if (e.getFirstName() == null || e.getFirstName().isBlank()) throw ApiException.field("firstName", "Required");
        if (e.getBranchId() == null) throw ApiException.field("branchId", "Required");
        if (!Authz.user().branchAllowed(e.getBranchId())) throw ApiException.field("branchId", "Outside your branch scope");
        if (e.getServiceTypeId() == null || !serviceTypes.existsById(e.getServiceTypeId())) throw ApiException.field("serviceTypeId", "Valid service type required");
        if (e.getWeeklyOffDay() != null && (e.getWeeklyOffDay() < 1 || e.getWeeklyOffDay() > 7)) throw ApiException.field("weeklyOffDay", "1=Mon .. 7=Sun");
        if (e.getBankIfsc() != null && !e.getBankIfsc().isBlank() && !e.getBankIfsc().matches("[A-Z]{4}0[A-Z0-9]{6}")) throw ApiException.field("bankIfsc", "Invalid IFSC");
        if (isNew) {
            Integer max = tenantService.current().getMaxEmployees();
            if (max != null) {
                Long active = jdbc.queryForObject("select count(*) from employees where status <> 'EXITED'", Long.class);
                if (active != null && active >= max) throw ApiException.invalidState("Your plan allows " + max + " employees. Ask us to upgrade your plan");
            }
        }
    }

    public record Transition(String reason, LocalDate date) {}

    @PostMapping("/{id}/submit")
    @Transactional
    public Employee submit(@PathVariable UUID id) {
        Authz.require(Perm.HR_WRITE);
        Employee e = load(id);
        move(e, Set.of("DRAFT"), "PENDING_VERIFICATION", null);
        return e;
    }

    /** Activation requires every document the primary service type demands to be VERIFIED and unexpired. */
    @PostMapping("/{id}/activate")
    @Transactional
    public Employee activate(@PathVariable UUID id) {
        Authz.require(Perm.HR_VERIFY);
        Employee e = load(id);
        List<String> missing = missingDocuments(e, e.getServiceTypeId(), LocalDate.now());
        if (!missing.isEmpty()) throw ApiException.invalidState("Pending verification: " + String.join(", ", missing));
        if (e.getJoinedOn() == null) e.setJoinedOn(LocalDate.now());
        move(e, Set.of("DRAFT", "PENDING_VERIFICATION"), "ACTIVE", null);
        return e;
    }

    @PostMapping("/{id}/suspend")
    @Transactional
    public Employee suspend(@PathVariable UUID id, @RequestBody Transition t) {
        Authz.require(Perm.HR_WRITE);
        if (t.reason() == null || t.reason().isBlank()) throw ApiException.field("reason", "Required");
        Employee e = load(id);
        move(e, Set.of("ACTIVE"), "SUSPENDED", t.reason());
        cancelFutureAssignments(e.getId(), LocalDate.now(), "Employee suspended");
        setAppAccess(e, false);
        return e;
    }

    @PostMapping("/{id}/reinstate")
    @Transactional
    public Employee reinstate(@PathVariable UUID id) {
        Authz.require(Perm.HR_WRITE);
        Employee e = load(id);
        move(e, Set.of("SUSPENDED"), "ACTIVE", null);
        setAppAccess(e, true);
        return e;
    }

    /** Exit: ends deployments, cancels future duties. Final settlement happens in the next payroll run. */
    @PostMapping("/{id}/exit")
    @Transactional
    public Employee exit(@PathVariable UUID id, @RequestBody Transition t) {
        Authz.require(Perm.HR_WRITE);
        Employee e = load(id);
        LocalDate date = t.date() == null ? LocalDate.now() : t.date();
        move(e, Set.of("ACTIVE", "SUSPENDED", "PENDING_VERIFICATION", "DRAFT"), "EXITED", t.reason());
        e.setExitedOn(date);
        e.setExitReason(t.reason());
        jdbc.update("update deployments set end_date = ?, status = case when start_date > ? then 'CANCELLED' else status end, updated_at = now() " +
                "where employee_id = ? and status = 'ACTIVE' and (end_date is null or end_date > ?)", date, date, e.getId(), date);
        cancelFutureAssignments(e.getId(), date.plusDays(1), "Employee exited");
        setAppAccess(e, false);
        return e;
    }

    @Autowired private app.erp.security.TokenRevocationValidator revocation;

    /**
     * Suspended or exited staff lose app access at once: membership off (no sign-in, no OTP, no password reset)
     * and every open session ended. Reinstating turns the membership back on.
     */
    private void setAppAccess(Employee e, boolean on) {
        if (e.getUserId() == null) return;
        memberships.findByTenantIdAndUserId(TenantContext.requireTenant(), e.getUserId()).ifPresent(m -> m.setStatus(on ? "ACTIVE" : "INACTIVE"));
        if (!on) {
            users.findById(e.getUserId()).ifPresent(u -> { u.setTokenEpoch(u.getTokenEpoch() + 1); users.save(u); });
            revocation.evict(e.getUserId());
        }
    }

    public record PinRequest(String pin) {}

    /**
     * Attendance PIN for workers without a phone: they punch on the site kiosk (or the supervisor's phone)
     * with employee code + PIN. Setting a PIN also unlocks a locked one.
     */
    @PostMapping("/{id}/kiosk-pin")
    @Transactional
    public Map<String, Object> setKioskPin(@PathVariable UUID id, @RequestBody PinRequest r) {
        Authz.requireAny(Perm.HR_WRITE, Perm.ATTENDANCE_ADJUST);
        Employee e = employees.findById(id).orElseThrow(() -> ApiException.notFound("Employee"));
        if (!Authz.user().branchAllowed(e.getBranchId())) throw ApiException.notFound("Employee");
        String pin = r == null ? null : r.pin();
        if (pin == null || !pin.matches("\\d{4,6}")) throw ApiException.field("pin", "4 to 6 digits");
        if (pin.chars().distinct().count() == 1 || "0123456789".contains(pin) || "9876543210".contains(pin))
            throw ApiException.field("pin", "Too easy to guess");
        e.setAttendancePinHash(encoder.encode(pin));
        e.setPinFailedCount(0);
        e.setPinLockedUntil(null);
        employees.save(e);
        audit.record("EMPLOYEE_KIOSK_PIN_SET", "Employee", id, Map.of());
        return Map.of("employeeId", id, "kioskPinSet", true);
    }

    public record LoginRequest(String phone, String email, String password) {}

    /** Creates the guard's self-service login (mobile check-in, roster, payslips). */
    @PostMapping("/{id}/create-login")
    @Transactional
    public Map<String, Object> createLogin(@PathVariable UUID id, @RequestBody LoginRequest r) {
        Authz.require(Perm.USER_MANAGE);
        Employee e = load(id);
        if (e.getUserId() != null) throw ApiException.conflict("Employee already has a login");
        String phone = r.phone() != null ? r.phone() : e.getPhone();
        if (phone == null && r.email() == null) throw ApiException.field("phone", "Phone or e-mail required");
        if (r.password() == null || r.password().length() < 6) throw ApiException.field("password", "Minimum 6 characters");
        User u = phone != null ? users.findByPhone(phone).orElse(null) : users.findByEmailIgnoreCase(r.email()).orElse(null);
        if (u == null) {
            u = new User();
            u.setPhone(phone);
            u.setEmail(r.email());
            u.setFullName(e.fullName());
            u.setPasswordHash(encoder.encode(r.password()));
            users.saveAndFlush(u);
        }
        Membership m = new Membership();
        m.setTenantId(TenantContext.requireTenant());
        m.setUserId(u.getId());
        m.setRole(Role.GUARD);
        m.setEmployeeId(e.getId());
        memberships.saveAndFlush(m);
        e.setUserId(u.getId());
        audit.record("EMPLOYEE_LOGIN_CREATED", "Employee", e.getId(), Map.of());
        return Map.of("userId", u.getId(), "login", phone != null ? phone : r.email());
    }

    @GetMapping("/{id}/readiness")
    @Transactional(readOnly = true)
    public Map<String, Object> readiness(@PathVariable UUID id, @RequestParam(required = false) UUID serviceTypeId) {
        Authz.require(Perm.HR_READ);
        Employee e = load(id);
        UUID st = serviceTypeId == null ? e.getServiceTypeId() : serviceTypeId;
        List<String> missing = missingDocuments(e, st, LocalDate.now());
        return Map.of("status", e.getStatus(), "qualified", e.qualifiedFor(st), "missingDocuments", missing,
                "deployable", "ACTIVE".equals(e.getStatus()) && e.qualifiedFor(st) && missing.isEmpty());
    }

    public List<String> missingDocuments(Employee e, UUID serviceTypeId, LocalDate onDate) {
        return hrService.missingDocuments(e.getId(), serviceTypeId, onDate);
    }

    private void move(Employee e, Set<String> from, String to, String reason) {
        if (!from.contains(e.getStatus())) throw ApiException.invalidState("Cannot move employee from " + e.getStatus() + " to " + to);
        String before = e.getStatus();
        e.setStatus(to);
        employees.saveAndFlush(e);
        audit.record("EMPLOYEE_" + to, "Employee", e.getId(), Map.of("from", before, "reason", reason == null ? "" : reason));
    }

    private void cancelFutureAssignments(UUID employeeId, LocalDate from, String reason) {
        jdbc.update("""
                update assignments a set status = 'CANCELLED', reason = ?, updated_at = now()
                from shifts s where s.id = a.shift_id and a.employee_id = ? and s.duty_date >= ?
                  and a.status in ('PLANNED','CONFIRMED')""", reason, employeeId, from);
    }
}

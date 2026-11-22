package app.erp.attendance;

import app.erp.common.ApiException;
import app.erp.common.AuditService;
import app.erp.security.Authz;
import app.erp.security.Perm;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/leaves")
public class LeaveController {
    private final LeaveRequestRepository leaves;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public LeaveController(LeaveRequestRepository leaves, AuditService audit, JdbcTemplate jdbc) {
        this.leaves = leaves;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<LeaveRequest> list(@RequestParam(required = false) String status, @RequestParam(required = false) UUID employeeId) {
        var u = Authz.user();
        UUID emp = employeeId;
        if (!Authz.has(Perm.LEAVE_APPROVE)) {
            Authz.require(Perm.LEAVE_SELF);
            emp = u.employeeId();
        }
        UUID filterEmp = emp;
        return leaves.findAll((r, q, cb) -> {
            var p = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (status != null) p.add(cb.equal(r.get("status"), status));
            if (filterEmp != null) p.add(cb.equal(r.get("employeeId"), filterEmp));
            return cb.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));
        }, Sort.by("fromDate").descending());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public LeaveRequest apply(@RequestBody LeaveRequest req) {
        var u = Authz.user();
        if (!Authz.has(Perm.LEAVE_APPROVE)) {
            Authz.require(Perm.LEAVE_SELF);
            req.setEmployeeId(u.employeeId());
        }
        if (req.getEmployeeId() == null) throw ApiException.field("employeeId", "Required");
        if (req.getFromDate() == null || req.getToDate() == null || req.getToDate().isBefore(req.getFromDate()))
            throw ApiException.field("toDate", "Valid date range required");
        if (!Set.of("CASUAL", "SICK", "EARNED", "UNPAID").contains(String.valueOf(req.getLeaveType())))
            throw ApiException.field("leaveType", "CASUAL, SICK, EARNED or UNPAID");
        req.setStatus("PENDING");
        req.setDecidedBy(null);
        req.setDecidedAt(null);
        return leaves.saveAndFlush(req);
    }

    public record Decision(boolean approve, String note) {}

    /** Approving leave releases the guard's rostered duties in that range so the shifts show as open. */
    @PostMapping("/{id}/decision")
    @Transactional
    public Map<String, Object> decide(@PathVariable UUID id, @RequestBody Decision d) {
        Authz.require(Perm.LEAVE_APPROVE);
        LeaveRequest l = leaves.findById(id).orElseThrow(() -> ApiException.notFound("Leave"));
        if (!"PENDING".equals(l.getStatus())) throw ApiException.invalidState("Already decided");
        l.setStatus(d.approve() ? "APPROVED" : "REJECTED");
        l.setDecidedBy(Authz.userId());
        l.setDecidedAt(Instant.now());
        int released = 0;
        if (d.approve()) {
            List<UUID> shiftIds = jdbc.queryForList("""
                    update assignments a set status = 'CANCELLED', reason = 'On approved leave', updated_at = now()
                    from shifts s where s.id = a.shift_id and a.employee_id = ? and s.duty_date between ? and ?
                      and a.status = 'PLANNED' returning a.shift_id""", UUID.class, l.getEmployeeId(), l.getFromDate(), l.getToDate());
            released = shiftIds.size();
            if (!shiftIds.isEmpty()) {
                jdbc.update("update shifts set status = case when status = 'FILLED' then 'PARTIAL' else status end where id = any(?)",
                        (Object) shiftIds.toArray(UUID[]::new));
                jdbc.update("update shifts s set status = 'OPEN' where s.id = any(?) and not exists (select 1 from assignments a " +
                        "where a.shift_id = s.id and a.status in ('PLANNED','CONFIRMED','COMPLETED'))", (Object) shiftIds.toArray(UUID[]::new));
            }
        }
        audit.record("LEAVE_" + l.getStatus(), "LeaveRequest", id, Map.of("releasedDuties", released));
        return Map.of("leave", l, "releasedDuties", released);
    }
}

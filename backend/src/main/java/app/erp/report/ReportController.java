package app.erp.report;

import app.erp.common.ApiException;
import app.erp.security.Authz;
import app.erp.security.Perm;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/reports")
public class ReportController {
    private final JdbcTemplate jdbc;

    public ReportController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Operations dashboard for a day: deployment fill rate, check-ins, exceptions, money at a glance. */
    @GetMapping("/dashboard")
    @Transactional(readOnly = true)
    public Map<String, Object> dashboard(@RequestParam(required = false) LocalDate date) {
        Authz.requireAny(Perm.REPORT_READ, Perm.ROSTER_READ);
        var u = Authz.user();
        LocalDate d = date == null ? LocalDate.now() : date;
        String siteFilter = u.siteIds() == null ? "" : " and s.site_id = any(?)";
        Object[] args = u.siteIds() == null ? new Object[]{d} : new Object[]{d, u.siteIds().toArray(UUID[]::new)};
        Map<String, Object> shifts = jdbc.queryForMap("""
                select count(*) as shifts, coalesce(sum(required_headcount),0) as required_slots,
                  count(*) filter (where status = 'FILLED') as filled, count(*) filter (where status in ('OPEN','PARTIAL')) as uncovered
                from shifts s where s.duty_date = ? and s.status <> 'CANCELLED'""" + siteFilter, args);
        Map<String, Object> att = jdbc.queryForMap("""
                select count(*) filter (where r.check_in_at is not null) as checked_in,
                  count(*) filter (where r.late_minutes > 0) as late,
                  count(*) filter (where r.status = 'ABSENT') as absent,
                  count(*) filter (where cardinality(r.flags) > 0) as flagged,
                  count(*) filter (where r.approval_status = 'PENDING' and r.status <> 'PENDING') as pending_approval
                from attendance_records r join shifts s on s.id = r.shift_id where r.duty_date = ?""" + siteFilter, args);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", d);
        out.put("roster", shifts);
        out.put("attendance", att);
        long required = ((Number) shifts.get("required_slots")).longValue();
        long covered = jdbc.queryForObject("""
                select count(*) from assignments a join shifts s on s.id = a.shift_id
                where s.duty_date = ? and a.status in ('PLANNED','CONFIRMED','COMPLETED') and a.end_at >= s.end_at""" + siteFilter, Long.class, args);
        out.put("fillRatePct", required == 0 ? null : Math.round(covered * 1000.0 / required) / 10.0);
        out.put("openIncidents", jdbc.queryForObject("select count(*) from incidents where status in ('OPEN','ACKNOWLEDGED','IN_PROGRESS')", Long.class));
        out.put("pendingAdjustments", jdbc.queryForObject("select count(*) from attendance_adjustments where status = 'PENDING'", Long.class));
        out.put("pendingLeaves", jdbc.queryForObject("select count(*) from leave_requests where status = 'PENDING'", Long.class));
        out.put("expiringDocuments30d", jdbc.queryForObject(
                "select count(*) from employee_documents where expires_on <= current_date + 30", Long.class));
        out.put("activeEmployees", jdbc.queryForObject("select count(*) from employees where status = 'ACTIVE'", Long.class));
        if (Authz.has(Perm.BILLING_READ)) {
            out.put("receivables", jdbc.queryForMap("""
                    select coalesce(sum(total - amount_paid),0) as outstanding,
                           coalesce(sum(total - amount_paid) filter (where status = 'OVERDUE'),0) as overdue
                    from invoices where kind = 'INVOICE' and status in ('ISSUED','PARTIALLY_PAID','OVERDUE','DISPUTED')"""));
        }
        out.put("generatedAt", Instant.now());
        return out;
    }

    /**
     * Contract / site margin for a month: revenue from issued invoices vs direct wage + employer cost
     * from the latest non-superseded payroll run.
     * contribution = revenue - direct employee cost - statutory employer cost
     */
    @GetMapping("/site-margin")
    @Transactional(readOnly = true)
    public Map<String, Object> siteMargin(@RequestParam String month) {
        Authz.require(Perm.REPORT_READ);
        if (!Authz.has(Perm.BILLING_READ) && !Authz.has(Perm.PAYROLL_READ)) throw ApiException.forbidden("Finance access required");
        YearMonth ym = YearMonth.parse(month);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                with rev as (
                  select l.site_id, sum(case when i.kind = 'CREDIT_NOTE' then -l.amount else l.amount end) as revenue
                  from invoice_lines l join invoices i on i.id = l.invoice_id
                  where i.period_start = ? and i.invoice_no is not null and i.status <> 'CANCELLED' and l.site_id is not null
                  group by l.site_id),
                cost as (
                  select c.site_id, sum(c.duties) as duties, sum(c.wage_amount) as wages, sum(c.employer_cost) as employer_cost
                  from payroll_site_costs c join payroll_runs r on r.id = c.run_id
                  where r.period_start = ? and r.status <> 'SUPERSEDED' group by c.site_id)
                select s.id as site_id, s.name as site_name, cl.legal_name as client_name,
                       coalesce(rev.revenue,0) as revenue, coalesce(cost.duties,0) as paid_duties,
                       coalesce(cost.wages,0) as wages, coalesce(cost.employer_cost,0) as employer_cost,
                       coalesce(rev.revenue,0) - coalesce(cost.wages,0) - coalesce(cost.employer_cost,0) as contribution
                from sites s join clients cl on cl.id = s.client_id
                left join rev on rev.site_id = s.id left join cost on cost.site_id = s.id
                where rev.site_id is not null or cost.site_id is not null
                order by contribution""", ym.atDay(1), ym.atDay(1));
        for (var r : rows) {
            var rev = (java.math.BigDecimal) r.get("revenue");
            var con = (java.math.BigDecimal) r.get("contribution");
            r.put("margin_pct", rev.signum() == 0 ? null : con.multiply(java.math.BigDecimal.valueOf(100)).divide(rev, 1, java.math.RoundingMode.HALF_UP));
        }
        return Map.of("month", month, "rows", rows, "generatedAt", Instant.now(),
                "note", "Revenue = issued invoices for the month; cost = latest payroll run (draft runs are provisional).");
    }

    /** Where did each guard work this month and for how many duties - the multi-site trail. */
    @GetMapping("/employee-deployment-trail")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> trail(@RequestParam String month, @RequestParam(required = false) UUID employeeId) {
        Authz.requireAny(Perm.REPORT_READ, Perm.ATTENDANCE_READ);
        YearMonth ym = YearMonth.parse(month);
        return jdbc.queryForList("""
                select e.employee_code, e.first_name || coalesce(' ' || e.last_name, '') as employee_name,
                       s.name as site_name, c.legal_name as client_name, st.name as service_type,
                       min(r.duty_date) as first_day, max(r.duty_date) as last_day,
                       sum(r.pay_units) as paid_duties, sum(r.bill_units) as billed_units,
                       count(*) filter (where r.assignment_role = 'REPLACEMENT') as as_replacement,
                       count(*) filter (where r.assignment_role = 'DOUBLE_DUTY') as double_duties
                from attendance_records r join employees e on e.id = r.employee_id join sites s on s.id = r.site_id
                join clients c on c.id = r.client_id join service_types st on st.id = r.service_type_id
                where r.duty_date between ? and ? and r.approval_status = 'APPROVED' and (?::uuid is null or r.employee_id = ?::uuid)
                group by e.employee_code, employee_name, s.name, c.legal_name, st.name
                order by e.employee_code, first_day""", ym.atDay(1), ym.atEndOfMonth(), employeeId, employeeId);
    }

    @GetMapping("/audit")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> audit(@RequestParam(required = false) String entityType, @RequestParam(required = false) UUID entityId,
                                           @RequestParam(defaultValue = "200") int limit) {
        Authz.require(Perm.AUDIT_READ);
        return jdbc.queryForList("""
                select a.created_at, a.action, a.entity_type, a.entity_id, a.details, a.correlation_id, u.full_name as actor
                from audit_events a left join users u on u.id = a.actor_user_id
                where (?::text is null or a.entity_type = ?) and (?::uuid is null or a.entity_id = ?::uuid)
                order by a.created_at desc limit ?""", entityType, entityType, entityId, entityId, Math.min(limit, 2000));
    }
}

package app.erp.report;

import app.erp.common.ApiException;
import app.erp.security.Authz;
import app.erp.security.Perm;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Guard portal (roster, attendance, payslips) and client portal summary. */
@RestController
@RequestMapping("/v1")
public class SelfServiceController {
    private final JdbcTemplate jdbc;

    public SelfServiceController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private UUID me() {
        var u = Authz.require(Perm.SELF_SERVICE);
        if (u.employeeId() == null) throw ApiException.forbidden("Not linked to an employee");
        return u.employeeId();
    }

    @GetMapping("/me/duties")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> duties(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        UUID emp = me();
        LocalDate f = from == null ? LocalDate.now().minusDays(1) : from;
        LocalDate t = to == null ? LocalDate.now().plusDays(7) : to;
        return jdbc.queryForList("""
                select a.id as assignment_id, a.start_at, a.end_at, a.role, a.status, s.duty_date, s.kind,
                       si.id as site_id, si.name as site_name, si.address, si.latitude, si.longitude, si.geofence_radius_m, si.geofence_policy,
                       p.name as post_name, p.instructions, r.check_in_at, r.check_out_at, r.status as attendance_status
                from assignments a join shifts s on s.id = a.shift_id join sites si on si.id = s.site_id join posts p on p.id = s.post_id
                left join attendance_records r on r.assignment_id = a.id
                where a.employee_id = ? and s.duty_date between ? and ? and a.status <> 'CANCELLED'
                order by a.start_at""", emp, f, t);
    }

    @GetMapping("/me/attendance")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> attendance(@RequestParam String month) {
        UUID emp = me();
        var ym = java.time.YearMonth.parse(month);
        return jdbc.queryForList("""
                select r.duty_date, si.name as site_name, r.check_in_at, r.check_out_at, r.status, r.approval_status, r.pay_units, r.ot_minutes
                from attendance_records r join sites si on si.id = r.site_id
                where r.employee_id = ? and r.duty_date between ? and ? order by r.duty_date""", emp, ym.atDay(1), ym.atEndOfMonth());
    }

    @GetMapping("/me/payslips")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> payslips() {
        UUID emp = me();
        return jdbc.queryForList("""
                select p.id, r.period_start, p.duties, p.gross, p.total_deductions, p.net, p.payment_status
                from payslips p join payroll_runs r on r.id = p.run_id
                where p.employee_id = ? and r.status in ('LOCKED','PAID') order by r.period_start desc""", emp);
    }

    @GetMapping("/portal/summary")
    @Transactional(readOnly = true)
    public Map<String, Object> clientSummary(@RequestParam(required = false) LocalDate date) {
        var u = Authz.require(Perm.CLIENT_PORTAL);
        LocalDate d = date == null ? LocalDate.now() : date;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sites", jdbc.queryForList("""
                select si.id, si.name,
                  (select coalesce(sum(required_headcount),0) from shifts s where s.site_id = si.id and s.duty_date = ? and s.status <> 'CANCELLED') as required_today,
                  (select count(*) from attendance_records r where r.site_id = si.id and r.duty_date = ? and r.check_in_at is not null) as reported_today
                from sites si where si.client_id = ? order by si.name""", d, d, u.clientId()));
        out.put("outstanding", jdbc.queryForObject("""
                select coalesce(sum(total - amount_paid),0) from invoices
                where client_id = ? and kind = 'INVOICE' and status in ('ISSUED','PARTIALLY_PAID','OVERDUE','DISPUTED')""", java.math.BigDecimal.class, u.clientId()));
        out.put("openIncidents", jdbc.queryForObject("""
                select count(*) from incidents i join sites si on si.id = i.site_id
                where si.client_id = ? and i.client_visible and i.status <> 'CLOSED'""", Long.class, u.clientId()));
        return out;
    }
}

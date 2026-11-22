package app.erp.payroll;

import app.erp.common.ApiException;
import app.erp.common.Idempotent;
import app.erp.security.Authz;
import app.erp.security.Perm;
import java.time.YearMonth;
import java.util.*;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/payroll")
public class PayrollController {
    private final PayrollService service;
    private final PayrollRunRepository runs;
    private final PayrollLineRepository lines;
    private final JdbcTemplate jdbc;

    public PayrollController(PayrollService service, PayrollRunRepository runs, PayrollLineRepository lines, JdbcTemplate jdbc) {
        this.service = service;
        this.runs = runs;
        this.lines = lines;
        this.jdbc = jdbc;
    }

    public record CreateRun(String month, UUID branchId) {}

    @PostMapping("/runs")
    @Idempotent
    public PayrollRun create(@RequestBody CreateRun r) {
        Authz.require(Perm.PAYROLL_RUN);
        if (r.month() == null) throw ApiException.field("month", "YYYY-MM required");
        return service.create(YearMonth.parse(r.month()), r.branchId());
    }

    @GetMapping("/runs")
    @Transactional(readOnly = true)
    public List<PayrollRun> list(@RequestParam(defaultValue = "false") boolean includeSuperseded) {
        Authz.require(Perm.PAYROLL_READ);
        return runs.findAll((r, q, cb) -> includeSuperseded ? cb.conjunction() : cb.notEqual(r.get("status"), "SUPERSEDED"),
                Sort.by("periodStart").descending().and(Sort.by("versionNo").descending()));
    }

    @GetMapping("/runs/{id}")
    @Transactional(readOnly = true)
    public PayrollRun get(@PathVariable UUID id) {
        Authz.require(Perm.PAYROLL_READ);
        return service.get(id);
    }

    @PostMapping("/runs/{id}/recalculate")
    @Idempotent
    public PayrollRun recalc(@PathVariable UUID id) {
        Authz.require(Perm.PAYROLL_RUN);
        return service.recalculate(id);
    }

    @PostMapping("/runs/{id}/{action:review|approve|reject|lock|mark-paid}")
    @Idempotent
    public PayrollRun transition(@PathVariable UUID id, @PathVariable String action) {
        Authz.require(Set.of("approve", "reject", "lock").contains(action) ? Perm.PAYROLL_APPROVE : Perm.PAYROLL_RUN);
        return service.transition(id, action);
    }

    /** Payroll register: one row per employee with totals. */
    @GetMapping("/runs/{id}/payslips")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> payslips(@PathVariable UUID id) {
        Authz.require(Perm.PAYROLL_READ);
        service.get(id);
        return jdbc.queryForList("""
                select p.id, p.employee_id, e.employee_code, e.first_name || coalesce(' ' || e.last_name, '') as employee_name,
                       p.duties, p.ot_hours, p.leave_days, p.gross, p.total_deductions, p.net, p.employer_contributions, p.payment_status
                from payslips p join employees e on e.id = p.employee_id where p.run_id = ? order by e.employee_code""", id);
    }

    @GetMapping("/payslips/{payslipId}")
    @Transactional(readOnly = true)
    public Map<String, Object> payslip(@PathVariable UUID payslipId) {
        var u = Authz.user();
        Map<String, Object> p = jdbc.queryForList("""
                select p.*, e.employee_code, e.first_name || coalesce(' ' || e.last_name, '') as employee_name, e.uan, e.esic_no,
                       e.bank_account_no, e.bank_ifsc, r.period_start, r.period_end, r.status as run_status
                from payslips p join employees e on e.id = p.employee_id join payroll_runs r on r.id = p.run_id where p.id = ?""", payslipId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Payslip"));
        boolean own = u.employeeId() != null && u.employeeId().equals(p.get("employee_id"));
        if (!own) Authz.require(Perm.PAYROLL_READ);
        if (own && !Authz.has(Perm.PAYROLL_READ) && !Set.of("LOCKED", "PAID").contains((String) p.get("run_status")))
            throw ApiException.notFound("Payslip");
        if (p.get("bank_account_no") != null) {
            String acc = (String) p.get("bank_account_no");
            p.put("bank_account_no", "XXXX" + acc.substring(Math.max(0, acc.length() - 4)));
        }
        p.put("lines", jdbc.queryForList("""
                select l.component_code, l.component_name, l.kind, l.quantity, l.rate, l.amount, s.name as site_name, l.source
                from payroll_lines l left join sites s on s.id = l.site_id
                where l.run_id = ? and l.employee_id = ? order by l.kind, l.component_code""", p.get("run_id"), p.get("employee_id")));
        return p;
    }

    /** Bank transfer file (CSV). Generic layout; adapt per bank in an integration adapter. */
    @GetMapping(value = "/runs/{id}/bank-export", produces = "text/csv")
    @Transactional(readOnly = true)
    public ResponseEntity<String> bankExport(@PathVariable UUID id) {
        Authz.require(Perm.PAYROLL_APPROVE);
        PayrollRun run = service.get(id);
        if (!Set.of("APPROVED", "LOCKED", "PAID").contains(run.getStatus())) throw ApiException.invalidState("Approve payroll first");
        StringBuilder sb = new StringBuilder("employee_code,name,account_no,ifsc,amount,narration\n");
        jdbc.query("""
                select e.employee_code, e.first_name || coalesce(' ' || e.last_name, '') as name, e.bank_account_no, e.bank_ifsc, p.net
                from payslips p join employees e on e.id = p.employee_id where p.run_id = ? and p.net > 0 order by e.employee_code""",
                rs -> {
                    sb.append(csv(rs.getString(1))).append(',').append(csv(rs.getString(2))).append(',').append(csv(rs.getString(3)))
                            .append(',').append(csv(rs.getString(4))).append(',').append(rs.getBigDecimal(5).toPlainString())
                            .append(",Salary ").append(run.getPeriodStart().getMonth()).append(' ').append(run.getPeriodStart().getYear()).append('\n');
                }, id);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=bank-" + run.getPeriodStart() + ".csv")
                .contentType(MediaType.parseMediaType("text/csv")).body(sb.toString());
    }

    private static String csv(String s) {
        if (s == null) return "";
        String v = s.replace("\"", "\"\"");
        if (v.startsWith("=") || v.startsWith("+") || v.startsWith("-") || v.startsWith("@")) v = "'" + v; // CSV injection guard
        return "\"" + v + "\"";
    }
}

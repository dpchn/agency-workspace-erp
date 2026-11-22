package app.erp.payroll;

import app.erp.common.ApiException;
import app.erp.common.AuditService;
import app.erp.common.OutboxService;
import app.erp.contract.PayRateRepository;
import app.erp.contract.PayRateResolver;
import app.erp.platform.TenantService;
import app.erp.platform.TenantSettings;
import app.erp.security.Authz;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payroll lifecycle: DRAFT -> REVIEWED -> APPROVED -> LOCKED -> PAID. Recalculation creates a new
 * version (old one SUPERSEDED). Locking stamps every consumed attendance record and adjustment,
 * making the period immutable; late approvals for a locked month flow into the next run as arrears.
 */
@Service
public class PayrollService {
    private final PayrollRunRepository runs;
    private final PayslipRepository payslips;
    private final PayrollLineRepository lines;
    private final PayrollSiteCostRepository siteCosts;
    private final SalaryComponentRepository components;
    private final PayRateRepository payRates;
    private final TenantService tenantService;
    private final AuditService audit;
    private final OutboxService outbox;
    private final JdbcTemplate jdbc;

    public PayrollService(PayrollRunRepository runs, PayslipRepository payslips, PayrollLineRepository lines,
                          PayrollSiteCostRepository siteCosts, SalaryComponentRepository components, PayRateRepository payRates,
                          TenantService tenantService, AuditService audit, OutboxService outbox, JdbcTemplate jdbc) {
        this.runs = runs;
        this.payslips = payslips;
        this.lines = lines;
        this.siteCosts = siteCosts;
        this.components = components;
        this.payRates = payRates;
        this.tenantService = tenantService;
        this.audit = audit;
        this.outbox = outbox;
        this.jdbc = jdbc;
    }

    @Transactional
    public PayrollRun create(YearMonth month, UUID branchId) {
        PayrollRun run = new PayrollRun();
        run.setPeriodStart(month.atDay(1));
        run.setPeriodEnd(month.atEndOfMonth());
        run.setBranchId(branchId);
        run.setVersionNo(1);
        run.setCreatedBy(Authz.userId());
        runs.saveAndFlush(run);
        compute(run);
        audit.record("PAYROLL_CREATE", "PayrollRun", run.getId(), Map.of("period", month.toString()));
        return run;
    }

    @Transactional
    public PayrollRun recalculate(UUID id) {
        PayrollRun old = get(id);
        if (!Set.of("DRAFT", "REVIEWED").contains(old.getStatus())) throw ApiException.invalidState("Only DRAFT/REVIEWED runs can be recalculated");
        old.setStatus("SUPERSEDED");
        runs.saveAndFlush(old);
        PayrollRun run = new PayrollRun();
        run.setPeriodStart(old.getPeriodStart());
        run.setPeriodEnd(old.getPeriodEnd());
        run.setBranchId(old.getBranchId());
        run.setVersionNo(old.getVersionNo() + 1);
        run.setCreatedBy(Authz.userId());
        runs.saveAndFlush(run);
        compute(run);
        audit.record("PAYROLL_RECALCULATE", "PayrollRun", run.getId(), Map.of("previous", id));
        return run;
    }

    private void compute(PayrollRun run) {
        TenantSettings st = tenantService.settings();
        YearMonth month = YearMonth.from(run.getPeriodStart());
        var resolver = new PayRateResolver(payRates.findAll());
        var engine = new PayrollEngine(resolver, components.findAll(), st, month);
        List<Map<String, Object>> exceptions = new ArrayList<>();

        String branchFilter = run.getBranchId() == null ? "" : " and e.branch_id = '" + run.getBranchId() + "'";
        // Duties: approved, unlocked, in period; plus arrears of already-locked months.
        List<Map<String, Object>> duties = jdbc.queryForList("""
                select r.id, r.employee_id, r.site_id, r.client_id, r.service_type_id, r.duty_date, r.pay_units, r.worked_minutes,
                       r.ot_minutes, r.is_holiday, r.assignment_role,
                       extract(epoch from (a.end_at - a.start_at))::int / 60 as planned_minutes,
                       (r.duty_date < ?) as arrear
                from attendance_records r join employees e on e.id = r.employee_id join assignments a on a.id = r.assignment_id
                where r.approval_status = 'APPROVED' and r.payroll_run_id is null and r.pay_units > 0
                  and (r.duty_date between ? and ?
                       or (r.duty_date < ? and exists (select 1 from payroll_runs pr where pr.status in ('LOCKED','PAID')
                             and r.duty_date between pr.period_start and pr.period_end)))""" + branchFilter,
                run.getPeriodStart(), run.getPeriodStart(), run.getPeriodEnd(), run.getPeriodStart());
        Integer pending = jdbc.queryForObject("""
                select count(*) from attendance_records r join employees e on e.id = r.employee_id
                where r.duty_date between ? and ? and r.approval_status = 'PENDING' and r.payroll_run_id is null""" + branchFilter,
                Integer.class, run.getPeriodStart(), run.getPeriodEnd());
        if (pending != null && pending > 0)
            exceptions.add(Map.of("type", "PENDING_ATTENDANCE", "message", pending + " attendance record(s) still pending approval; they will be paid as arrears later"));

        List<Map<String, Object>> leaveRows = jdbc.queryForList("""
                select l.employee_id, gs::date as day, l.leave_type from leave_requests l join employees e on e.id = l.employee_id,
                  generate_series(greatest(l.from_date, ?::date), least(l.to_date, ?::date), interval '1 day') gs
                where l.status = 'APPROVED'""" + branchFilter, run.getPeriodStart(), run.getPeriodEnd());
        List<Map<String, Object>> adjRows = jdbc.queryForList("""
                select a.id, a.employee_id, a.kind, a.amount, a.notes from employee_adjustments a join employees e on e.id = a.employee_id
                where a.period_month = ? and a.payroll_run_id is null""" + branchFilter, run.getPeriodStart());

        Set<UUID> empIds = new LinkedHashSet<>();
        duties.forEach(d -> empIds.add((UUID) d.get("employee_id")));
        leaveRows.forEach(d -> empIds.add((UUID) d.get("employee_id")));
        adjRows.forEach(d -> empIds.add((UUID) d.get("employee_id")));

        BigDecimal tg = BigDecimal.ZERO, td = BigDecimal.ZERO, tn = BigDecimal.ZERO, te = BigDecimal.ZERO;
        int count = 0;
        for (UUID empId : empIds) {
            Map<String, Object> emp = jdbc.queryForMap("""
                    select e.id, e.employee_code, e.branch_id, b.state_code, e.service_type_id, e.bank_account_no,
                      (select d.site_id from deployments d where d.employee_id = e.id and d.deployment_type = 'PERMANENT' and d.status = 'ACTIVE'
                         and d.start_date <= ? order by d.start_date desc limit 1) as home_site_id
                    from employees e join branches b on b.id = e.branch_id where e.id = ?""", run.getPeriodEnd(), empId);
            UUID homeSite = (UUID) emp.get("home_site_id");
            UUID homeClient = homeSite == null ? null : jdbc.queryForObject("select client_id from sites where id = ?", UUID.class, homeSite);
            List<PayrollEngine.Duty> ds = duties.stream().filter(d -> empId.equals(d.get("employee_id"))).map(d -> new PayrollEngine.Duty(
                    (UUID) d.get("id"), (UUID) d.get("site_id"), (UUID) d.get("client_id"), (UUID) d.get("service_type_id"),
                    ((Date) d.get("duty_date")).toLocalDate(), (BigDecimal) d.get("pay_units"), (Integer) d.get("worked_minutes"),
                    (Integer) d.get("planned_minutes"), (Integer) d.get("ot_minutes"), (Boolean) d.get("is_holiday"),
                    (String) d.get("assignment_role"), homeSite, homeClient, (Boolean) d.get("arrear"))).toList();
            List<PayrollEngine.LeaveDay> ls = leaveRows.stream().filter(l -> empId.equals(l.get("employee_id")))
                    .map(l -> new PayrollEngine.LeaveDay(((Date) l.get("day")).toLocalDate(), (String) l.get("leave_type"))).toList();
            List<PayrollEngine.Adjustment> as = adjRows.stream().filter(a -> empId.equals(a.get("employee_id"))).map(a -> {
                String kind = (String) a.get("kind");
                return new PayrollEngine.Adjustment((UUID) a.get("id"), kind, (BigDecimal) a.get("amount"), (String) a.get("notes"),
                        Set.of("BONUS", "ALLOWANCE", "OTHER_EARNING").contains(kind));
            }).toList();

            var input = new PayrollEngine.EmployeeInput(empId, (String) emp.get("employee_code"), (UUID) emp.get("branch_id"),
                    (String) emp.get("state_code"), (UUID) emp.get("service_type_id"), homeSite, homeClient, ds, ls, as);
            PayrollEngine.Result r = engine.calculate(input);
            r.exceptions().forEach(x -> exceptions.add(Map.of("type", "CALCULATION", "employeeId", empId.toString(), "message", x)));
            if (emp.get("bank_account_no") == null && r.net().signum() > 0)
                exceptions.add(Map.of("type", "NO_BANK_ACCOUNT", "employeeId", empId.toString(), "message", emp.get("employee_code") + " has no bank account"));

            Payslip p = new Payslip();
            p.setRunId(run.getId());
            p.setEmployeeId(empId);
            p.setDuties(r.duties());
            p.setOtHours(r.otHours());
            p.setLeaveDays(r.leaveDays());
            p.setGross(r.gross());
            p.setTotalDeductions(r.deductions());
            p.setNet(r.net());
            p.setEmployerContributions(r.employerCost());
            payslips.save(p);
            for (var l : r.lines()) {
                PayrollLine pl = new PayrollLine();
                pl.setRunId(run.getId());
                pl.setEmployeeId(empId);
                pl.setComponentCode(l.code());
                pl.setComponentName(l.name());
                pl.setKind(l.kind());
                pl.setQuantity(l.quantity());
                pl.setRate(l.rate());
                pl.setAmount(l.amount());
                pl.setSiteId(l.siteId());
                pl.setSource(l.source());
                lines.save(pl);
            }
            for (var c : r.siteCosts()) {
                PayrollSiteCost sc = new PayrollSiteCost();
                sc.setRunId(run.getId());
                sc.setEmployeeId(empId);
                sc.setSiteId(c.siteId());
                sc.setClientId(c.clientId());
                sc.setDuties(c.duties());
                sc.setWageAmount(c.wage());
                sc.setEmployerCost(c.employerCost());
                siteCosts.save(sc);
            }
            tg = tg.add(r.gross());
            td = td.add(r.deductions());
            tn = tn.add(r.net());
            te = te.add(r.employerCost());
            count++;
        }
        run.setEmployeeCount(count);
        run.setTotalGross(tg);
        run.setTotalDeductions(td);
        run.setTotalNet(tn);
        run.setTotalEmployerCost(te);
        run.setExceptions(exceptions);
        run.setRuleSnapshot(Map.of("settings", new TreeMap<>(st.raw() == null ? Map.of() : st.raw()),
                "components", components.findAll().stream().map(c -> c.getCode() + ":" + c.getVersion()).sorted().toList(),
                "computedAt", Instant.now().toString()));
        runs.saveAndFlush(run);
    }

    @Transactional
    public PayrollRun transition(UUID id, String action) {
        PayrollRun run = get(id);
        TenantSettings st = tenantService.settings();
        UUID me = Authz.userId();
        switch (action) {
            case "review" -> {
                require(run, "DRAFT");
                run.setStatus("REVIEWED");
                run.setReviewedBy(me);
            }
            case "approve" -> {
                require(run, "REVIEWED");
                if (st.bool("requireApprovalSeparation") && me.equals(run.getCreatedBy()))
                    throw ApiException.forbidden("The person who prepared payroll cannot approve it");
                run.setStatus("APPROVED");
                run.setApprovedBy(me);
                outbox.publish(OutboxService.PAYROLL_APPROVED, run.getId(), Map.of("period", run.getPeriodStart().toString()));
            }
            case "reject" -> {
                if (!Set.of("REVIEWED", "APPROVED").contains(run.getStatus())) throw ApiException.invalidState("Nothing to reject");
                run.setStatus("DRAFT");
            }
            case "lock" -> {
                require(run, "APPROVED");
                lock(run);
            }
            case "mark-paid" -> {
                require(run, "LOCKED");
                run.setStatus("PAID");
                run.setPaidAt(Instant.now());
                jdbc.update("update payslips set payment_status = 'PAID', updated_at = now() where run_id = ? and net > 0", run.getId());
            }
            default -> throw ApiException.badRequest("Unknown action " + action);
        }
        audit.record("PAYROLL_" + action.toUpperCase().replace('-', '_'), "PayrollRun", run.getId(), Map.of("status", run.getStatus()));
        return run;
    }

    private void lock(PayrollRun run) {
        List<UUID> recordIds = new ArrayList<>();
        for (PayrollLine l : lines.findAll((r, q, cb) -> cb.and(cb.equal(r.get("runId"), run.getId()), cb.equal(r.get("componentCode"), "DUTY_WAGE")))) {
            Object ids = l.getSource() == null ? null : l.getSource().get("recordIds");
            if (ids instanceof List<?> list) list.forEach(x -> recordIds.add(UUID.fromString(x.toString())));
        }
        if (!recordIds.isEmpty()) {
            int updated = jdbc.update("update attendance_records set payroll_run_id = ?, updated_at = now() where id = any(?) and payroll_run_id is null",
                    run.getId(), recordIds.toArray(UUID[]::new));
            if (updated != recordIds.size()) throw ApiException.conflict("Some duties were already paid in another run; recalculate");
        }
        List<UUID> adjIds = new ArrayList<>();
        for (PayrollLine l : lines.findAll((r, q, cb) -> cb.equal(r.get("runId"), run.getId()))) {
            Object a = l.getSource() == null ? null : l.getSource().get("adjustmentId");
            if (a != null) adjIds.add(UUID.fromString(a.toString()));
        }
        if (!adjIds.isEmpty())
            jdbc.update("update employee_adjustments set payroll_run_id = ? where id = any(?)", run.getId(), adjIds.toArray(UUID[]::new));
        run.setStatus("LOCKED");
        run.setLockedAt(Instant.now());
    }

    private static void require(PayrollRun run, String status) {
        if (!status.equals(run.getStatus())) throw ApiException.invalidState("Run is " + run.getStatus() + ", expected " + status);
    }

    public PayrollRun get(UUID id) {
        return runs.findById(id).orElseThrow(() -> ApiException.notFound("Payroll run"));
    }

}

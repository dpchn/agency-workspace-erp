package app.erp.payroll;

import static app.erp.common.Money.*;

import app.erp.contract.PayRate;
import app.erp.contract.PayRateResolver;
import app.erp.platform.TenantSettings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * Pure, deterministic payroll calculation (no I/O) so it can be unit-tested with golden scenarios.
 *
 * gross = Σ duty wages (rate resolved per duty) + allowances + OT + paid leave + earning adjustments
 * net   = gross - statutory deductions - other deductions
 */
public final class PayrollEngine {

    public record Duty(UUID recordId, UUID siteId, UUID clientId, UUID serviceTypeId, LocalDate date,
                       BigDecimal payUnits, int workedMinutes, int plannedMinutes, int otMinutes,
                       boolean holiday, String assignmentRole, UUID homeSiteId, UUID homeClientId, boolean arrear) {}

    public record LeaveDay(LocalDate date, String type) {}

    public record Adjustment(UUID id, String kind, BigDecimal amount, String notes, boolean earning) {}

    public record EmployeeInput(UUID employeeId, String employeeCode, UUID branchId, String stateCode, UUID primaryServiceTypeId,
                                UUID homeSiteId, UUID homeClientId, List<Duty> duties, List<LeaveDay> leaves, List<Adjustment> adjustments) {}

    public record Line(String code, String name, String kind, BigDecimal quantity, BigDecimal rate, BigDecimal amount,
                       UUID siteId, Map<String, Object> source) {}

    public record SiteCost(UUID siteId, UUID clientId, BigDecimal duties, BigDecimal wage, BigDecimal employerCost) {}

    public record Result(UUID employeeId, List<Line> lines, BigDecimal duties, BigDecimal otHours, BigDecimal leaveDays,
                         BigDecimal gross, BigDecimal deductions, BigDecimal net, BigDecimal employerCost,
                         List<SiteCost> siteCosts, List<String> exceptions) {}

    private final PayRateResolver resolver;
    private final List<SalaryComponent> components;
    private final TenantSettings settings;
    private final YearMonth month;

    public PayrollEngine(PayRateResolver resolver, List<SalaryComponent> components, TenantSettings settings, YearMonth month) {
        this.resolver = resolver;
        this.components = components.stream().filter(c -> Boolean.TRUE.equals(c.getActive()))
                .sorted(Comparator.comparing((SalaryComponent c) -> kindOrder(c.getKind())).thenComparing(SalaryComponent::getSequence)).toList();
        this.settings = settings;
        this.month = month;
    }

    private static int kindOrder(String kind) {
        return switch (kind) { case "EARNING" -> 0; case "DEDUCTION" -> 1; default -> 2; };
    }

    /** Monthly wage -> value of one duty, per tenant policy. */
    BigDecimal divisor(LocalDate date) {
        String d = settings.str("payDivisor");
        return switch (d) {
            case "CALENDAR_DAYS" -> BigDecimal.valueOf(YearMonth.from(date).lengthOfMonth());
            default -> new BigDecimal(d);
        };
    }

    private record Valued(PayRate rate, BigDecimal perDuty) {}

    private Valued value(PayRate r, Duty d) {
        if (r == null) return null;
        BigDecimal perDuty = switch (r.getWageBasis()) {
            case "MONTHLY" -> div(r.getRate(), divisor(d.date()));
            case "DAILY" -> r.getRate();
            case "HOURLY" -> r.getRate().multiply(BigDecimal.valueOf(d.plannedMinutes())).divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP);
            default -> throw new IllegalStateException("Unknown wage basis " + r.getWageBasis());
        };
        return new Valued(r, perDuty);
    }

    private Valued rateFor(EmployeeInput e, Duty d) {
        var site = new PayRateResolver.DutyContext(e.employeeId(), d.siteId(), d.clientId(), d.serviceTypeId(), e.branchId(), d.date());
        Valued atSite = value(resolver.resolve(site).orElse(null), d);
        String policy = settings.str("payRatePolicy");
        if ("SITE_RATE".equals(policy) || d.homeSiteId() == null || d.homeSiteId().equals(d.siteId())) return atSite;
        var home = new PayRateResolver.DutyContext(e.employeeId(), d.homeSiteId(), d.homeClientId(), e.primaryServiceTypeId(), e.branchId(), d.date());
        Valued atHome = value(resolver.resolve(home).orElse(null), d);
        if ("HOME_RATE".equals(policy)) return atHome != null ? atHome : atSite;
        if (atSite == null) return atHome;
        if (atHome == null) return atSite;
        return atSite.perDuty().compareTo(atHome.perDuty()) >= 0 ? atSite : atHome; // HIGHER_OF
    }

    public Result calculate(EmployeeInput e) {
        List<Line> lines = new ArrayList<>();
        List<String> exceptions = new ArrayList<>();
        BigDecimal dutyWage = BigDecimal.ZERO, allowance = BigDecimal.ZERO, ot = BigDecimal.ZERO;
        BigDecimal totalDuties = BigDecimal.ZERO, otMinutes = BigDecimal.ZERO;
        BigDecimal holidayMult = settings.decimal("holidayPayMultiplier");
        BigDecimal otMult = settings.decimal("otMultiplier");

        // ---- per site grouping so the payslip shows "12 duties @ Site A, 14 @ Site B"
        record Acc(UUID siteId, UUID clientId, BigDecimal[] v, List<String> ids, Set<String> rates) {}
        Map<UUID, Acc> bySite = new LinkedHashMap<>();
        for (Duty d : e.duties()) {
            Valued v = rateFor(e, d);
            if (v == null) {
                exceptions.add("No pay rate for " + e.employeeCode() + " at site " + d.siteId() + " on " + d.date());
                continue;
            }
            BigDecimal units = d.payUnits();
            BigDecimal wage;
            if ("HOURLY".equals(v.rate().getWageBasis())) {
                int paidMinutes = Math.min(d.workedMinutes(), d.plannedMinutes());
                wage = v.rate().getRate().multiply(BigDecimal.valueOf(paidMinutes)).divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP);
            } else if ("DOUBLE_DUTY".equals(d.assignmentRole()) && v.rate().getExtraDutyRate() != null) {
                wage = v.rate().getExtraDutyRate().multiply(units);
            } else {
                wage = v.perDuty().multiply(units);
            }
            if (d.holiday()) wage = wage.multiply(holidayMult);
            BigDecimal allow = nz(v.rate().getDutyAllowance()).multiply(units);
            BigDecimal otPay = BigDecimal.ZERO;
            if (d.otMinutes() > 0) {
                BigDecimal hourly = v.rate().getOtHourlyRate() != null ? v.rate().getOtHourlyRate()
                        : div(v.perDuty(), BigDecimal.valueOf(Math.max(d.plannedMinutes(), 60) / 60.0)).multiply(otMult);
                otPay = hourly.multiply(BigDecimal.valueOf(d.otMinutes())).divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP);
                otMinutes = otMinutes.add(BigDecimal.valueOf(d.otMinutes()));
            }
            Acc acc = bySite.computeIfAbsent(d.siteId(), k -> new Acc(d.siteId(), d.clientId(),
                    new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO}, new ArrayList<>(), new TreeSet<>()));
            acc.v()[0] = acc.v()[0].add(units);
            acc.v()[1] = acc.v()[1].add(wage);
            acc.v()[2] = acc.v()[2].add(allow);
            acc.v()[3] = acc.v()[3].add(otPay);
            acc.ids().add(d.recordId().toString());
            acc.rates().add(v.rate().getWageBasis() + " " + v.rate().getRate().stripTrailingZeros().toPlainString()
                    + (d.arrear() ? " (arrear " + d.date() + ")" : ""));
            dutyWage = dutyWage.add(wage);
            allowance = allowance.add(allow);
            ot = ot.add(otPay);
            totalDuties = totalDuties.add(units);
        }
        for (Acc a : bySite.values()) {
            Map<String, Object> src = Map.of("recordIds", a.ids(), "rates", List.copyOf(a.rates()));
            lines.add(new Line("DUTY_WAGE", "Duty wages", "EARNING", a.v()[0], div(a.v()[1], a.v()[0]), round(a.v()[1]), a.siteId(), src));
            if (a.v()[2].signum() > 0) lines.add(new Line("DUTY_ALLOWANCE", "Site / duty allowance", "EARNING", a.v()[0], null, round(a.v()[2]), a.siteId(), Map.of()));
            if (a.v()[3].signum() > 0) lines.add(new Line("OT_PAY", "Overtime", "EARNING", null, null, round(a.v()[3]), a.siteId(), Map.of()));
        }

        // ---- paid leave at home rate
        BigDecimal leavePay = BigDecimal.ZERO;
        List<String> paidTypes = settings.list("paidLeaveTypes");
        long paidLeaveDays = e.leaves().stream().filter(l -> paidTypes.contains(l.type())).count();
        for (LeaveDay l : e.leaves()) {
            if (!paidTypes.contains(l.type())) continue;
            Duty pseudo = new Duty(null, e.homeSiteId(), e.homeClientId(), e.primaryServiceTypeId(), l.date(), BigDecimal.ONE, 480, 480, 0,
                    false, "REGULAR", null, null, false);
            Valued v = value(resolver.resolve(new PayRateResolver.DutyContext(e.employeeId(), e.homeSiteId(), e.homeClientId(),
                    e.primaryServiceTypeId(), e.branchId(), l.date())).orElse(null), pseudo);
            if (v == null) { exceptions.add("No pay rate to value leave of " + e.employeeCode() + " on " + l.date()); continue; }
            leavePay = leavePay.add(v.perDuty());
        }
        if (leavePay.signum() > 0)
            lines.add(new Line("LEAVE_PAY", "Paid leave", "EARNING", BigDecimal.valueOf(paidLeaveDays), null, round(leavePay), null, Map.of()));

        // ---- adjustments (earnings)
        BigDecimal adjEarn = BigDecimal.ZERO;
        for (Adjustment a : e.adjustments()) {
            if (!a.earning()) continue;
            adjEarn = adjEarn.add(a.amount());
            lines.add(new Line(a.kind(), label(a.kind()), "EARNING", null, null, round(a.amount()), null, Map.of("adjustmentId", a.id().toString())));
        }

        Map<String, BigDecimal> bases = new HashMap<>();
        bases.put("DUTY_WAGE", round(dutyWage.add(leavePay)));
        bases.put("OT_PAY", round(ot));
        BigDecimal gross = round(dutyWage).add(round(allowance)).add(round(ot)).add(round(leavePay)).add(round(adjEarn));

        // ---- configured components
        BigDecimal deductions = BigDecimal.ZERO, employer = BigDecimal.ZERO;
        boolean grossFrozen = false;
        for (SalaryComponent c : components) {
            if (c.getStateCode() != null && !c.getStateCode().equals(e.stateCode())) continue;
            LocalDate ref = month.atDay(1);
            if (ref.isBefore(c.getEffectiveFrom()) || (c.getEffectiveTo() != null && ref.isAfter(c.getEffectiveTo()))) continue;
            if (!"EARNING".equals(c.getKind()) && !grossFrozen) { bases.put("GROSS", gross); grossFrozen = true; }
            if (c.getAppliesIfGrossUpto() != null && gross.compareTo(c.getAppliesIfGrossUpto()) > 0) continue;
            BigDecimal base = c.getBase() == null ? gross : bases.getOrDefault(c.getBase(), "GROSS".equals(c.getBase()) ? gross : BigDecimal.ZERO);
            BigDecimal amt = switch (c.getCalcType()) {
                case "PERCENT" -> {
                    BigDecimal b = c.getBaseCeiling() != null ? base.min(c.getBaseCeiling()) : base;
                    yield b.multiply(c.getPercent()).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
                }
                case "FIXED" -> gross.signum() > 0 ? c.getFixedAmount() : BigDecimal.ZERO;
                case "SLAB" -> slab(c.getSlabs(), base);
                default -> BigDecimal.ZERO;
            };
            amt = round(amt);
            if (amt.signum() == 0) continue;
            bases.put(c.getCode(), amt);
            lines.add(new Line(c.getCode(), c.getName(), c.getKind(), null, c.getPercent(), amt, null, Map.of("base", c.getBase() == null ? "GROSS" : c.getBase())));
            switch (c.getKind()) {
                case "EARNING" -> gross = gross.add(amt);
                case "DEDUCTION" -> deductions = deductions.add(amt);
                default -> employer = employer.add(amt);
            }
        }
        for (Adjustment a : e.adjustments()) {
            if (a.earning()) continue;
            deductions = deductions.add(round(a.amount()));
            lines.add(new Line(a.kind(), label(a.kind()), "DEDUCTION", null, null, round(a.amount()), null, Map.of("adjustmentId", a.id().toString())));
        }
        BigDecimal net = gross.subtract(deductions);
        if (net.signum() < 0) exceptions.add(e.employeeCode() + ": deductions exceed gross by " + net.negate());

        // ---- site cost allocation (feeds client/site margin)
        List<SiteCost> costs = new ArrayList<>();
        BigDecimal siteWageTotal = bySite.values().stream().map(a -> a.v()[1].add(a.v()[2]).add(a.v()[3])).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal overhead = employer.add(leavePay).add(adjEarn); // spread pro-rata
        for (Acc a : bySite.values()) {
            BigDecimal w = a.v()[1].add(a.v()[2]).add(a.v()[3]);
            BigDecimal share = siteWageTotal.signum() == 0 ? BigDecimal.ZERO : w.divide(siteWageTotal, 8, RoundingMode.HALF_UP);
            costs.add(new SiteCost(a.siteId(), a.clientId(), a.v()[0], round(w), round(overhead.multiply(share))));
        }
        return new Result(e.employeeId(), lines, totalDuties, otMinutes.divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(paidLeaveDays), round(gross), round(deductions), round(net), round(employer), costs, exceptions);
    }

    static BigDecimal slab(List<Map<String, Object>> slabs, BigDecimal base) {
        for (Map<String, Object> s : slabs) {
            Object upto = s.get("upto");
            if (upto == null || base.compareTo(new BigDecimal(upto.toString())) <= 0) return new BigDecimal(s.get("amount").toString());
        }
        return BigDecimal.ZERO;
    }

    private static String label(String kind) {
        return switch (kind) {
            case "ADVANCE_RECOVERY" -> "Advance recovery";
            case "LOAN_EMI" -> "Loan EMI";
            case "UNIFORM" -> "Uniform deduction";
            case "FINE" -> "Fine";
            case "BONUS" -> "Bonus";
            case "ALLOWANCE" -> "Allowance";
            case "OTHER_EARNING" -> "Other earning";
            default -> "Other deduction";
        };
    }
}

package app.erp.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import app.erp.contract.PayRate;
import app.erp.contract.PayRateResolver;
import app.erp.platform.TenantSettings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Golden payroll scenarios. Statutory values are illustrative tenant configuration, not legal advice. */
class PayrollEngineTest {
    static final UUID GUARD = UUID.randomUUID(), SITE_A = UUID.randomUUID(), SITE_B = UUID.randomUUID(), CLIENT = UUID.randomUUID();
    static final YearMonth OCT = YearMonth.of(2026, 10);

    static PayRate rate(UUID site, String basis, String amount) {
        PayRate r = new PayRate();
        r.setServiceTypeId(GUARD);
        r.setSiteId(site);
        r.setWageBasis(basis);
        r.setRate(new BigDecimal(amount));
        r.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        return r;
    }

    static SalaryComponent pct(String code, String kind, String base, String percent, String ceiling, String grossUpto) {
        SalaryComponent c = new SalaryComponent();
        c.setCode(code);
        c.setName(code);
        c.setKind(kind);
        c.setCalcType("PERCENT");
        c.setBase(base);
        c.setPercent(new BigDecimal(percent));
        c.setBaseCeiling(ceiling == null ? null : new BigDecimal(ceiling));
        c.setAppliesIfGrossUpto(grossUpto == null ? null : new BigDecimal(grossUpto));
        c.setSequence(10);
        return c;
    }

    static PayrollEngine.Duty duty(UUID site, int day, String units, int otMinutes, String role, boolean holiday) {
        return new PayrollEngine.Duty(UUID.randomUUID(), site, CLIENT, GUARD, LocalDate.of(2026, 10, day), new BigDecimal(units),
                720, 720, otMinutes, holiday, role, SITE_A, CLIENT, false);
    }

    static PayrollEngine engine(Map<String, Object> settings, List<SalaryComponent> comps) {
        var rates = new PayRateResolver(List.of(rate(null, "MONTHLY", "15000"), rate(SITE_A, "MONTHLY", "16500"), rate(SITE_B, "DAILY", "600")));
        return new PayrollEngine(rates, comps, new TenantSettings(settings), OCT);
    }

    static PayrollEngine.EmployeeInput input(List<PayrollEngine.Duty> duties) {
        return new PayrollEngine.EmployeeInput(UUID.randomUUID(), "G1", UUID.randomUUID(), "27", GUARD, SITE_A, CLIENT, duties, List.of(), List.of());
    }

    @Test
    void transferredGuardIsPaidEachSitesRateForTheDutiesWorkedThere() {
        List<PayrollEngine.Duty> d = new ArrayList<>();
        for (int i = 1; i <= 15; i++) d.add(duty(SITE_A, i, "1", 0, "REGULAR", false));
        for (int i = 16; i <= 31; i++) d.add(duty(SITE_B, i, "1", 0, "REGULAR", false));
        var r = engine(Map.of(), List.of()).calculate(input(d));
        // 16500/31*15 = 7983.87 ; 600*16 = 9600
        assertThat(r.gross()).isEqualByComparingTo("17583.87");
        assertThat(r.siteCosts()).hasSize(2);
        assertThat(r.duties()).isEqualByComparingTo("31");
    }

    @Test
    void divisorPolicyChangesPerDutyValue() {
        List<PayrollEngine.Duty> d = List.of(duty(SITE_A, 1, "1", 0, "REGULAR", false));
        var calendar = engine(Map.of(), List.of()).calculate(input(d));
        var fixed26 = engine(Map.of("payDivisor", "26"), List.of()).calculate(input(d));
        assertThat(calendar.gross()).isEqualByComparingTo("532.26");  // 16500/31
        assertThat(fixed26.gross()).isEqualByComparingTo("634.62");   // 16500/26
    }

    @Test
    void halfDayHolidayAndOvertime() {
        var r = engine(Map.of(), List.of()).calculate(input(List.of(
                duty(SITE_B, 2, "0.5", 0, "REGULAR", false),    // 300
                duty(SITE_B, 3, "1", 0, "REGULAR", true),       // 600 x 2 holiday = 1200
                duty(SITE_B, 4, "1", 120, "REGULAR", false)))); // 600 + OT 2h x (600/12 x 2) = 600 + 200
        assertThat(r.gross()).isEqualByComparingTo("2300.00");
        assertThat(r.otHours()).isEqualByComparingTo("2.00");
    }

    @Test
    void homeRatePolicyPaysHomeSiteRateWhenWorkingElsewhere() {
        var r = engine(Map.of("payRatePolicy", "HOME_RATE"), List.of()).calculate(input(List.of(duty(SITE_B, 5, "1", 0, "REGULAR", false))));
        assertThat(r.gross()).isEqualByComparingTo("532.26"); // Site A monthly rate, not Site B daily 600
        var higher = engine(Map.of("payRatePolicy", "HIGHER_OF"), List.of()).calculate(input(List.of(duty(SITE_B, 5, "1", 0, "REGULAR", false))));
        assertThat(higher.gross()).isEqualByComparingTo("600.00");
    }

    @Test
    void statutoryComponentsRespectCeilingAndThreshold() {
        var comps = List.of(
                pct("PF_EE", "DEDUCTION", "DUTY_WAGE", "12", "15000", null),
                pct("ESI_EE", "DEDUCTION", "GROSS", "0.75", null, "21000"),
                pct("PF_ER", "EMPLOYER_CONTRIBUTION", "DUTY_WAGE", "13", "15000", null));
        List<PayrollEngine.Duty> d = new ArrayList<>();
        for (int i = 1; i <= 31; i++) d.add(duty(SITE_A, i, "1", 0, "REGULAR", false));
        var r = engine(Map.of(), comps).calculate(input(d));
        assertThat(r.gross()).isEqualByComparingTo("16500.00");
        // PF on min(16500, 15000) = 1800 ; ESI 0.75% of 16500 = 123.75
        assertThat(r.deductions()).isEqualByComparingTo("1923.75");
        assertThat(r.net()).isEqualByComparingTo("14576.25");
        assertThat(r.employerCost()).isEqualByComparingTo("1950.00");
    }

    @Test
    void missingRateIsReportedAsExceptionNotSilentlyZero() {
        var rates = new PayRateResolver(List.of());
        var r = new PayrollEngine(rates, List.of(), new TenantSettings(Map.of()), OCT).calculate(input(List.of(duty(SITE_A, 1, "1", 0, "REGULAR", false))));
        assertThat(r.exceptions()).hasSize(1);
        assertThat(r.gross()).isEqualByComparingTo("0");
    }
}

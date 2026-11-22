package app.erp.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayRateResolverTest {
    static final UUID GUARD = UUID.randomUUID(), SITE_A = UUID.randomUUID(), SITE_B = UUID.randomUUID(), EMP = UUID.randomUUID();

    static PayRate rate(String amount, UUID site, UUID emp, String from, String to) {
        PayRate r = new PayRate();
        r.setServiceTypeId(GUARD);
        r.setSiteId(site);
        r.setEmployeeId(emp);
        r.setWageBasis("MONTHLY");
        r.setRate(new BigDecimal(amount));
        r.setEffectiveFrom(LocalDate.parse(from));
        r.setEffectiveTo(to == null ? null : LocalDate.parse(to));
        return r;
    }

    PayRateResolver.DutyContext ctx(UUID site, String date) {
        return new PayRateResolver.DutyContext(EMP, site, null, GUARD, null, LocalDate.parse(date));
    }

    @Test
    void mostSpecificRuleWins() {
        var resolver = new PayRateResolver(List.of(
                rate("15000", null, null, "2026-01-01", null),
                rate("16500", SITE_A, null, "2026-01-01", null),
                rate("18000", SITE_A, EMP, "2026-01-01", null)));
        assertThat(resolver.resolve(ctx(SITE_A, "2026-10-05")).orElseThrow().getRate()).isEqualByComparingTo("18000");
        assertThat(resolver.resolve(ctx(SITE_B, "2026-10-05")).orElseThrow().getRate()).isEqualByComparingTo("15000");
    }

    @Test
    void effectiveDatingPicksRateInForceOnTheDutyDate() {
        var resolver = new PayRateResolver(List.of(
                rate("15000", null, null, "2026-01-01", "2026-10-14"),
                rate("15600", null, null, "2026-10-15", null)));
        assertThat(resolver.resolve(ctx(SITE_A, "2026-10-14")).orElseThrow().getRate()).isEqualByComparingTo("15000");
        assertThat(resolver.resolve(ctx(SITE_A, "2026-10-15")).orElseThrow().getRate()).isEqualByComparingTo("15600");
    }

    @Test
    void noMatchingRuleReturnsEmpty() {
        var resolver = new PayRateResolver(List.of(rate("15000", SITE_A, null, "2026-01-01", null)));
        assertThat(resolver.resolve(ctx(SITE_B, "2026-10-05"))).isEmpty();
    }
}

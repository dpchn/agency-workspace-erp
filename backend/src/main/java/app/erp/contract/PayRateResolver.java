package app.erp.contract;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Picks the pay rate for ONE duty. Most specific rule wins:
 * employee (16) > site (8) > client (4) > service type (2) > branch (1); ties -> latest effective date.
 * This is what lets a guard earn different amounts at different sites within one month.
 */
public final class PayRateResolver {
    private final List<PayRate> rates;

    public PayRateResolver(List<PayRate> rates) { this.rates = rates; }

    public record DutyContext(UUID employeeId, UUID siteId, UUID clientId, UUID serviceTypeId, UUID branchId, LocalDate date) {}

    public Optional<PayRate> resolve(DutyContext c) {
        return rates.stream()
                .filter(r -> r.coversDate(c.date()))
                .filter(r -> matches(r.getEmployeeId(), c.employeeId()) && matches(r.getSiteId(), c.siteId())
                        && matches(r.getClientId(), c.clientId()) && matches(r.getServiceTypeId(), c.serviceTypeId())
                        && matches(r.getBranchId(), c.branchId()))
                .max(Comparator.comparingInt(PayRateResolver::specificity).thenComparing(PayRate::getEffectiveFrom));
    }

    static int specificity(PayRate r) {
        return (r.getEmployeeId() != null ? 16 : 0) + (r.getSiteId() != null ? 8 : 0) + (r.getClientId() != null ? 4 : 0)
                + (r.getServiceTypeId() != null ? 2 : 0) + (r.getBranchId() != null ? 1 : 0);
    }

    private static boolean matches(UUID ruleValue, UUID actual) {
        return ruleValue == null || ruleValue.equals(actual);
    }
}

package app.erp.billing;

import static app.erp.common.Money.*;

import app.erp.contract.ContractRate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Pure billing calculation for ONE contract rate line over ONE window (the part of the month a
 * contract version was in force). Billable units come from the contract, not from payroll days:
 * an internal double duty is paid to the guard but never billed; a client-requested extra shift is.
 */
public final class InvoiceEngine {
    private InvoiceEngine() {}

    public record Duty(UUID recordId, LocalDate date, BigDecimal billUnits, int workedMinutes, int otMinutes,
                       boolean holiday, String shiftKind, UUID employeeId) {}

    public record Line(String type, String description, BigDecimal quantity, String unit, BigDecimal rate, BigDecimal amount,
                       List<UUID> recordIds) {}

    public record Input(ContractRate rate, String label, LocalDate from, LocalDate to, YearMonth month,
                        BigDecimal expectedDuties, List<Duty> duties, BigDecimal absencePenaltyPerDuty, boolean supplementary) {}

    public static List<Line> bill(Input in) {
        ContractRate r = in.rate();
        List<Line> out = new ArrayList<>();
        String period = in.from() + " to " + in.to();
        long days = ChronoUnit.DAYS.between(in.from(), in.to()) + 1;
        BigDecimal fraction = BigDecimal.valueOf(days).divide(BigDecimal.valueOf(in.month().lengthOfMonth()), 6, RoundingMode.HALF_UP);
        List<Duty> regular = in.duties().stream().filter(d -> "REGULAR".equals(d.shiftKind())).toList();
        List<Duty> extra = in.duties().stream().filter(d -> "EXTRA".equals(d.shiftKind())).toList();
        List<UUID> regularIds = regular.stream().map(Duty::recordId).toList();
        BigDecimal covered = regular.stream().map(Duty::billUnits).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal perDutyEquivalent = null;

        switch (r.getBillingModel()) {
            case "MONTHLY_PER_POST", "LUMPSUM_MONTHLY" -> {
                BigDecimal qty = r.getQuantity().multiply(fraction).setScale(4, RoundingMode.HALF_UP);
                // exact amount (days / days-in-month), only the displayed quantity is rounded
                BigDecimal base = r.getRate().multiply(r.getQuantity()).multiply(BigDecimal.valueOf(days))
                        .divide(BigDecimal.valueOf(in.month().lengthOfMonth()), 6, RoundingMode.HALF_UP);
                if (!in.supplementary()) {
                    String unit = "LUMPSUM_MONTHLY".equals(r.getBillingModel()) ? "month" : "post-month";
                    out.add(new Line("SERVICE", in.label() + " (" + r.getQuantity().stripTrailingZeros().toPlainString() + " " + unit + ", " + period + ")",
                            qty, unit, r.getRate(), round(base), regularIds));
                }
                BigDecimal expected = in.expectedDuties();
                if (expected.signum() > 0) perDutyEquivalent = div(base, expected);
                if (!in.supplementary() && "MONTHLY_PER_POST".equals(r.getBillingModel()) && Boolean.TRUE.equals(r.getAttendanceLinked())
                        && expected.signum() > 0 && covered.compareTo(expected) < 0) {
                    BigDecimal uncovered = expected.subtract(covered);
                    out.add(new Line("DEDUCTION", "Less: uncovered duties (" + covered.stripTrailingZeros().toPlainString() + " of "
                            + expected.stripTrailingZeros().toPlainString() + " covered)", uncovered, "duty", perDutyEquivalent,
                            round(perDutyEquivalent.multiply(uncovered)).negate(), List.of()));
                    if (in.absencePenaltyPerDuty() != null && in.absencePenaltyPerDuty().signum() > 0) {
                        out.add(new Line("PENALTY", "Penalty for uncovered duties", uncovered, "duty", in.absencePenaltyPerDuty(),
                                round(in.absencePenaltyPerDuty().multiply(uncovered)).negate(), List.of()));
                    }
                }
            }
            case "PER_HEADCOUNT_MONTH" -> {
                long heads = regular.stream().map(Duty::employeeId).distinct().count();
                BigDecimal qty = BigDecimal.valueOf(heads).multiply(fraction).setScale(4, RoundingMode.HALF_UP);
                if (!in.supplementary())
                    out.add(new Line("SERVICE", in.label() + " (" + heads + " persons deployed, " + period + ")", qty, "person-month",
                            r.getRate(), round(r.getRate().multiply(BigDecimal.valueOf(heads * days))
                                    .divide(BigDecimal.valueOf(in.month().lengthOfMonth()), 6, RoundingMode.HALF_UP)), regularIds));
            }
            case "PER_SHIFT", "PER_DAY" -> {
                perDutyEquivalent = r.getRate();
                BigDecimal normal = regular.stream().filter(d -> !d.holiday()).map(Duty::billUnits).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal hol = regular.stream().filter(Duty::holiday).map(Duty::billUnits).reduce(BigDecimal.ZERO, BigDecimal::add);
                String unit = "PER_SHIFT".equals(r.getBillingModel()) ? "shift" : "man-day";
                if (normal.signum() > 0)
                    out.add(new Line("SERVICE", in.label() + " (" + period + ")", normal, unit, r.getRate(), round(r.getRate().multiply(normal)),
                            regular.stream().filter(d -> !d.holiday()).map(Duty::recordId).toList()));
                if (hol.signum() > 0) {
                    BigDecimal hr = r.getRate().multiply(nz(r.getHolidayMultiplier()));
                    out.add(new Line("SERVICE", in.label() + " - holiday duties", hol, unit, hr, round(hr.multiply(hol)),
                            regular.stream().filter(Duty::holiday).map(Duty::recordId).toList()));
                }
                minimumTopUp(out, r, normal.add(hol), unit, in.supplementary());
            }
            case "PER_HOUR" -> {
                BigDecimal hours = regular.stream().map(d -> BigDecimal.valueOf(Math.max(0, d.workedMinutes() - d.otMinutes())))
                        .reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
                perDutyEquivalent = r.getRate().multiply(nz(r.getShiftHours()));
                if (hours.signum() > 0)
                    out.add(new Line("SERVICE", in.label() + " (" + period + ")", hours, "hour", r.getRate(), round(r.getRate().multiply(hours)), regularIds));
                minimumTopUp(out, r, hours, "hour", in.supplementary());
            }
            default -> throw new IllegalStateException("Unknown billing model " + r.getBillingModel());
        }

        // Client-requested additional shifts
        BigDecimal extraUnits = extra.stream().map(Duty::billUnits).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (extraUnits.signum() > 0) {
            BigDecimal er = r.getExtraShiftRate() != null ? r.getExtraShiftRate() : perDutyEquivalent;
            if (er != null) {
                out.add(new Line("EXTRA_SHIFT", in.label() + " - additional shifts on request", extraUnits, "shift", round(er),
                        round(er.multiply(extraUnits)), extra.stream().map(Duty::recordId).toList()));
            }
        }
        // Overtime, only if the contract prices it
        if (r.getOtHourlyRate() != null) {
            BigDecimal otHours = in.duties().stream().map(d -> BigDecimal.valueOf(d.otMinutes())).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
            if (otHours.signum() > 0)
                out.add(new Line("OVERTIME", in.label() + " - overtime", otHours, "hour", r.getOtHourlyRate(), round(r.getOtHourlyRate().multiply(otHours)), List.of()));
        }
        return out;
    }

    private static void minimumTopUp(List<Line> out, ContractRate r, BigDecimal actual, String unit, boolean supplementary) {
        if (supplementary || r.getMinimumUnits() == null || actual.compareTo(r.getMinimumUnits()) >= 0) return;
        BigDecimal gap = r.getMinimumUnits().subtract(actual);
        out.add(new Line("MINIMUM_TOPUP", "Minimum guarantee top-up (" + r.getMinimumUnits().stripTrailingZeros().toPlainString() + " " + unit + "s)",
                gap, unit, r.getRate(), round(r.getRate().multiply(gap)), List.of()));
    }

    public record Tax(BigDecimal cgst, BigDecimal sgst, BigDecimal igst) {}

    /** Intra-state supply -> CGST + SGST; inter-state -> IGST. Rates are contract/tenant configuration. */
    public static Tax gst(BigDecimal taxable, BigDecimal ratePct, String supplierState, String placeOfSupply) {
        BigDecimal tax = taxable.multiply(ratePct).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        if (supplierState != null && supplierState.equals(placeOfSupply)) {
            BigDecimal half = round(tax.divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP));
            return new Tax(half, half, ZERO);
        }
        return new Tax(ZERO, ZERO, round(tax));
    }
}

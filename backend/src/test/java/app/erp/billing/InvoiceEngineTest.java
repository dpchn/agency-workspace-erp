package app.erp.billing;

import static org.assertj.core.api.Assertions.assertThat;

import app.erp.contract.ContractRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Test;

class InvoiceEngineTest {
    static final YearMonth OCT = YearMonth.of(2026, 10);

    static ContractRate rate(String model, String amount, String qty) {
        ContractRate r = new ContractRate();
        r.setBillingModel(model);
        r.setRate(new BigDecimal(amount));
        r.setQuantity(new BigDecimal(qty));
        r.setShiftHours(new BigDecimal("12"));
        r.setAttendanceLinked(true);
        r.setHolidayMultiplier(new BigDecimal("2"));
        return r;
    }

    static List<InvoiceEngine.Duty> duties(int n, String kind) {
        List<InvoiceEngine.Duty> l = new ArrayList<>();
        for (int i = 0; i < n; i++)
            l.add(new InvoiceEngine.Duty(UUID.randomUUID(), LocalDate.of(2026, 10, 1 + i % 31), BigDecimal.ONE, 720, 0, false, kind, UUID.randomUUID()));
        return l;
    }

    static BigDecimal total(List<InvoiceEngine.Line> lines) {
        return lines.stream().map(InvoiceEngine.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void monthlyPostDeductsUncoveredDuties() {
        var lines = InvoiceEngine.bill(new InvoiceEngine.Input(rate("MONTHLY_PER_POST", "22000", "2"), "Guard", LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31), OCT, new BigDecimal("62"), duties(61, "REGULAR"), null, false));
        assertThat(total(lines)).isEqualByComparingTo("43290.32"); // 44000 - 44000/62
    }

    @Test
    void fixedMonthlyIgnoresAttendance() {
        ContractRate r = rate("MONTHLY_PER_POST", "22000", "2");
        r.setAttendanceLinked(false);
        var lines = InvoiceEngine.bill(new InvoiceEngine.Input(r, "Guard", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), OCT,
                new BigDecimal("62"), duties(40, "REGULAR"), null, false));
        assertThat(total(lines)).isEqualByComparingTo("44000.00");
    }

    @Test
    void partialMonthVersionIsProrated() {
        var lines = InvoiceEngine.bill(new InvoiceEngine.Input(rate("MONTHLY_PER_POST", "31000", "1"), "Guard", LocalDate.of(2026, 10, 16),
                LocalDate.of(2026, 10, 31), OCT, new BigDecimal("16"), duties(16, "REGULAR"), null, false));
        assertThat(total(lines)).isEqualByComparingTo("16000.00");
    }

    @Test
    void perShiftWithExtrasAndMinimumGuarantee() {
        ContractRate r = rate("PER_SHIFT", "850", "1");
        r.setExtraShiftRate(new BigDecimal("1000"));
        r.setMinimumUnits(new BigDecimal("30"));
        List<InvoiceEngine.Duty> d = new ArrayList<>(duties(25, "REGULAR"));
        d.addAll(duties(2, "EXTRA"));
        var lines = InvoiceEngine.bill(new InvoiceEngine.Input(r, "Guard", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), OCT,
                new BigDecimal("31"), d, null, false));
        // 25*850 + top-up 5*850 + 2 extras * 1000
        assertThat(total(lines)).isEqualByComparingTo("27500.00");
        assertThat(lines).extracting(InvoiceEngine.Line::type).contains("MINIMUM_TOPUP", "EXTRA_SHIFT");
    }

    @Test
    void supplementaryInvoiceDoesNotRebillMonthlyBase() {
        var lines = InvoiceEngine.bill(new InvoiceEngine.Input(rate("MONTHLY_PER_POST", "22000", "1"), "Guard", LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31), OCT, new BigDecimal("31"), duties(1, "REGULAR"), null, true));
        assertThat(lines).isEmpty();
    }

    @Test
    void gstSplitsByPlaceOfSupply() {
        var intra = InvoiceEngine.gst(new BigDecimal("1000"), new BigDecimal("18"), "27", "27");
        var inter = InvoiceEngine.gst(new BigDecimal("1000"), new BigDecimal("18"), "27", "29");
        assertThat(intra.cgst()).isEqualByComparingTo("90");
        assertThat(intra.sgst()).isEqualByComparingTo("90");
        assertThat(inter.igst()).isEqualByComparingTo("180");
    }
}

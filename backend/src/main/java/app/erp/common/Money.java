package app.erp.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
    private Money() {}

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    public static BigDecimal round(BigDecimal v) { return v == null ? ZERO : v.setScale(2, RoundingMode.HALF_UP); }

    public static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    public static BigDecimal div(BigDecimal a, BigDecimal b) {
        if (b == null || b.signum() == 0) return BigDecimal.ZERO;
        return a.divide(b, 6, RoundingMode.HALF_UP);
    }
}

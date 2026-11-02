package dev.calculo.bonds;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/** Pure calculator of the value of a single 10-year EDO bond (nominal 100 PLN). */
public final class EdoCalculator {

    private static final int TERM_YEARS = 10;
    private static final BigDecimal NOMINAL = new BigDecimal("100.00");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private EdoCalculator() {}

    /** Series code: "EDO" + purchase month (2 digits) + 2-digit year of maturity. */
    public static String seriesCode(LocalDate purchase) {
        return String.format("EDO%02d%02d", purchase.getMonthValue(), purchase.plusYears(TERM_YEARS).getYear() % 100);
    }

    public static BondValue valuePerBond(LocalDate purchase, EdoSeries series,
                                         Function<YearMonth, Optional<BigDecimal>> cpi, LocalDate on) {
        if (on.isBefore(purchase)) {
            throw new IllegalArgumentException("Valuation date " + on + " is before purchase " + purchase);
        }
        LocalDate maturity = purchase.plusYears(TERM_YEARS);
        LocalDate end = on.isAfter(maturity) ? maturity : on;

        BigDecimal base = NOMINAL;
        Set<YearMonth> missing = new TreeSet<>();
        for (int k = 0; k < TERM_YEARS; k++) {
            LocalDate start = purchase.plusYears(k);
            LocalDate next = purchase.plusYears(k + 1);
            if (!end.isAfter(start)) {
                break;
            }
            BigDecimal rate = periodRate(k, start, series, cpi, missing);
            if (!end.isBefore(next)) {
                base = base.add(interest(base, rate, 1, 1)).setScale(2, RoundingMode.HALF_UP);
            } else {
                long days = ChronoUnit.DAYS.between(start, end);
                long periodDays = ChronoUnit.DAYS.between(start, next);
                base = base.add(interest(base, rate, days, periodDays));
                break;
            }
        }
        return new BondValue(base, !missing.isEmpty(), Set.copyOf(missing));
    }

    private static BigDecimal periodRate(int k, LocalDate start, EdoSeries series,
                                         Function<YearMonth, Optional<BigDecimal>> cpi, Set<YearMonth> missing) {
        if (k == 0) {
            return series.firstYearRate();
        }
        YearMonth month = YearMonth.from(start).minusMonths(1);
        Optional<BigDecimal> inflation = cpi.apply(month);
        if (inflation.isEmpty()) {
            missing.add(month);
            return series.margin();
        }
        return inflation.get().max(BigDecimal.ZERO).add(series.margin());
    }

    /** base * rate% * days / periodDays, rounded once to the grosz (HALF_UP). */
    private static BigDecimal interest(BigDecimal base, BigDecimal rate, long days, long periodDays) {
        return base.multiply(rate).multiply(BigDecimal.valueOf(days))
                .divide(HUNDRED.multiply(BigDecimal.valueOf(periodDays)), 2, RoundingMode.HALF_UP);
    }
}

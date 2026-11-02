package dev.calculo.portfolio;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/** Money-weighted return (XIRR) of a portfolio funded by deposits. */
public final class Returns {

    public record Headline(double value, boolean annualized) {}

    private static final double LOW = -0.99;
    private static final double HIGH = 100;
    private static final int ITERATIONS = 200;
    private static final double YEAR_DAYS = 365.0;

    private Returns() {}

    /** Annualized rate r such that deposits compounded to asOf equal value. */
    public static OptionalDouble xirr(List<Flow> flows, LocalDate asOf, double value) {
        if (flows.isEmpty() || !(value > 0)) {
            return OptionalDouble.empty();
        }
        LocalDate first = flows.stream().map(Flow::date).min(LocalDate::compareTo).get();
        if (!first.isBefore(asOf)) {
            return OptionalDouble.empty();
        }
        double lo = LOW;
        double hi = HIGH;
        double fLo = residual(flows, asOf, value, lo);
        double fHi = residual(flows, asOf, value, hi);
        if (Double.isNaN(fLo) || Double.isNaN(fHi) || fLo * fHi > 0) {
            return OptionalDouble.empty();
        }
        if (fLo == 0) {
            return OptionalDouble.of(lo);
        }
        for (int i = 0; i < ITERATIONS; i++) {
            double mid = (lo + hi) / 2;
            double fMid = residual(flows, asOf, value, mid);
            if (fMid == 0) {
                return OptionalDouble.of(mid);
            }
            if (fLo * fMid < 0) {
                hi = mid;
            } else {
                lo = mid;
                fLo = fMid;
            }
        }
        return OptionalDouble.of((lo + hi) / 2);
    }

    /** Total return over the whole history, derived from the XIRR. */
    public static OptionalDouble cumulative(List<Flow> flows, LocalDate asOf, double value) {
        OptionalDouble rate = xirr(flows, asOf, value);
        if (rate.isEmpty()) {
            return OptionalDouble.empty();
        }
        long days = historyDays(flows, asOf);
        return OptionalDouble.of(Math.pow(1 + rate.getAsDouble(), days / YEAR_DAYS) - 1);
    }

    /** Annualized when history spans at least 365 days, otherwise cumulative. */
    public static Optional<Headline> headline(List<Flow> flows, LocalDate asOf, double value) {
        boolean annualized = !flows.isEmpty() && historyDays(flows, asOf) >= 365;
        OptionalDouble result = annualized ? xirr(flows, asOf, value) : cumulative(flows, asOf, value);
        return result.isPresent()
                ? Optional.of(new Headline(result.getAsDouble(), annualized))
                : Optional.empty();
    }

    private static long historyDays(List<Flow> flows, LocalDate asOf) {
        LocalDate first = flows.stream().map(Flow::date).min(LocalDate::compareTo).get();
        return ChronoUnit.DAYS.between(first, asOf);
    }

    private static double residual(List<Flow> flows, LocalDate asOf, double value, double rate) {
        double sum = 0;
        for (Flow f : flows) {
            double years = ChronoUnit.DAYS.between(f.date(), asOf) / YEAR_DAYS;
            sum += f.amount() * Math.pow(1 + rate, years);
        }
        return sum - value;
    }
}

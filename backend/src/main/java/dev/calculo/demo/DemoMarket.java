package dev.calculo.demo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;

/** Deterministic fictional prices and exchange rates for the demo profile; no network is ever used. */
final class DemoMarket {
    private DemoMarket() {}

    static String currency(String symbol) {
        return symbol.endsWith(".DE") ? "EUR" : "PLN";
    }

    static boolean isTradingDay(LocalDate day) {
        DayOfWeek d = day.getDayOfWeek();
        return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY;
    }

    /** Closing price in the instrument's own currency: a base level, a slow trend and a wave. */
    static BigDecimal close(String symbol, LocalDate day) {
        double base = switch (symbol) {
            case "AAA.DE" -> 80;
            case "BBB.WA", "BBB.PL" -> 45;
            case "CCC.WA", "CCC.PL" -> 120;
            default -> 50 + Math.floorMod(symbol.hashCode(), 100);
        };
        long t = day.toEpochDay();
        double phase = Math.floorMod(symbol.hashCode(), 628) / 100.0;
        double factor = 1 + 0.0004 * (t % 1000) + 0.08 * Math.sin(t / 17.0 + phase) + 0.03 * Math.sin(t / 5.0);
        return BigDecimal.valueOf(base * factor).setScale(2, RoundingMode.HALF_UP);
    }

    /** PLN per one EUR. */
    static BigDecimal eurRate(LocalDate day) {
        return BigDecimal.valueOf(4.25 + 0.10 * Math.sin(day.toEpochDay() / 37.0)).setScale(4, RoundingMode.HALF_UP);
    }
}

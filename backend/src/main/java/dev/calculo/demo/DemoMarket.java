package dev.calculo.demo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;

/** Deterministic fictional prices and exchange rates for the demo profile; no network is ever used. */
final class DemoMarket {
    private DemoMarket() {}

    static String currency(String symbol) {
        return symbol.endsWith(".DE") || symbol.endsWith(".XETRA") ? "EUR" : "PLN";
    }

    /** The name without the exchange suffix, so a ledger ticker and its mapped symbol share one price path. */
    private static String root(String symbol) {
        int dot = symbol.lastIndexOf('.');
        return dot < 0 ? symbol : symbol.substring(0, dot);
    }

    static boolean isTradingDay(LocalDate day) {
        DayOfWeek d = day.getDayOfWeek();
        return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY;
    }

    /** Closing price in the instrument's own currency: a base level, a slow trend and a wave. */
    static BigDecimal close(String symbol, LocalDate day) {
        String name = root(symbol);
        double base = switch (name) {
            case "AAA" -> 80;
            case "BBB" -> 45;
            case "CCC" -> 120;
            default -> 50 + Math.floorMod(name.hashCode(), 100);
        };
        long t = day.toEpochDay();
        double phase = Math.floorMod(name.hashCode(), 628) / 100.0;
        double factor = 1 + 0.0004 * (t % 1000) + 0.08 * Math.sin(t / 17.0 + phase) + 0.03 * Math.sin(t / 5.0);
        return BigDecimal.valueOf(base * factor).setScale(2, RoundingMode.HALF_UP);
    }

    /** PLN per one EUR. */
    static BigDecimal eurRate(LocalDate day) {
        return BigDecimal.valueOf(4.25 + 0.10 * Math.sin(day.toEpochDay() / 37.0)).setScale(4, RoundingMode.HALF_UP);
    }
}

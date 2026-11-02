package dev.calculo.portfolio;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReturnsTest {

    private static LocalDate d(String s) { return LocalDate.parse(s); }
    private static Flow flow(String date, double amount) { return new Flow(d(date), amount); }

    @Test
    void singleDepositOneYear() {
        var r = Returns.xirr(List.of(flow("2025-01-01", 1000)), d("2026-01-01"), 1100);
        assertEquals(0.10, r.getAsDouble(), 1e-6);
    }

    @Test
    void twoDepositsSatisfyEquation() {
        var flows = List.of(flow("2025-01-01", 1000), flow("2025-07-01", 500));
        LocalDate asOf = d("2026-01-01");
        double r = Returns.xirr(flows, asOf, 1700).getAsDouble();
        double sum = 0;
        for (Flow f : flows) {
            sum += f.amount() * Math.pow(1 + r, ChronoUnit.DAYS.between(f.date(), asOf) / 365.0);
        }
        assertEquals(0, sum - 1700, 1e-6);
    }

    @Test
    void emptyCases() {
        assertTrue(Returns.xirr(List.of(), d("2026-01-01"), 100).isEmpty());
        assertTrue(Returns.xirr(List.of(flow("2026-01-01", 1000)), d("2026-01-01"), 1000).isEmpty());
        assertTrue(Returns.xirr(List.of(flow("2025-01-01", 1000)), d("2026-01-01"), 0).isEmpty());
        assertTrue(Returns.headline(List.of(), d("2026-01-01"), 100).isEmpty());
    }

    @Test
    void headlineSwitchesAtOneYear() {
        var flows = List.of(flow("2025-01-01", 1000));
        assertFalse(Returns.headline(flows, d("2025-12-31"), 1100).get().annualized());
        assertTrue(Returns.headline(flows, d("2026-01-01"), 1100).get().annualized());
    }

    @Test
    void cumulativeAfter182Days() {
        var flows = List.of(flow("2025-01-01", 1000));
        var r = Returns.cumulative(flows, d("2025-01-01").plusDays(182), 1050);
        assertEquals(0.05, r.getAsDouble(), 1e-6);
    }

    @Test
    void flowsAfterAsOfAreIgnored() {
        LocalDate asOf = d("2025-06-01");
        var single = Returns.xirr(List.of(flow("2025-01-01", 1000)), asOf, 1040);
        var both = Returns.xirr(List.of(flow("2025-01-01", 1000), flow("2025-07-01", 500)), asOf, 1040);
        assertEquals(single.getAsDouble(), both.getAsDouble(), 1e-9);
    }

    @Test
    void headlineEmptyWhenOnlyFlowIsAfterAsOf() {
        assertTrue(Returns.headline(List.of(flow("2025-07-01", 500)), d("2025-06-01"), 500).isEmpty());
    }

    @Test
    void modestGainOverFewDaysHasCumulativeReturn() {
        var r = Returns.cumulative(List.of(flow("2026-01-01", 1000)), d("2026-01-04"), 1050);
        assertEquals(0.05, r.getAsDouble(), 1e-6);
        var doubled = Returns.cumulative(List.of(flow("2026-01-01", 1000)), d("2026-01-02"), 2000);
        assertEquals(1.0, doubled.getAsDouble(), 1e-6);
    }

    @Test
    void zeroValueAndSameDayStayEmpty() {
        assertTrue(Returns.cumulative(List.of(flow("2026-01-01", 1000)), d("2026-01-04"), 0).isEmpty());
        assertTrue(Returns.cumulative(List.of(flow("2026-01-01", 1000)), d("2026-01-01"), 1050).isEmpty());
    }
}

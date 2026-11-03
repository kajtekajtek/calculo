package dev.calculo.bonds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class EdoCalculatorTest {

    private static final Function<YearMonth, Optional<BigDecimal>> NO_CPI = ym -> Optional.empty();

    private static EdoSeries s(String first, String margin) {
        return new EdoSeries("EDO", new BigDecimal(first), new BigDecimal(margin));
    }

    private static Function<YearMonth, Optional<BigDecimal>> cpi(String ym, String value) {
        Map<YearMonth, BigDecimal> map = Map.of(YearMonth.parse(ym), new BigDecimal(value));
        return m -> Optional.ofNullable(map.get(m));
    }

    private static BondValue value(String purchase, EdoSeries series,
                                   Function<YearMonth, Optional<BigDecimal>> cpi, String on) {
        return EdoCalculator.valuePerBond(LocalDate.parse(purchase), series, cpi, LocalDate.parse(on));
    }

    private static void assertValue(String expected, BondValue actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.value()),
                "expected " + expected + " but was " + actual.value());
    }

    @Test
    void seriesCodeFromPurchaseDate() {
        assertEquals("EDO1141", EdoCalculator.seriesCode(LocalDate.of(2031, 11, 5)));
        assertEquals("EDO0641", EdoCalculator.seriesCode(LocalDate.of(2031, 6, 1)));
    }

    @Test
    void firstYearInterestIsRoundedHalfUpPerBond() {
        // 100 * rate * days / 365, in a period of 365 days: 4.5107, 3.7589 and 0.8941.
        assertValue("104.51", value("2031-01-15", s("5.60", "2.00"), NO_CPI, "2031-11-05")); // 294 days
        assertValue("103.76", value("2031-01-15", s("5.60", "2.00"), NO_CPI, "2031-09-17")); // 245 days
        assertValue("100.89", value("2031-01-15", s("5.35", "2.00"), NO_CPI, "2031-03-17")); // 61 days
        assertValue("100.00", value("2031-01-15", s("5.60", "2.00"), NO_CPI, "2031-01-15"));
        assertFalse(value("2031-01-15", s("5.60", "2.00"), NO_CPI, "2031-11-05").estimated());
    }

    @Test
    void secondYearCapitalisesWithInflation() {
        // 2033-2035 has no leap day, so both periods have 365 days.
        var cpi = cpi("2033-12", "3.0");
        var series = s("5.60", "2.00");
        BondValue atAnniversary = value("2033-01-15", series, cpi, "2034-01-15");
        assertValue("105.60", atAnniversary);
        assertFalse(atAnniversary.estimated());
        BondValue partial = value("2033-01-15", series, cpi, "2034-07-16");
        assertValue("108.23", partial);
        assertFalse(partial.estimated());
        assertTrue(partial.missingCpi().isEmpty());
    }

    @Test
    void negativeInflationIsFloorAtZero() {
        assertValue("106.65", value("2033-01-15", s("5.60", "2.00"), cpi("2033-12", "-1.0"), "2034-07-16"));
    }

    @Test
    void missingCpiIsEstimatedWithMarginOnly() {
        BondValue v = value("2033-01-15", s("5.60", "2.00"), NO_CPI, "2034-07-16");
        assertValue("106.65", v);
        assertTrue(v.estimated());
        assertEquals(Set.of(YearMonth.of(2033, 12)), v.missingCpi());
    }

    @Test
    void valueIsFrozenAfterMaturity() {
        var series = s("5.60", "2.00");
        BondValue atMaturity = value("2033-01-15", series, NO_CPI, "2043-01-15");
        assertEquals(atMaturity.value(), value("2033-01-15", series, NO_CPI, "2047-01-01").value());
        assertEquals(9, atMaturity.missingCpi().size());
    }

    @Test
    void dateBeforePurchaseIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> value("2033-01-15", s("5.60", "2.00"), NO_CPI, "2033-01-14"));
    }
}

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
        assertEquals("EDO1136", EdoCalculator.seriesCode(LocalDate.of(2026, 11, 2)));
        assertEquals("EDO0636", EdoCalculator.seriesCode(LocalDate.of(2026, 6, 1)));
    }

    @Test
    void firstYearMatchesBank() {
        assertValue("104.51", value("2026-01-12", s("5.60", "2.00"), NO_CPI, "2026-11-02"));
        assertValue("103.76", value("2026-03-02", s("5.60", "2.00"), NO_CPI, "2026-11-02"));
        assertValue("100.89", value("2026-09-02", s("5.35", "2.00"), NO_CPI, "2026-11-02"));
        assertValue("100.00", value("2026-11-02", s("5.60", "2.00"), NO_CPI, "2026-11-02"));
        assertFalse(value("2026-01-12", s("5.60", "2.00"), NO_CPI, "2026-11-02").estimated());
    }

    @Test
    void secondYearCapitalisesWithInflation() {
        var cpi = cpi("2026-12", "3.0");
        var series = s("5.60", "2.00");
        BondValue atAnniversary = value("2026-01-12", series, cpi, "2027-01-12");
        assertValue("105.60", atAnniversary);
        assertFalse(atAnniversary.estimated());
        BondValue partial = value("2026-01-12", series, cpi, "2027-07-13");
        assertValue("108.23", partial);
        assertFalse(partial.estimated());
        assertTrue(partial.missingCpi().isEmpty());
    }

    @Test
    void negativeInflationIsFloorAtZero() {
        assertValue("106.65", value("2026-01-12", s("5.60", "2.00"), cpi("2026-12", "-1.0"), "2027-07-13"));
    }

    @Test
    void missingCpiIsEstimatedWithMarginOnly() {
        BondValue v = value("2026-01-12", s("5.60", "2.00"), NO_CPI, "2027-07-13");
        assertValue("106.65", v);
        assertTrue(v.estimated());
        assertEquals(Set.of(YearMonth.of(2026, 12)), v.missingCpi());
    }

    @Test
    void valueIsFrozenAfterMaturity() {
        var series = s("5.60", "2.00");
        BondValue atMaturity = value("2026-01-12", series, NO_CPI, "2036-01-12");
        assertEquals(atMaturity.value(), value("2026-01-12", series, NO_CPI, "2040-01-01").value());
        assertEquals(9, atMaturity.missingCpi().size());
    }

    @Test
    void dateBeforePurchaseIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> value("2026-01-12", s("5.60", "2.00"), NO_CPI, "2026-01-11"));
    }
}

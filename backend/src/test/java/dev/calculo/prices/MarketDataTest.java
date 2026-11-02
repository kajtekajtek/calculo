package dev.calculo.prices;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class MarketDataTest {

    private static TreeMap<LocalDate, BigDecimal> series(Object... pairs) {
        TreeMap<LocalDate, BigDecimal> m = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((LocalDate) pairs[i], new BigDecimal((String) pairs[i + 1]));
        return m;
    }

    private static final LocalDate FRI = LocalDate.of(2026, 10, 30);
    private static final LocalDate SAT = LocalDate.of(2026, 10, 31);
    private static final LocalDate THU = LocalDate.of(2026, 10, 29);

    @Test
    void saturdayTakesFriday() {
        MarketData data = new MarketData(Map.of("BBB.PL", series(THU, "20.00", FRI, "21.00")),
                Map.of("BBB.PL", "PLN"), Map.of());

        assertThat(data.pricePln("BBB.PL", SAT))
                .contains(new MarketData.PricedAt(new BigDecimal("21.00"), FRI));
    }

    @Test
    void foreignCurrencyIsMultipliedByTheLatestRateNotAfterTheDay() {
        MarketData data = new MarketData(Map.of("AAA.DE", series(FRI, "10.00")), Map.of("AAA.DE", "EUR"),
                Map.of("EUR", series(THU, "4.20", FRI, "4.25", LocalDate.of(2026, 11, 2), "9.99")));

        Optional<MarketData.PricedAt> priced = data.pricePln("AAA.DE", SAT);

        assertThat(priced).isPresent();
        assertThat(priced.get().pln()).isEqualByComparingTo("42.50");
        assertThat(priced.get().priceDate()).isEqualTo(FRI);
    }

    @Test
    void missingPriceOrRateIsEmpty() {
        MarketData data = new MarketData(Map.of("AAA.DE", series(FRI, "10.00")), Map.of("AAA.DE", "EUR"), Map.of());

        assertThat(data.pricePln("AAA.DE", FRI)).isEmpty(); // no FX at all
        assertThat(data.pricePln("ZZZ.DE", FRI)).isEmpty(); // unknown ticker
        assertThat(data.pricePln("AAA.DE", THU)).isEmpty(); // nothing priced yet that day
    }
}

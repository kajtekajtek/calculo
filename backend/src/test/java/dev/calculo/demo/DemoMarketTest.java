package dev.calculo.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The seeder prices purchases by ledger ticker, the provider by mapped symbol: both must see the same market. */
class DemoMarketTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 27);

    @Test
    void mappedSymbolsHaveTheSamePriceAsTheirTicker() {
        assertThat(DemoMarket.close("AAA.XETRA", DAY)).isEqualTo(DemoMarket.close("AAA.DE", DAY));
        assertThat(DemoMarket.close("BBB.WAR", DAY)).isEqualTo(DemoMarket.close("BBB.PL", DAY));
        assertThat(DemoMarket.close("CCC.WAR", DAY)).isEqualTo(DemoMarket.close("CCC.PL", DAY));
    }

    @Test
    void germanListingsAreInEurosUnderEitherSuffix() {
        assertThat(DemoMarket.currency("AAA.DE")).isEqualTo("EUR");
        assertThat(DemoMarket.currency("AAA.XETRA")).isEqualTo("EUR");
        assertThat(DemoMarket.currency("BBB.WAR")).isEqualTo("PLN");
    }
}

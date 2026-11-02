package dev.calculo.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.calculo.PostgresTestBase;
import dev.calculo.xtb.InstrumentsChanged;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "calculo.prices.sync-on-import=true")
class PriceSyncIT extends PostgresTestBase {

    record PriceCall(String symbol, LocalDate from, LocalDate to) {}

    record FxCall(String currency, LocalDate from, LocalDate to) {}

    static final List<PriceCall> PRICE_CALLS = new CopyOnWriteArrayList<>();
    static final List<FxCall> FX_CALLS = new CopyOnWriteArrayList<>();
    static final Set<String> FAILING_FX = ConcurrentHashMap.newKeySet();
    static final Set<String> FAILING = ConcurrentHashMap.newKeySet();

    @TestConfiguration
    static class Stubs {
        @Bean @Primary
        PriceProvider stubPrices() {
            return (symbol, from, to) -> {
                PRICE_CALLS.add(new PriceCall(symbol, from, to));
                if (FAILING.contains(symbol)) throw new PriceUnavailableException("boom " + symbol);
                String currency = symbol.endsWith(".DE") ? "EUR" : "PLN";
                List<DailyPrice> prices = new ArrayList<>();
                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                    if (d.getDayOfWeek().getValue() < 6) prices.add(new DailyPrice(d, new BigDecimal("10.00")));
                }
                return new PriceSeries(currency, prices);
            };
        }

        @Bean @Primary
        FxProvider stubFx() {
            return (currency, from, to) -> {
                FX_CALLS.add(new FxCall(currency, from, to));
                // background syncs left over from other tests must not trip the failure injected for the test thread
                if (!Thread.currentThread().getName().equals("price-sync")
                        && FAILING_FX.contains(currency + "@" + FX_CALLS.size())) throw new PriceUnavailableException("fx boom");
                Map<LocalDate, BigDecimal> rates = new TreeMap<>();
                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                    if (d.getDayOfWeek().getValue() < 6) rates.put(d, new BigDecimal("4.30"));
                }
                return rates;
            };
        }

        @Bean @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired PriceSyncService sync;
    @Autowired MarketDataRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate rest;
    @Autowired ApplicationEventPublisher events;

    @BeforeEach
    void seed() {
        dev.calculo.TestAuth.login(rest);
        PRICE_CALLS.clear();
        FX_CALLS.clear();
        FAILING.clear();
        FAILING_FX.clear();
        jdbc.update("delete from price_history");
        jdbc.update("delete from fx_rate");
        jdbc.update("delete from operation");
        jdbc.update("delete from instrument");
        jdbc.update("delete from account");
        jdbc.update("insert into account (id, name, broker) values ('xtb:T', 'T', 'XTB')");
        jdbc.update("insert into instrument (ticker, name, category) values ('AAA.DE', 'Alpha', 'ETF')");
        jdbc.update("insert into instrument (ticker, name, category, price_symbol) values ('BBB.PL', 'Beta', 'STOCK', 'BBB.OVERRIDE')");
        operation("1", "AAA.DE", "2026-10-26T09:00:00Z");
        operation("2", "AAA.DE", "2026-10-28T09:00:00Z");
        operation("3", "BBB.PL", "2026-10-27T09:00:00Z");
    }

    private void operation(String id, String ticker, String at) {
        jdbc.update("insert into operation (account_id, external_id, type, ticker, occurred_at, amount_pln) "
                + "values ('xtb:T', ?, 'PURCHASE', ?, ?::timestamptz, -100)", id, ticker, at);
    }

    @Test
    void firstSyncStartsAtTheFirstOperationAndStoresCurrencyAndFx() {
        sync.sync();

        assertThat(PRICE_CALLS).contains(
                new PriceCall("AAA.DE", LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 2)),
                new PriceCall("BBB.OVERRIDE", LocalDate.of(2026, 10, 27), LocalDate.of(2026, 11, 2)));
        assertThat(jdbc.queryForObject("select currency from instrument where ticker = 'AAA.DE'", String.class))
                .isEqualTo("EUR");
        assertThat(jdbc.queryForObject("select currency from instrument where ticker = 'BBB.PL'", String.class))
                .isEqualTo("PLN");
        // FX only for EUR, from a few days before the earliest price day so the first day has a rate.
        assertThat(FX_CALLS).hasSize(1);
        assertThat(FX_CALLS.get(0).currency()).isEqualTo("EUR");
        assertThat(FX_CALLS.get(0).from()).isBeforeOrEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(FX_CALLS.get(0).to()).isEqualTo(LocalDate.of(2026, 11, 2));
        MarketData data = repository.load();
        assertThat(data.pricePln("AAA.DE", LocalDate.of(2026, 11, 1)).orElseThrow().pln())
                .isEqualByComparingTo("43.00");
        assertThat(data.pricePln("BBB.PL", LocalDate.of(2026, 10, 27))).isPresent();
        assertThat(sync.syncStatus()).isEmpty();
    }

    @Test
    void laterSyncFillsGapsFromTheLastStoredDay() {
        jdbc.update("insert into price_history (ticker, day, close, currency) values ('AAA.DE', '2026-10-29', 9, 'EUR')");
        jdbc.update("insert into price_history (ticker, day, close, currency) values ('BBB.PL', '2026-11-02', 9, 'PLN')");
        jdbc.update("insert into fx_rate (currency, day, mid) values ('EUR', '2026-10-30', 4.2)");

        sync.sync();

        assertThat(PRICE_CALLS).contains(new PriceCall("AAA.DE", LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 2)));
        // Already up to date: only the last stored day (today) is re-fetched, not the whole history.
        assertThat(PRICE_CALLS).contains(
                new PriceCall("BBB.OVERRIDE", LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 2)));
        assertThat(FX_CALLS).extracting(FxCall::from).contains(LocalDate.of(2026, 10, 30));
    }

    @Test
    void oneFailingTickerKeepsTheOthersAndIsReportedUntilItSucceeds() {
        FAILING.add("AAA.DE");

        sync.sync();

        assertThat(sync.syncStatus()).containsKey("AAA.DE").doesNotContainKey("BBB.PL");
        assertThat(sync.syncStatus().get("AAA.DE")).contains("boom");
        assertThat(jdbc.queryForObject("select count(*) from price_history where ticker = 'BBB.PL'", Integer.class))
                .isPositive();
        assertThat(jdbc.queryForObject("select count(*) from price_history where ticker = 'AAA.DE'", Integer.class))
                .isZero();

        FAILING.clear();
        sync.sync();

        assertThat(sync.syncStatus()).isEmpty(); // a full success clears every earlier error
        assertThat(jdbc.queryForObject("select count(*) from price_history where ticker = 'AAA.DE'", Integer.class))
                .isPositive();
    }

    @Test
    void repeatedSyncCreatesNoDuplicates() {
        sync.sync();
        Integer prices = jdbc.queryForObject("select count(*) from price_history", Integer.class);
        Integer fx = jdbc.queryForObject("select count(*) from fx_rate", Integer.class);

        sync.sync();

        assertThat(jdbc.queryForObject("select count(*) from price_history", Integer.class)).isEqualTo(prices);
        assertThat(jdbc.queryForObject("select count(*) from fx_rate", Integer.class)).isEqualTo(fx);
    }

    @Test
    void refreshEndpointAnswers202AndSyncsInTheBackground() {
        var response = rest.postForEntity("/api/prices/refresh", null, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select count(*) from price_history", Integer.class)).isPositive());
    }

    @Test
    void instrumentsChangedTriggersASync() {
        events.publishEvent(new InstrumentsChanged());

        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select count(*) from price_history", Integer.class)).isPositive());
    }

    @Test
    void olderOperationsImportedLaterGetTheirPricesAndRates() {
        jdbc.update("insert into price_history (ticker, day, close, currency) values ('AAA.DE', '2026-10-29', 9, 'EUR')");
        jdbc.update("insert into fx_rate (currency, day, mid) values ('EUR', '2026-10-29', 4.2)");

        sync.sync();

        assertThat(PRICE_CALLS).contains(new PriceCall("AAA.DE", LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 29)));
        assertThat(FX_CALLS).extracting(FxCall::from).contains(LocalDate.of(2026, 10, 19));
        assertThat(jdbc.queryForObject("select min(day) from price_history where ticker = 'AAA.DE'", java.sql.Date.class))
                .hasToString("2026-10-26");
        assertThat(jdbc.queryForObject("select min(day) from fx_rate where currency = 'EUR'", java.sql.Date.class))
                .hasToString("2026-10-19");
    }

    @Test
    void failedSecondChunkStoresNothingForThatCurrency() {
        jdbc.update("insert into fx_rate (currency, day, mid) values ('EUR', '2026-10-29', 4.2)");
        FAILING_FX.add("EUR@2"); // the older range is call 1, the range since the last day is call 2

        sync.sync();

        assertThat(jdbc.queryForObject("select count(*) from fx_rate", Integer.class)).isEqualTo(1);
        assertThat(sync.syncStatus()).containsKey("FX:EUR");
    }
}

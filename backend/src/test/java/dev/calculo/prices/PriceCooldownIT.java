package dev.calculo.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.calculo.PostgresTestBase;
import dev.calculo.xtb.InstrumentsChanged;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
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

/** All triggers are enabled here, so "no calls during cooldown" means every one of them honours it. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"calculo.prices.sync-on-import=true", "calculo.prices.sync-on-startup=true",
        "calculo.prices.schedule-enabled=true"})
class PriceCooldownIT extends PostgresTestBase {

    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-02T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    static final MutableClock CLOCK = new MutableClock();
    static final List<String> PRICE_CALLS = new CopyOnWriteArrayList<>();
    static final List<String> FX_CALLS = new CopyOnWriteArrayList<>();
    static volatile boolean limited;
    static volatile Duration retryAfter;
    static volatile CountDownLatch gate;

    @TestConfiguration
    static class Stubs {
        @Bean @Primary
        PriceProvider stubPrices() {
            return (symbol, from, to) -> {
                PRICE_CALLS.add(symbol);
                CountDownLatch waitFor = gate;
                if (waitFor != null) {
                    try {
                        waitFor.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (limited) throw new RateLimitedException("HTTP 429 for " + symbol, retryAfter);
                return new PriceSeries("EUR", List.of());
            };
        }

        @Bean @Primary
        FxProvider stubFx() {
            return (currency, from, to) -> {
                FX_CALLS.add(currency);
                return new TreeMap<LocalDate, java.math.BigDecimal>();
            };
        }

        @Bean @Primary
        Clock mutableClock() {
            return CLOCK;
        }
    }

    @Autowired PriceSyncService sync;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate rest;
    @Autowired ApplicationEventPublisher events;

    @BeforeEach
    void seed() {
        dev.calculo.TestAuth.login(rest);
        CLOCK.advance(Duration.ofDays(30)); // ends a cooldown left by the previous test
        limited = false;
        retryAfter = null;
        gate = null;
        idle();
        PRICE_CALLS.clear();
        FX_CALLS.clear();
        jdbc.update("delete from operation");
        jdbc.update("delete from instrument");
        jdbc.update("delete from account");
        jdbc.update("insert into account (id, name, broker) values ('xtb:T', 'T', 'XTB')");
        for (String ticker : List.of("AAA.DE", "BBB.DE", "CCC.DE")) {
            jdbc.update("insert into instrument (ticker, name, category) values (?, ?, 'ETF')", ticker, ticker);
            jdbc.update("insert into operation (account_id, external_id, type, ticker, occurred_at, amount_pln) "
                    + "values ('xtb:T', ?, 'PURCHASE', ?, '2026-10-26T09:00:00Z'::timestamptz, -100)", ticker, ticker);
        }
    }

    private Map<?, ?> status() {
        var response = rest.getForEntity("/api/prices/status", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void idle() {
        await().atMost(Duration.ofSeconds(10)).until(() -> !sync.state().running());
    }

    private void hitRateLimit(Duration after) {
        limited = true;
        retryAfter = after;
        sync.sync();
        limited = false;
        PRICE_CALLS.clear();
    }

    @Test
    void rateLimitAbortsTheRunAfterTheFirstTicker() {
        limited = true;
        retryAfter = Duration.ofSeconds(120);

        sync.sync();

        assertThat(PRICE_CALLS).hasSize(1);
        assertThat(FX_CALLS).isEmpty();
        assertThat(sync.syncStatus()).containsKey(PRICE_CALLS.get(0));
        Map<?, ?> status = status();
        assertThat(status.get("running")).isEqualTo(false);
        assertThat(Instant.parse((String) status.get("notBefore"))).isEqualTo(CLOCK.instant().plusSeconds(120));
    }

    @Test
    void withoutARetryAfterTheCooldownIsOneHour() {
        hitRateLimit(null);

        assertThat(sync.state().notBefore()).isEqualTo(CLOCK.instant().plus(Duration.ofHours(1)));
    }

    @Test
    void noTriggerCallsTheProviderDuringCooldown() {
        hitRateLimit(Duration.ofMinutes(10));

        assertThat(rest.postForEntity("/api/prices/refresh", null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(status().get("running")).isEqualTo(false);
        sync.sync();
        sync.scheduled();
        sync.onStartup();
        events.publishEvent(new InstrumentsChanged());
        idle();

        assertThat(PRICE_CALLS).isEmpty();
        assertThat(FX_CALLS).isEmpty();
    }

    @Test
    void cooldownExpiresWithTheClock() {
        hitRateLimit(Duration.ofSeconds(120));

        CLOCK.advance(Duration.ofSeconds(119));
        assertThat(status().get("notBefore")).isNotNull();
        rest.postForEntity("/api/prices/refresh", null, Void.class);
        idle();
        assertThat(PRICE_CALLS).isEmpty();

        CLOCK.advance(Duration.ofSeconds(1));
        assertThat(status().get("notBefore")).isNull();
        rest.postForEntity("/api/prices/refresh", null, Void.class);
        idle();
        assertThat(PRICE_CALLS).hasSize(3);
    }

    @Test
    void statusShowsARunningSync() {
        assertThat(status().get("running")).isEqualTo(false);
        assertThat(status().get("notBefore")).isNull();
        gate = new CountDownLatch(1);

        rest.postForEntity("/api/prices/refresh", null, Void.class);

        assertThat(status().get("running")).isEqualTo(true); // true at once, not only after the worker picked it up
        await().atMost(Duration.ofSeconds(10)).until(() -> !PRICE_CALLS.isEmpty());
        assertThat(status().get("running")).isEqualTo(true);
        gate.countDown();
        await().atMost(Duration.ofSeconds(10)).until(() -> status().get("running").equals(false));
    }
}

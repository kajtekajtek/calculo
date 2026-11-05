package dev.calculo.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EodhdPriceProviderTest {
    private static final String KEY = "secret-key-123";
    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final LocalDate FROM = LocalDate.of(2026, 10, 26);
    private static final LocalDate TO = LocalDate.of(2026, 10, 28);
    private static final Map<String, String> CURRENCIES = Map.of("WAR", "PLN", "XETRA", "EUR");

    private HttpServer server;
    private int status = 200;
    private String body = "[]";
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger budgetUsed = new AtomicInteger();
    private volatile boolean budgetLeft = true;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();

    private final CallBudget budget = () -> {
        if (!budgetLeft) return false;
        budgetUsed.incrementAndGet();
        return true;
    };

    private EodhdPriceProvider provider(String key) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            lastPath.set(exchange.getRequestURI().getPath());
            lastQuery.set(exchange.getRequestURI().getQuery());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new EodhdPriceProvider("http://127.0.0.1:" + server.getAddress().getPort(), key, CURRENCIES, budget, CLOCK);
    }

    private EodhdPriceProvider provider() throws IOException {
        return provider(KEY);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void returnsClosesInTheRangeWithTheCurrencyOfTheExchange() throws Exception {
        body = """
                [{"date":"2026-10-23","close":99.0},
                 {"date":"2026-10-26","open":1,"close":101.5,"adjusted_close":100.1},
                 {"date":"2026-10-27","close":null},
                 {"date":"2026-10-28","close":102.25},
                 {"date":"2026-10-29","close":103.0}]""";

        PriceSeries series = provider().history("AAA.XETRA", FROM, TO);

        assertThat(series.currency()).isEqualTo("EUR");
        assertThat(series.prices()).containsExactly(
                new DailyPrice(LocalDate.of(2026, 10, 26), new BigDecimal("101.5")),
                new DailyPrice(LocalDate.of(2026, 10, 28), new BigDecimal("102.25")));
        assertThat(lastPath.get()).isEqualTo("/api/eod/AAA.XETRA");
        assertThat(lastQuery.get()).contains("api_token=" + KEY, "from=2026-10-26", "to=2026-10-28",
                "period=d", "fmt=json");
        assertThat(budgetUsed.get()).isEqualTo(1);
    }

    @Test
    void warsawSymbolsArePricedInZloty() throws Exception {
        body = "[{\"date\":\"2026-10-26\",\"close\":12.3}]";

        assertThat(provider().history("BBB.WAR", FROM, TO).currency()).isEqualTo("PLN");
    }

    @Test
    void emptyAnswerIsAnEmptySeries() throws Exception {
        PriceSeries series = provider().history("BBB.WAR", FROM, TO);

        assertThat(series.currency()).isEqualTo("PLN");
        assertThat(series.prices()).isEmpty();
    }

    @Test
    void unknownExchangeFailsWithoutARequestOrBudget() throws Exception {
        EodhdPriceProvider p = provider();

        assertThatThrownBy(() -> p.history("CCC.LSE", FROM, TO))
                .isInstanceOf(PriceUnavailableException.class)
                .isNotInstanceOf(RateLimitedException.class)
                .hasMessageContaining("LSE");
        assertThat(requests.get()).isZero();
        assertThat(budgetUsed.get()).isZero();
    }

    @Test
    void missingKeyMeansNotConfiguredWithoutARequestOrBudget() throws Exception {
        EodhdPriceProvider p = provider(" ");

        assertThatThrownBy(() -> p.history("AAA.XETRA", FROM, TO))
                .isInstanceOf(ProviderNotConfiguredException.class)
                .hasMessageContaining("EODHD_API_KEY");
        assertThat(requests.get()).isZero();
        assertThat(budgetUsed.get()).isZero();
    }

    @Test
    void spentBudgetIsARateLimitUntilMidnightUtcWithoutARequest() throws Exception {
        budgetLeft = false;
        EodhdPriceProvider p = provider();

        assertThatThrownBy(() -> p.history("AAA.XETRA", FROM, TO))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofHours(14)));
        assertThat(requests.get()).isZero();
    }

    @Test
    void http429IsARateLimitUntilMidnightUtc() throws Exception {
        status = 429;
        body = "Too Many Requests";
        EodhdPriceProvider p = provider();

        assertThatThrownBy(() -> p.history("AAA.XETRA", FROM, TO))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofHours(14)));
    }

    @Test
    void http402IsARateLimitToo() throws Exception {
        status = 402;
        EodhdPriceProvider p = provider();

        assertThatThrownBy(() -> p.history("AAA.XETRA", FROM, TO)).isInstanceOf(RateLimitedException.class);
    }

    @Test
    void rejectedKeySaysSoAndNeverShowsTheKey() throws Exception {
        status = 401;
        body = "Unauthenticated " + KEY;
        EodhdPriceProvider p = provider();

        assertThatThrownBy(() -> p.history("AAA.XETRA", FROM, TO))
                .isInstanceOf(ProviderNotConfiguredException.class)
                .hasMessageContaining("401")
                .hasMessageNotContaining(KEY);
    }

    @Test
    void unknownSymbolIsReported() throws Exception {
        status = 404;
        body = "Ticker Not Found.";
        EodhdPriceProvider p = provider();

        assertThatThrownBy(() -> p.history("ZZZ.WAR", FROM, TO))
                .isInstanceOf(PriceUnavailableException.class)
                .isNotInstanceOf(RateLimitedException.class)
                .hasMessageContaining("ZZZ.WAR")
                .hasMessageContaining("404");
    }

    @Test
    void otherStatusIsUnavailable() throws Exception {
        status = 500;
        EodhdPriceProvider p = provider();

        assertThatThrownBy(() -> p.history("AAA.XETRA", FROM, TO))
                .isInstanceOf(PriceUnavailableException.class)
                .isNotInstanceOf(RateLimitedException.class)
                .hasMessageContaining("500");
    }

    @Test
    void bodyThatIsNotAListIsUnavailable() throws Exception {
        body = "{\"error\":\"something\"}";
        EodhdPriceProvider p = provider();

        assertThatThrownBy(() -> p.history("AAA.XETRA", FROM, TO))
                .isInstanceOf(PriceUnavailableException.class)
                .isNotInstanceOf(RateLimitedException.class);
    }

    @Test
    void unreachableServerNeverShowsTheKey() {
        EodhdPriceProvider p = new EodhdPriceProvider("http://127.0.0.1:1", KEY, CURRENCIES, budget, CLOCK);

        assertThatThrownBy(() -> p.history("AAA.XETRA", FROM, TO))
                .isInstanceOf(PriceUnavailableException.class)
                .hasMessageContaining("AAA.XETRA")
                .hasMessageNotContaining(KEY);
    }

    @Test
    void exchangeCodeIsMatchedIgnoringCase() throws Exception {
        assertThat(provider().history("bbb.war", FROM, TO).currency()).isEqualTo("PLN");
        assertThat(List.of(lastPath.get())).containsExactly("/api/eod/bbb.war");
    }
}

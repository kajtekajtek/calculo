package dev.calculo.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class YahooPriceProviderTest {

    private HttpServer server;
    private int status = 200;
    private String body = "";
    private final Map<String, String> headers = new java.util.HashMap<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private final AtomicReference<String> lastUserAgent = new AtomicReference<>();

    private YahooPriceProvider provider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastQuery.set(exchange.getRequestURI().getQuery());
            lastUserAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new YahooPriceProvider("http://127.0.0.1:" + server.getAddress().getPort(), CLOCK);
    }

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final LocalDate FROM = LocalDate.of(2026, 10, 26);
    private static final LocalDate TO = LocalDate.of(2026, 10, 28);

    private static String oneClose(String currency, String close) {
        return """
                {"chart":{"result":[{"meta":{"currency":"%s","exchangeTimezoneName":"Europe/London"},
                "timestamp":[1793001600],"indicators":{"quote":[{"close":[%s]}]}}],"error":null}}"""
                .formatted(currency, close);
    }

    private Duration retryAfterOf429() throws Exception {
        status = 429;
        body = "Too Many Requests";
        YahooPriceProvider p = provider();
        try {
            p.history("AAA.DE", FROM, TO);
        } catch (RateLimitedException e) {
            return e.retryAfter();
        }
        throw new AssertionError("expected RateLimitedException");
    }

    @Test
    void http429WithSecondsCarriesRetryAfter() throws Exception {
        headers.put("Retry-After", "120");
        assertThat(retryAfterOf429()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void http429WithAnHttpDateCarriesTheTimeLeft() throws Exception {
        headers.put("Retry-After", "Mon, 02 Nov 2026 10:05:00 GMT");
        assertThat(retryAfterOf429()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void http429WithoutAUsableHeaderCarriesNothing() throws Exception {
        assertThat(retryAfterOf429()).isNull();
    }

    @Test
    void http429WithAnUnreadableOrPastHeaderCarriesNothing() throws Exception {
        headers.put("Retry-After", "soon");
        assertThat(retryAfterOf429()).isNull();
        server.stop(0);
        headers.put("Retry-After", "Mon, 02 Nov 2026 09:00:00 GMT");
        assertThat(retryAfterOf429()).isNull();
    }

    @Test
    void hugeRetryAfterIsCappedSoItCannotOverflowOrBlockForever() throws Exception {
        headers.put("Retry-After", "999999999999999");
        assertThat(retryAfterOf429()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void penceAreNormalisedToPounds() throws Exception {
        body = oneClose("GBp", "1234.5");
        PriceSeries series = provider().history("DDD.L", FROM, TO);

        assertThat(series.currency()).isEqualTo("GBP");
        assertThat(series.prices().get(0).close()).isEqualByComparingTo("12.345");
    }

    @Test
    void uppercaseCodeIsKeptWithItsClose() throws Exception {
        body = oneClose("GBP", "12.5");
        PriceSeries series = provider().history("DDD.L", FROM, TO);

        assertThat(series.currency()).isEqualTo("GBP");
        assertThat(series.prices().get(0).close()).isEqualByComparingTo("12.5");
    }

    @Test
    void anyOtherNonIsoCodeIsAProviderFailure() throws Exception {
        YahooPriceProvider p = provider();
        for (String code : List.of("ZAc", "eur", "EURO", "E1R")) {
            body = oneClose(code, "1.0");
            assertThatThrownBy(() -> p.history("EEE.X", FROM, TO))
                    .isInstanceOf(PriceUnavailableException.class).hasMessageContaining(code);
        }
    }

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/prices", name));
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void parsesSeriesSkipsNullCloseAndTakesCurrencyFromMeta() throws Exception {
        body = fixture("yahoo-aaa.json");
        PriceSeries series = provider().history("AAA.DE", LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 28));

        assertThat(series.currency()).isEqualTo("EUR");
        assertThat(series.prices()).containsExactly(
                new DailyPrice(LocalDate.of(2026, 10, 26), new BigDecimal("10.5")),
                new DailyPrice(LocalDate.of(2026, 10, 28), new BigDecimal("11.25")));
        assertThat(lastPath.get()).isEqualTo("/v8/finance/chart/AAA.DE");
        assertThat(lastQuery.get()).contains("interval=1d").contains("period1=").contains("period2=");
        assertThat(lastUserAgent.get()).contains("Mozilla");
    }

    @Test
    void convertsTimestampsToDaysInTheExchangeTimezone() throws Exception {
        // 2026-10-26T21:00Z is already 2026-10-27 in Auckland.
        body = """
                {"chart":{"result":[{"meta":{"currency":"NZD","exchangeTimezoneName":"Pacific/Auckland"},
                "timestamp":[1793048400],"indicators":{"quote":[{"close":[3.0]}]}}],"error":null}}""";
        PriceSeries series = provider().history("CCC.NZ", LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 28));

        assertThat(series.prices()).extracting(DailyPrice::day).containsExactly(LocalDate.of(2026, 10, 27));
    }

    @Test
    void emptyRangeWithoutTimestampsIsAnEmptySeries() throws Exception {
        body = """
                {"chart":{"result":[{"meta":{"currency":"EUR","exchangeTimezoneName":"Europe/Berlin"},
                "indicators":{"quote":[{}]}}],"error":null}}""";
        PriceSeries series = provider().history("AAA.DE", LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 25));

        assertThat(series.currency()).isEqualTo("EUR");
        assertThat(series.prices()).isEmpty();
    }

    @Test
    void http404IsUnavailable() throws Exception {
        status = 404;
        YahooPriceProvider p = provider();
        assertThatThrownBy(() -> p.history("AAA.DE", LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 28)))
                .isInstanceOf(PriceUnavailableException.class);
    }

    @Test
    void errorInJsonIsUnavailable() throws Exception {
        body = fixture("yahoo-error.json");
        YahooPriceProvider p = provider();
        assertThatThrownBy(() -> p.history("AAA.DE", LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 28)))
                .isInstanceOf(PriceUnavailableException.class).hasMessageContaining("No data found");
    }

    @Test
    void unreachableServerIsUnavailable() {
        YahooPriceProvider p = new YahooPriceProvider("http://127.0.0.1:1", CLOCK);
        assertThatThrownBy(() -> p.history("AAA.DE", LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 28)))
                .isInstanceOf(PriceUnavailableException.class);
    }

    @Test
    void symbolMapping() {
        SymbolMapper mapper = new SymbolMapper(Map.of("PL", "WA"));
        assertThat(mapper.symbol("AAA.PL", null)).isEqualTo("AAA.WA");
        assertThat(mapper.symbol("AAA.DE", null)).isEqualTo("AAA.DE");
        assertThat(mapper.symbol("AAA.PL", "ZZZ.XX")).isEqualTo("ZZZ.XX");
        assertThat(mapper.symbol("AAA.PL", "  ")).isEqualTo("AAA.WA");
        assertThat(mapper.symbol("AAA", null)).isEqualTo("AAA");
        assertThat(List.of(mapper.symbol("bbb.pl", null))).containsExactly("bbb.WA");
    }

    @Test
    void emptyReplacementDropsTheSuffix() {
        SymbolMapper mapper = new SymbolMapper(Map.of("US", ""));
        assertThat(mapper.symbol("XXX.US", null)).isEqualTo("XXX");
        assertThat(mapper.symbol("XXX.DE", null)).isEqualTo("XXX.DE");
    }
}

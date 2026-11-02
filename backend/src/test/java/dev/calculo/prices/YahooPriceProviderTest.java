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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class YahooPriceProviderTest {

    private HttpServer server;
    private int status = 200;
    private String body = "";
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private final AtomicReference<String> lastUserAgent = new AtomicReference<>();

    private YahooPriceProvider provider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastQuery.set(exchange.getRequestURI().getQuery());
            lastUserAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new YahooPriceProvider("http://127.0.0.1:" + server.getAddress().getPort());
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
        YahooPriceProvider p = new YahooPriceProvider("http://127.0.0.1:1");
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
}

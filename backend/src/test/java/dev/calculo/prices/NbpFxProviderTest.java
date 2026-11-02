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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NbpFxProviderTest {

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();

    private NbpFxProvider provider(Function<String, String> bodyForPath) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            String body = bodyForPath.apply(path);
            int status = body == null ? 404 : body.equals("ERROR") ? 500 : 200;
            byte[] bytes = (body == null ? "404 NotFound - Not Found" : body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new NbpFxProvider("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void parsesRates() throws Exception {
        String json = Files.readString(Path.of("src/test/resources/prices/nbp-eur.json"));
        NbpFxProvider p = provider(path -> json);

        Map<LocalDate, BigDecimal> rates = p.rates("EUR", LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 28));

        assertThat(rates).containsOnly(
                Map.entry(LocalDate.of(2026, 10, 26), new BigDecimal("4.25")),
                Map.entry(LocalDate.of(2026, 10, 27), new BigDecimal("4.265")));
        assertThat(paths).containsExactly("/api/exchangerates/rates/a/EUR/2026-10-26/2026-10-28/");
    }

    @Test
    void splitsRangesLongerThan93Days() throws Exception {
        NbpFxProvider p = provider(path -> "{\"rates\":[]}");

        p.rates("EUR", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 19)); // 200 days

        assertThat(paths).containsExactly(
                "/api/exchangerates/rates/a/EUR/2026-01-01/2026-04-03/",
                "/api/exchangerates/rates/a/EUR/2026-04-04/2026-07-05/",
                "/api/exchangerates/rates/a/EUR/2026-07-06/2026-07-19/");
    }

    @Test
    void exactly93DaysIsOneRequest() throws Exception {
        NbpFxProvider p = provider(path -> "{\"rates\":[]}");
        p.rates("EUR", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1).plusDays(92));
        assertThat(paths).hasSize(1);
    }

    @Test
    void notFoundForASubRangeIsNotAFailure() throws Exception {
        String json = "{\"rates\":[{\"effectiveDate\":\"2026-07-10\",\"mid\":4.3}]}";
        NbpFxProvider p = provider(path -> path.contains("2026-04-04") ? null : json);

        Map<LocalDate, BigDecimal> rates = p.rates("EUR", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 19));

        assertThat(paths).hasSize(3);
        assertThat(rates).hasSize(1).containsKey(LocalDate.of(2026, 7, 10));
    }

    @Test
    void serverErrorIsUnavailable() throws Exception {
        NbpFxProvider p = provider(path -> "ERROR");
        assertThatThrownBy(() -> p.rates("EUR", LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 28)))
                .isInstanceOf(PriceUnavailableException.class);
    }

    @Test
    void plnNeverHitsTheNetworkAndIsOne() throws Exception {
        NbpFxProvider p = provider(path -> "ERROR");

        Map<LocalDate, BigDecimal> rates = p.rates("PLN", LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 28));

        assertThat(paths).isEmpty();
        assertThat(rates).hasSize(3).containsValue(BigDecimal.ONE);
    }
}

package dev.calculo.bonds;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ObligacjeSkarboweSeriesSourceTest {

    private static String fixture() throws IOException {
        return Files.readString(Path.of("src/test/resources/bonds/edo-series-page.html"), StandardCharsets.UTF_8);
    }

    @Test
    void parsesRatesFromPage() throws IOException {
        Optional<EdoSeries> series = ObligacjeSkarboweSeriesSource.parse("EDO0136", fixture());
        assertThat(series).isPresent();
        assertThat(series.get().code()).isEqualTo("EDO0136");
        assertThat(series.get().firstYearRate()).isEqualByComparingTo("5.35");
        assertThat(series.get().margin()).isEqualByComparingTo("2.00");
    }

    @Test
    void parsesRatesWithNonBreakingSpacesAndMarkup() {
        String html = "<span>Oprocentowanie:</span> <b>4,10%</b>&nbsp;w pierwszym rocznym okresie odsetkowym, "
                + "w kolejnych rocznych okresach odsetkowych:&nbsp;marża&nbsp;1,50% + inflacja";
        Optional<EdoSeries> series = ObligacjeSkarboweSeriesSource.parse("EDO0236", html);
        assertThat(series).isPresent();
        assertThat(series.get().firstYearRate()).isEqualByComparingTo("4.10");
        assertThat(series.get().margin()).isEqualByComparingTo("1.50");
    }

    @Test
    void otherContentGivesEmpty() {
        assertThat(ObligacjeSkarboweSeriesSource.parse("EDO0136", "<html><body>Strona nie istnieje</body></html>"))
                .isEmpty();
        assertThat(ObligacjeSkarboweSeriesSource.parse("EDO0136", "")).isEmpty();
    }

    @Test
    void fetchesLowercaseCodeUnderBaseUrlAndNetworkFailureGivesEmpty() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String[] requested = new String[1];
        byte[] body = fixture().getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            requested[0] = exchange.getRequestURI().getPath();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/oferta/";
            assertThat(new ObligacjeSkarboweSeriesSource(base).fetch("EDO0136")).isPresent();
            assertThat(requested[0]).isEqualTo("/oferta/edo0136/");
        } finally {
            server.stop(0);
        }
        // server is stopped now: connection refused must not throw
        assertThat(new ObligacjeSkarboweSeriesSource("http://127.0.0.1:1/x/").fetch("EDO0136")).isEmpty();
    }
}

package dev.calculo.bonds;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Reads EDO series terms from the public obligacjeskarbowe.pl series page; any failure yields empty. */
@Component
public class ObligacjeSkarboweSeriesSource implements EdoSeriesSource {

    static final String DEFAULT_BASE_URL = "https://www.obligacjeskarbowe.pl/oferta-obligacji/obligacje-10-letnie-edo/";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern TERMS = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*%\\s*w pierwszym rocznym okresie odsetkowym.*?mar[żz]a\\s*(\\d+(?:[.,]\\d+)?)\\s*%",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    @Autowired
    public ObligacjeSkarboweSeriesSource(
            @Value("${calculo.edo-series-base-url:" + DEFAULT_BASE_URL + "}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    @Override
    public Optional<EdoSeries> fetch(String code) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + code.toLowerCase(Locale.ROOT) + "/"))
                    .timeout(TIMEOUT).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            return parse(code, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    static Optional<EdoSeries> parse(String code, String html) {
        String text = html.replaceAll("<[^>]*>", " ").replace("&nbsp;", " ").replace(' ', ' ')
                .replaceAll("\\s+", " ");
        Matcher m = TERMS.matcher(text);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(new EdoSeries(code, rate(m.group(1)), rate(m.group(2))));
    }

    private static BigDecimal rate(String s) {
        return new BigDecimal(s.replace(',', '.'));
    }
}

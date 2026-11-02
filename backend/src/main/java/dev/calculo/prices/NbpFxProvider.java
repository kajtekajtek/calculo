package dev.calculo.prices;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** PLN exchange rates (table A, mid) from the NBP public API. */
public class NbpFxProvider implements FxProvider {
    /** NBP allows at most 93 days per request. */
    static final int MAX_DAYS = 93;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient client;

    public NbpFxProvider(String baseUrl) {
        this.client = HttpSupport.client(baseUrl);
    }

    @Override
    public Map<LocalDate, BigDecimal> rates(String currency, LocalDate from, LocalDate to)
            throws PriceUnavailableException {
        Map<LocalDate, BigDecimal> rates = new TreeMap<>();
        if ("PLN".equalsIgnoreCase(currency)) {
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) rates.put(d, BigDecimal.ONE);
            return rates;
        }
        for (LocalDate start = from; !start.isAfter(to); start = start.plusDays(MAX_DAYS)) {
            LocalDate end = start.plusDays(MAX_DAYS - 1);
            if (end.isAfter(to)) end = to;
            rates.putAll(fetch(currency.toUpperCase(java.util.Locale.ROOT), start, end));
        }
        return rates;
    }

    private Map<LocalDate, BigDecimal> fetch(String currency, LocalDate from, LocalDate to)
            throws PriceUnavailableException {
        HttpSupport.Response response;
        try {
            response = client.get()
                    .uri("/api/exchangerates/rates/a/{c}/{from}/{to}/?format=json", currency, from, to)
                    .exchange((req, res) -> new HttpSupport.Response(res.getStatusCode().value(),
                            new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (RestClientException e) {
            throw new PriceUnavailableException("NBP is unreachable for " + currency + ": " + e.getMessage(), e);
        }
        Map<LocalDate, BigDecimal> rates = new TreeMap<>();
        if (response.status() == 404) {
            return rates; // no fixing in this sub-range (e.g. a weekend)
        }
        if (response.status() != 200) {
            throw new PriceUnavailableException("NBP answered HTTP " + response.status() + " for " + currency);
        }
        try {
            for (JsonNode rate : JSON.readTree(response.body()).path("rates")) {
                rates.put(LocalDate.parse(rate.path("effectiveDate").asText()), rate.path("mid").decimalValue());
            }
        } catch (IOException | RuntimeException e) {
            throw new PriceUnavailableException("NBP returned an unreadable answer for " + currency, e);
        }
        return rates;
    }
}

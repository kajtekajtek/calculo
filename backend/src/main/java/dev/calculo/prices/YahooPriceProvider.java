package dev.calculo.prices;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Daily closes from Yahoo's unofficial chart endpoint. It can change without notice, so it stays behind
 * {@link PriceProvider}.
 */
public class YahooPriceProvider implements PriceProvider {
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient client;

    public YahooPriceProvider(String baseUrl) {
        this.client = HttpSupport.client(baseUrl);
    }

    @Override
    public PriceSeries history(String symbol, LocalDate from, LocalDate to) throws PriceUnavailableException {
        // Pad the window by a day each side: the exchange day boundary differs from UTC.
        long period1 = from.minusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long period2 = to.plusDays(2).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        HttpSupport.Response response;
        try {
            response = client.get()
                    .uri("/v8/finance/chart/{symbol}?period1={p1}&period2={p2}&interval=1d", symbol, period1, period2)
                    .header("User-Agent", USER_AGENT)
                    .exchange((req, res) -> new HttpSupport.Response(res.getStatusCode().value(),
                            new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
        } catch (RestClientException e) {
            throw new PriceUnavailableException("Yahoo is unreachable for " + symbol + ": " + e.getMessage(), e);
        }
        JsonNode root;
        try {
            root = response.body().isBlank() ? null : JSON.readTree(response.body());
        } catch (IOException e) {
            root = null;
        }
        JsonNode chart = root == null ? null : root.path("chart");
        if (chart != null && !chart.path("error").isMissingNode() && !chart.path("error").isNull()) {
            throw new PriceUnavailableException("Yahoo error for " + symbol + ": "
                    + chart.path("error").path("description").asText(chart.path("error").toString()));
        }
        if (response.status() != 200) {
            throw new PriceUnavailableException("Yahoo answered HTTP " + response.status() + " for " + symbol);
        }
        JsonNode result = chart == null ? null : chart.path("result").path(0);
        if (result == null || result.isMissingNode() || result.isNull()) {
            throw new PriceUnavailableException("Yahoo returned no data for " + symbol);
        }
        return parse(symbol, result, from, to);
    }

    private static PriceSeries parse(String symbol, JsonNode result, LocalDate from, LocalDate to)
            throws PriceUnavailableException {
        JsonNode meta = result.path("meta");
        String currency = meta.path("currency").asText("");
        if (currency.isBlank()) {
            throw new PriceUnavailableException("Yahoo returned no currency for " + symbol);
        }
        ZoneId zone = zone(meta.path("exchangeTimezoneName").asText(null));
        JsonNode timestamps = result.path("timestamp");
        JsonNode closes = result.path("indicators").path("quote").path(0).path("close");
        List<DailyPrice> prices = new ArrayList<>();
        for (int i = 0; i < timestamps.size(); i++) {
            JsonNode close = closes.path(i);
            if (close.isMissingNode() || close.isNull() || !close.isNumber()) continue;
            LocalDate day = Instant.ofEpochSecond(timestamps.get(i).asLong()).atZone(zone).toLocalDate();
            if (day.isBefore(from) || day.isAfter(to)) continue;
            prices.add(new DailyPrice(day, close.decimalValue()));
        }
        return new PriceSeries(currency, prices);
    }

    private static ZoneId zone(String name) {
        try {
            return name == null ? ZoneOffset.UTC : ZoneId.of(name);
        } catch (java.time.DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }
}

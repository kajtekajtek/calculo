package dev.calculo.prices;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Daily closes from the EODHD end-of-day API ({@code SYMBOL.EXCHANGE}, e.g. {@code CDR.WAR}). The answer carries
 * no currency, so it comes from the exchange code. Every request, failed ones included, counts against the
 * daily allowance, which EODHD resets at midnight UTC. Messages never contain the API key.
 */
public class EodhdPriceProvider implements PriceProvider {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient client;
    private final String apiKey;
    private final Map<String, String> currencies = new HashMap<>();
    private final CallBudget budget;
    private final Clock clock;

    public EodhdPriceProvider(String baseUrl, String apiKey, Map<String, String> exchangeCurrencies, CallBudget budget,
                              Clock clock) {
        this.client = HttpSupport.client(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        exchangeCurrencies.forEach((exchange, currency) -> currencies.put(exchange.toUpperCase(Locale.ROOT), currency));
        this.budget = budget;
        this.clock = clock;
    }

    @Override
    public PriceSeries history(String symbol, LocalDate from, LocalDate to) throws PriceUnavailableException {
        if (apiKey.isEmpty()) {
            throw new ProviderNotConfiguredException("EODHD_API_KEY is not set");
        }
        String currency = currencyOf(symbol);
        if (!budget.tryUse()) {
            throw new RateLimitedException("Daily limit of EODHD requests is spent", untilMidnightUtc());
        }
        HttpSupport.Response response;
        try {
            response = client.get()
                    .uri("/api/eod/{symbol}?api_token={key}&from={from}&to={to}&period=d&fmt=json",
                            symbol, apiKey, from, to)
                    .exchange((req, res) -> new HttpSupport.Response(res.getStatusCode().value(),
                            new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (RestClientException e) {
            // The exception text holds the request URL, and with it the key, so only its type is kept.
            throw new PriceUnavailableException("EODHD is unreachable for " + symbol + " ("
                    + e.getMostSpecificCause().getClass().getSimpleName() + ")");
        }
        switch (response.status()) {
            case 200 -> { }
            case 402, 429 -> throw new RateLimitedException(
                    "EODHD answered HTTP " + response.status() + " for " + symbol, untilMidnightUtc());
            case 401, 403 -> throw new ProviderNotConfiguredException(
                    "EODHD rejected the request (HTTP " + response.status() + "): check EODHD_API_KEY and the plan");
            case 404 -> throw new PriceUnavailableException("EODHD does not know " + symbol + " (HTTP 404)");
            default -> throw new PriceUnavailableException("EODHD answered HTTP " + response.status() + " for " + symbol);
        }
        return new PriceSeries(currency, parse(symbol, response.body(), from, to));
    }

    private String currencyOf(String symbol) throws PriceUnavailableException {
        int dot = symbol.lastIndexOf('.');
        String exchange = dot < 0 ? "" : symbol.substring(dot + 1).toUpperCase(Locale.ROOT);
        String currency = currencies.get(exchange);
        if (currency == null) {
            throw new PriceUnavailableException("No currency configured for exchange \"" + exchange + "\" of " + symbol
                    + " (CALCULO_EXCHANGE_CURRENCIES)");
        }
        return currency;
    }

    private static List<DailyPrice> parse(String symbol, String body, LocalDate from, LocalDate to)
            throws PriceUnavailableException {
        JsonNode rows;
        try {
            rows = JSON.readTree(body);
        } catch (IOException e) {
            rows = null;
        }
        if (rows == null || !rows.isArray()) {
            throw new PriceUnavailableException("EODHD returned an unreadable answer for " + symbol);
        }
        List<DailyPrice> prices = new ArrayList<>();
        for (JsonNode row : rows) {
            JsonNode close = row.path("close");
            if (!close.isNumber()) continue;
            LocalDate day;
            try {
                day = LocalDate.parse(row.path("date").asText(""));
            } catch (DateTimeParseException e) {
                continue;
            }
            if (day.isBefore(from) || day.isAfter(to)) continue;
            prices.add(new DailyPrice(day, new BigDecimal(close.asText())));
        }
        return prices;
    }

    private Duration untilMidnightUtc() {
        Instant now = clock.instant();
        Instant midnight = LocalDate.ofInstant(now, ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return Duration.between(now, midnight);
    }
}

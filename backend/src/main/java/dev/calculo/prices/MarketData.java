package dev.calculo.prices;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;

/** Immutable snapshot of stored closes and PLN exchange rates. */
public class MarketData {

    /** PLN value per unit and the day of the close it is based on. */
    public record PricedAt(BigDecimal pln, LocalDate priceDate) {}

    private final Map<String, ? extends NavigableMap<LocalDate, BigDecimal>> closes;
    private final Map<String, String> currencies;
    private final Map<String, ? extends NavigableMap<LocalDate, BigDecimal>> fx;

    public MarketData(Map<String, ? extends NavigableMap<LocalDate, BigDecimal>> closes,
                      Map<String, String> currencies,
                      Map<String, ? extends NavigableMap<LocalDate, BigDecimal>> fx) {
        this.closes = closes;
        this.currencies = currencies;
        this.fx = fx;
    }

    /** Latest close and exchange rate not later than {@code day}; empty when either is missing. */
    public Optional<PricedAt> pricePln(String ticker, LocalDate day) {
        NavigableMap<LocalDate, BigDecimal> series = closes.get(ticker);
        if (series == null) return Optional.empty();
        Map.Entry<LocalDate, BigDecimal> close = series.floorEntry(day);
        if (close == null) return Optional.empty();
        String currency = currencies.getOrDefault(ticker, "PLN");
        if ("PLN".equals(currency)) return Optional.of(new PricedAt(close.getValue(), close.getKey()));
        NavigableMap<LocalDate, BigDecimal> rates = fx.get(currency);
        if (rates == null) return Optional.empty();
        Map.Entry<LocalDate, BigDecimal> rate = rates.floorEntry(day);
        if (rate == null) return Optional.empty();
        return Optional.of(new PricedAt(close.getValue().multiply(rate.getValue()), close.getKey()));
    }
}

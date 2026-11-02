package dev.calculo.prices;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public interface FxProvider {
    /** PLN per one unit of {@code currency}, by day, between the two days inclusive; only days with a rate. */
    Map<LocalDate, BigDecimal> rates(String currency, LocalDate from, LocalDate to) throws PriceUnavailableException;
}

package dev.calculo.prices;

import java.time.LocalDate;

public interface PriceProvider {
    /** Daily closes for {@code symbol} between the two days, inclusive. */
    PriceSeries history(String symbol, LocalDate from, LocalDate to) throws PriceUnavailableException;
}

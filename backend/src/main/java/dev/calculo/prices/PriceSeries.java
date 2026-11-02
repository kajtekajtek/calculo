package dev.calculo.prices;

import java.util.List;

/** Daily closes of one instrument, all in {@code currency}. */
public record PriceSeries(String currency, List<DailyPrice> prices) {}

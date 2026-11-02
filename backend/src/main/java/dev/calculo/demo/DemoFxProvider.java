package dev.calculo.demo;

import dev.calculo.prices.FxProvider;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

class DemoFxProvider implements FxProvider {
    @Override
    public Map<LocalDate, BigDecimal> rates(String currency, LocalDate from, LocalDate to) {
        Map<LocalDate, BigDecimal> rates = new TreeMap<>();
        if (!"EUR".equalsIgnoreCase(currency)) return rates;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (DemoMarket.isTradingDay(day)) rates.put(day, DemoMarket.eurRate(day));
        }
        return rates;
    }
}

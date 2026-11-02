package dev.calculo.demo;

import dev.calculo.prices.DailyPrice;
import dev.calculo.prices.PriceProvider;
import dev.calculo.prices.PriceSeries;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

class DemoPriceProvider implements PriceProvider {
    @Override
    public PriceSeries history(String symbol, LocalDate from, LocalDate to) {
        List<DailyPrice> prices = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (DemoMarket.isTradingDay(day)) prices.add(new DailyPrice(day, DemoMarket.close(symbol, day)));
        }
        return new PriceSeries(DemoMarket.currency(symbol), prices);
    }
}

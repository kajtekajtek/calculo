package dev.calculo.prices;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MarketDataRepository {
    private final JdbcClient jdbc;

    public MarketDataRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public MarketData load() {
        Map<String, TreeMap<LocalDate, BigDecimal>> closes = new HashMap<>();
        Map<String, String> currencies = new HashMap<>();
        // Ordered by day so the currency of the latest row wins.
        jdbc.sql("select ticker, day, close, currency from price_history order by day").query(rs -> {
            String ticker = rs.getString("ticker");
            closes.computeIfAbsent(ticker, t -> new TreeMap<>())
                    .put(rs.getDate("day").toLocalDate(), rs.getBigDecimal("close"));
            currencies.put(ticker, rs.getString("currency"));
        });
        Map<String, TreeMap<LocalDate, BigDecimal>> fx = new HashMap<>();
        jdbc.sql("select currency, day, mid from fx_rate").query(rs -> {
            fx.computeIfAbsent(rs.getString("currency"), c -> new TreeMap<>())
                    .put(rs.getDate("day").toLocalDate(), rs.getBigDecimal("mid"));
        });
        return new MarketData(closes, currencies, fx);
    }

    public Optional<LocalDate> latestPriceDay(String ticker) {
        return jdbc.sql("select max(day) from price_history where ticker = :t").param("t", ticker)
                .query((rs, i) -> Optional.ofNullable(rs.getDate(1)).map(Date::toLocalDate)).single();
    }

    public Optional<LocalDate> earliestPriceDay(String ticker) {
        return jdbc.sql("select min(day) from price_history where ticker = :t").param("t", ticker)
                .query((rs, i) -> Optional.ofNullable(rs.getDate(1)).map(Date::toLocalDate)).single();
    }

    public Optional<LocalDate> earliestFxDay(String currency) {
        return jdbc.sql("select min(day) from fx_rate where currency = :c").param("c", currency)
                .query((rs, i) -> Optional.ofNullable(rs.getDate(1)).map(Date::toLocalDate)).single();
    }

    public Optional<LocalDate> latestFxDay(String currency) {
        return jdbc.sql("select max(day) from fx_rate where currency = :c").param("c", currency)
                .query((rs, i) -> Optional.ofNullable(rs.getDate(1)).map(Date::toLocalDate)).single();
    }

    @Transactional
    public void savePrices(String ticker, String currency, List<DailyPrice> prices) {
        for (DailyPrice p : prices) {
            jdbc.sql("""
                    insert into price_history (ticker, day, close, currency) values (:t, :d, :c, :cur)
                    on conflict (ticker, day) do update set close = excluded.close, currency = excluded.currency""")
                    .param("t", ticker).param("d", Date.valueOf(p.day())).param("c", p.close())
                    .param("cur", currency).update();
        }
        jdbc.sql("update instrument set currency = :cur where ticker = :t").param("cur", currency)
                .param("t", ticker).update();
    }

    @Transactional
    public void saveFx(String currency, Map<LocalDate, BigDecimal> rates) {
        rates.forEach((day, mid) -> jdbc.sql("""
                insert into fx_rate (currency, day, mid) values (:c, :d, :m)
                on conflict (currency, day) do update set mid = excluded.mid""")
                .param("c", currency).param("d", Date.valueOf(day)).param("m", mid).update());
    }
}

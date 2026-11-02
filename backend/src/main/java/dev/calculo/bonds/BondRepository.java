package dev.calculo.bonds;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BondRepository {
    private final JdbcClient jdbc;

    public BondRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<BondPurchase> allPurchases() {
        return jdbc.sql("select id, series_code, purchase_date, quantity from bond_purchase order by purchase_date, id")
                .query(BondRepository::purchase).list();
    }

    public Optional<BondPurchase> findPurchase(long id) {
        return jdbc.sql("select id, series_code, purchase_date, quantity from bond_purchase where id = :id")
                .param("id", id).query(BondRepository::purchase).optional();
    }

    public BondPurchase insertPurchase(String accountId, String seriesCode, LocalDate date, int quantity) {
        long id = jdbc.sql("insert into bond_purchase (account_id, series_code, purchase_date, quantity) "
                        + "values (:a, :s, :d, :q) returning id")
                .param("a", accountId).param("s", seriesCode).param("d", Date.valueOf(date)).param("q", quantity)
                .query(Long.class).single();
        return new BondPurchase(id, seriesCode, date, quantity);
    }

    /** Returns true when the purchase exists. */
    public boolean updatePurchase(long id, String seriesCode, LocalDate date, int quantity) {
        return jdbc.sql("update bond_purchase set series_code = :s, purchase_date = :d, quantity = :q where id = :id")
                .param("s", seriesCode).param("d", Date.valueOf(date)).param("q", quantity).param("id", id)
                .update() == 1;
    }

    public boolean deletePurchase(long id) {
        return jdbc.sql("delete from bond_purchase where id = :id").param("id", id).update() == 1;
    }

    public Map<String, EdoSeries> allSeries() {
        Map<String, EdoSeries> result = new LinkedHashMap<>();
        jdbc.sql("select code, first_year_rate, margin from edo_series order by code")
                .query((rs, i) -> new EdoSeries(rs.getString("code"), rs.getBigDecimal("first_year_rate"),
                        rs.getBigDecimal("margin")))
                .list().forEach(s -> result.put(s.code(), s));
        return result;
    }

    public boolean seriesExists(String code) {
        return jdbc.sql("select count(*) from edo_series where code = :c").param("c", code)
                .query(Integer.class).single() > 0;
    }

    public void upsertSeries(EdoSeries series) {
        jdbc.sql("""
                insert into edo_series (code, first_year_rate, margin) values (:c, :f, :m)
                on conflict (code) do update set first_year_rate = excluded.first_year_rate, margin = excluded.margin""")
                .param("c", series.code()).param("f", series.firstYearRate()).param("m", series.margin()).update();
    }

    public Map<YearMonth, BigDecimal> cpi() {
        Map<YearMonth, BigDecimal> result = new TreeMap<>();
        jdbc.sql("select month, pct from cpi_announcement order by month")
                .query((rs, i) -> Map.entry(YearMonth.from(rs.getDate("month").toLocalDate()), rs.getBigDecimal("pct")))
                .list().forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    public void upsertCpi(YearMonth month, BigDecimal pct) {
        jdbc.sql("""
                insert into cpi_announcement (month, pct) values (:m, :p)
                on conflict (month) do update set pct = excluded.pct""")
                .param("m", Date.valueOf(month.atDay(1))).param("p", pct).update();
    }

    public boolean deleteCpi(YearMonth month) {
        return jdbc.sql("delete from cpi_announcement where month = :m")
                .param("m", Date.valueOf(month.atDay(1))).update() == 1;
    }

    private static BondPurchase purchase(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new BondPurchase(rs.getLong("id"), rs.getString("series_code"),
                rs.getDate("purchase_date").toLocalDate(), rs.getInt("quantity"));
    }
}

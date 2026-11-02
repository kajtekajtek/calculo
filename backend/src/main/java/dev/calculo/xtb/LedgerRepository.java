package dev.calculo.xtb;

import dev.calculo.xtb.ParsedExport.CashOperation;
import dev.calculo.xtb.ParsedExport.OpenPosition;
import dev.calculo.xtb.ParsedExport.OperationType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerRepository {

    public record OperationRow(String accountId, String externalId, OperationType type, String ticker,
                               Instant occurredAt, BigDecimal amountPln, String positionId, BigDecimal volume,
                               BigDecimal price) {}

    public record SnapshotRow(String accountId, String ticker, BigDecimal volume, BigDecimal valuePln,
                              Instant reportedAt) {}

    public record InstrumentRow(String ticker, String name, String category, String priceSymbol, String currency) {}

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<OperationRow> allOperations() {
        return jdbc.sql("""
                select account_id, external_id, type, ticker, occurred_at, amount_pln, position_id, volume, price
                from operation order by occurred_at, account_id, external_id""")
                .query((rs, i) -> new OperationRow(rs.getString("account_id"), rs.getString("external_id"),
                        OperationType.valueOf(rs.getString("type")), rs.getString("ticker"),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getBigDecimal("amount_pln"),
                        rs.getString("position_id"), rs.getBigDecimal("volume"), rs.getBigDecimal("price")))
                .list();
    }

    public List<SnapshotRow> snapshots() {
        return jdbc.sql("select account_id, ticker, volume, value_pln, reported_at from position_snapshot "
                        + "order by account_id, ticker")
                .query((rs, i) -> new SnapshotRow(rs.getString("account_id"), rs.getString("ticker"),
                        rs.getBigDecimal("volume"), rs.getBigDecimal("value_pln"),
                        rs.getTimestamp("reported_at").toInstant()))
                .list();
    }

    public List<InstrumentRow> instruments() {
        return jdbc.sql("select ticker, name, category, price_symbol, currency from instrument order by ticker")
                .query((rs, i) -> new InstrumentRow(rs.getString("ticker"), rs.getString("name"),
                        rs.getString("category"), rs.getString("price_symbol"), rs.getString("currency")))
                .list();
    }

    public Set<String> externalIds(String accountId) {
        return Set.copyOf(jdbc.sql("select external_id from operation where account_id = :a")
                .param("a", accountId).query(String.class).list());
    }

    /** Summed purchase volume per ticker already stored for the account. */
    public List<TickerVolume> storedPurchaseVolumes(String accountId) {
        return jdbc.sql("select ticker, sum(volume) as volume from operation "
                        + "where account_id = :a and type = 'PURCHASE' and ticker is not null group by ticker")
                .param("a", accountId)
                .query((rs, i) -> new TickerVolume(rs.getString("ticker"), rs.getBigDecimal("volume")))
                .list();
    }

    public record TickerVolume(String ticker, BigDecimal volume) {}

    /** Inserts the instrument if unknown; an existing one (and its price_symbol) is left untouched. */
    public void insertInstrumentIfMissing(String ticker, String name, String category) {
        jdbc.sql("insert into instrument (ticker, name, category) values (:t, :n, :c) on conflict (ticker) do nothing")
                .param("t", ticker).param("n", name).param("c", category).update();
    }

    /** Returns true when a new row was inserted, false for a duplicate. */
    public boolean insertOperation(String accountId, CashOperation op) {
        return jdbc.sql("""
                insert into operation (account_id, external_id, type, ticker, occurred_at, amount_pln, position_id,
                                       volume, price, comment)
                values (:a, :e, :type, :t, :at, :amount, :pos, :vol, :price, :comment)
                on conflict (account_id, external_id) do nothing""")
                .param("a", accountId).param("e", op.externalId()).param("type", op.type().name())
                .param("t", blankToNull(op.ticker())).param("at", java.sql.Timestamp.from(op.occurredAt()))
                .param("amount", op.amountPln()).param("pos", op.positionId())
                .param("vol", op.volume()).param("price", op.price()).param("comment", op.comment())
                .update() == 1;
    }

    /** Replaces the account's snapshot unless a newer one is already stored. */
    public void replaceSnapshot(String accountId, List<OpenPosition> positions, Instant reportedAt) {
        if (reportedAt == null) return;
        Integer newer = jdbc.sql("select count(*) from position_snapshot where account_id = :a and reported_at > :r")
                .param("a", accountId).param("r", java.sql.Timestamp.from(reportedAt)).query(Integer.class).single();
        if (newer > 0) return;
        jdbc.sql("delete from position_snapshot where account_id = :a").param("a", accountId).update();
        for (OpenPosition p : positions) {
            jdbc.sql("insert into position_snapshot (account_id, ticker, volume, value_pln, reported_at) "
                            + "values (:a, :t, :v, :val, :r)")
                    .param("a", accountId).param("t", p.ticker()).param("v", p.volume())
                    .param("val", p.valuePln()).param("r", java.sql.Timestamp.from(reportedAt)).update();
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}

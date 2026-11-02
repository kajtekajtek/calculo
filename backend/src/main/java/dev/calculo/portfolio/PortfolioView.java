package dev.calculo.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The dashboard: header figures, positions, history and warnings, serialized as JSON. */
public record PortfolioView(Summary summary, List<PositionRow> positions, List<SeriesPoint> series,
                            List<String> warnings, boolean empty) {

    /** {@code pricesAsOf} is the oldest price date among instrument positions, null when there are none. */
    public record Summary(BigDecimal value, BigDecimal contributions, BigDecimal gain, BigDecimal gainPct,
                          Double returnValue, boolean returnAnnualized, LocalDate pricesAsOf) {}

    /**
     * {@code kind} is INSTRUMENT, BONDS or CASH; {@code key} is the ticker, "edo" or "cash".
     * {@code firstDay} is the Warsaw day of the position's first activity in the current view (first purchase,
     * or first operation for cash); the UI uses it for a colour order that new purchases never disturb.
     */
    public record PositionRow(String key, String name, String kind, BigDecimal value, BigDecimal sharePct,
                              BigDecimal gainPct, LocalDate priceDate, LocalDate firstDay) {}

    public record SeriesPoint(LocalDate day, BigDecimal value, BigDecimal contributions, Double cumulativeReturn) {}

    public record PurchaseRow(LocalDate day, String accountName, String label, BigDecimal volume, BigDecimal price,
                              BigDecimal amountPln, BigDecimal valueNow) {}

    /** {@code series} holds the position's value and cost per day (in value and contributions). */
    public record PositionDetail(PositionRow row, List<PurchaseRow> purchases, List<SeriesPoint> series) {}
}

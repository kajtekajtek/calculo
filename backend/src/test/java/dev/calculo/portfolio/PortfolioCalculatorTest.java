package dev.calculo.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.calculo.NotFoundException;
import dev.calculo.account.Account;
import dev.calculo.bonds.BondPurchase;
import dev.calculo.bonds.EdoSeries;
import dev.calculo.portfolio.PortfolioView.PositionDetail;
import dev.calculo.portfolio.PortfolioView.PositionRow;
import dev.calculo.portfolio.PortfolioView.SeriesPoint;
import dev.calculo.prices.MarketData;
import dev.calculo.xtb.LedgerRepository.InstrumentRow;
import dev.calculo.xtb.LedgerRepository.OperationRow;
import dev.calculo.xtb.LedgerRepository.SnapshotRow;
import dev.calculo.xtb.ParsedExport.OperationType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class PortfolioCalculatorTest {
    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final LocalDate TODAY = LocalDate.parse("2026-11-02"); // Monday

    private static final Account IKE = new Account("xtb:IKE", "IKE XTB", "XTB");
    private static final Account TRADES = new Account("xtb:Trades", "Zwykłe XTB", "XTB");
    private static final Account BONDS = new Account("bonds", "Obligacje", "bonds");

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private static Instant at(String day) { return LocalDate.parse(day).atTime(12, 0).atZone(WARSAW).toInstant(); }

    private static int seq = 0;

    private static OperationRow op(String account, OperationType type, String ticker, String day, String amount,
                                   String positionId, String volume, String price) {
        return new OperationRow(account, "e" + (seq++), type, ticker, at(day), bd(amount), positionId,
                volume == null ? null : bd(volume), price == null ? null : bd(price));
    }

    private static OperationRow deposit(String account, String day, String amount) {
        return op(account, OperationType.DEPOSIT, null, day, amount, null, null, null);
    }

    private static OperationRow buy(String account, String ticker, String day, String cost, String pos,
                                    String volume) {
        return op(account, OperationType.PURCHASE, ticker, day, "-" + cost, pos, volume, "0");
    }

    private static OperationRow dividend(String account, String ticker, String day, String amount) {
        return op(account, OperationType.DIVIDEND, ticker, day, amount, null, null, null);
    }

    private static MarketData market(Map<String, Map<String, String>> closes) {
        Map<String, TreeMap<LocalDate, BigDecimal>> m = new HashMap<>();
        closes.forEach((t, days) -> {
            TreeMap<LocalDate, BigDecimal> s = new TreeMap<>();
            days.forEach((d, p) -> s.put(LocalDate.parse(d), bd(p)));
            m.put(t, s);
        });
        return new MarketData(m, Map.of(), Map.of());
    }

    private static final MarketData PRICES = market(Map.of(
            "AAA.DE", Map.of("2026-10-29", "90", "2026-10-30", "100"),
            "BBB.PL", Map.of("2026-10-30", "50")));

    private static final List<InstrumentRow> INSTRUMENTS = List.of(
            new InstrumentRow("AAA.DE", "Alpha ETF", "ETF", null, "PLN"),
            new InstrumentRow("BBB.PL", "Beta SA", "STC", null, "PLN"));

    private static List<OperationRow> basicOps() {
        return List.of(
                deposit("xtb:IKE", "2026-03-02", "1000"),
                buy("xtb:IKE", "AAA.DE", "2026-03-03", "900", "p1", "10"),
                dividend("xtb:IKE", "AAA.DE", "2026-04-01", "10"));
    }

    private static PortfolioInputs inputs(List<Account> accounts, List<OperationRow> ops) {
        return new PortfolioInputs(accounts, ops, List.of(), Map.of(), Map.of(), PRICES, List.of(), INSTRUMENTS,
                Map.of(), TODAY);
    }

    private static PortfolioInputs basic() { return inputs(List.of(IKE), basicOps()); }

    private static PortfolioInputs withBonds(PortfolioInputs in, List<BondPurchase> bonds,
                                             Map<String, EdoSeries> series, Map<YearMonth, BigDecimal> cpi) {
        List<Account> accounts = new ArrayList<>(in.accounts());
        accounts.add(BONDS);
        return new PortfolioInputs(accounts, in.operations(), bonds, series, cpi, in.market(), in.snapshots(),
                in.instruments(), in.syncStatus(), in.today());
    }

    private static PositionRow row(PortfolioView v, String key) {
        return v.positions().stream().filter(p -> p.key().equals(key)).findFirst().orElseThrow();
    }

    private static void assertMoney(BigDecimal actual, String expected) {
        assertThat(actual).isNotNull().isEqualByComparingTo(expected);
    }

    @Test
    void basicPortfolioSummaryAndPositions() {
        PortfolioView v = PortfolioCalculator.view(basic(), "all");
        assertThat(v.empty()).isFalse();
        assertMoney(v.summary().value(), "1110");
        assertMoney(v.summary().contributions(), "1000");
        assertMoney(v.summary().gain(), "110");
        assertMoney(v.summary().gainPct(), "11.00");
        assertThat(v.positions()).extracting(PositionRow::key).containsExactly("AAA.DE", "cash");

        PositionRow aaa = row(v, "AAA.DE");
        assertThat(aaa.kind()).isEqualTo("INSTRUMENT");
        assertThat(aaa.name()).isEqualTo("Alpha ETF");
        assertMoney(aaa.value(), "1000");
        assertMoney(aaa.gainPct(), "11.11");
        assertThat(aaa.priceDate()).isEqualTo(LocalDate.parse("2026-10-30"));

        PositionRow cash = row(v, "cash");
        assertThat(cash.kind()).isEqualTo("CASH");
        assertThat(cash.name()).isEqualTo("Gotówka");
        assertMoney(cash.value(), "110");
        assertThat(cash.gainPct()).isNull();
        assertThat(cash.priceDate()).isNull();
        assertThat(v.summary().pricesAsOf()).isEqualTo(LocalDate.parse("2026-10-30"));
    }

    @Test
    void sharesSumToHundredAndPositionsAreSortedDescending() {
        List<OperationRow> ops = new ArrayList<>(basicOps());
        ops.add(buy("xtb:IKE", "BBB.PL", "2026-03-05", "30", "p2", "1"));
        ops.add(deposit("xtb:IKE", "2026-03-06", "7"));
        PortfolioView v = PortfolioCalculator.view(inputs(List.of(IKE), ops), "all");
        BigDecimal sum = v.positions().stream().map(PositionRow::sharePct).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum.doubleValue()).isBetween(99.95, 100.05);
        List<BigDecimal> values = v.positions().stream().map(PositionRow::value).toList();
        for (int i = 1; i < values.size(); i++) {
            assertThat(values.get(i - 1)).isGreaterThanOrEqualTo(values.get(i));
        }
    }

    @Test
    void zeroCashIsNotShown() {
        List<OperationRow> ops = List.of(deposit("xtb:IKE", "2026-03-02", "900"),
                buy("xtb:IKE", "AAA.DE", "2026-03-03", "900", "p1", "10"));
        PortfolioView v = PortfolioCalculator.view(inputs(List.of(IKE), ops), "all");
        assertThat(v.positions()).extracting(PositionRow::key).containsExactly("AAA.DE");
    }

    @Test
    void edoAddsContributionsAndPositionButNotXtbCash() {
        Map<String, EdoSeries> series = Map.of("EDO1036", new EdoSeries("EDO1036", bd("5.60"), bd("2.00")));
        PortfolioInputs in = withBonds(basic(), List.of(new BondPurchase(1, "EDO1036", LocalDate.parse("2026-10-02"), 10)),
                series, Map.of());
        PortfolioView v = PortfolioCalculator.view(in, "all");
        // 100 * 5.60% * 31/365 = 0.4756 -> 0.48 per bond, so 100.48 * 10
        PositionRow edo = row(v, "edo");
        assertThat(edo.kind()).isEqualTo("BONDS");
        assertMoney(edo.value(), "1004.80");
        assertMoney(edo.gainPct(), "0.48");
        assertThat(edo.priceDate()).isNull();
        assertMoney(row(v, "cash").value(), "110");
        assertMoney(v.summary().contributions(), "2000");
        assertMoney(v.summary().value(), "2114.80");
        assertMoney(v.summary().gain(), "114.80");
        assertThat(v.summary().pricesAsOf()).isEqualTo(LocalDate.parse("2026-10-30"));
    }

    @Test
    void filtersSelectAccountOrBondsOnly() {
        List<OperationRow> ops = new ArrayList<>(basicOps());
        ops.add(deposit("xtb:Trades", "2026-05-01", "500"));
        ops.add(buy("xtb:Trades", "BBB.PL", "2026-05-02", "400", "t1", "8"));
        Map<String, EdoSeries> series = Map.of("EDO1036", new EdoSeries("EDO1036", bd("5.60"), bd("2.00")));
        PortfolioInputs in = withBonds(inputs(List.of(IKE, TRADES), ops),
                List.of(new BondPurchase(1, "EDO1036", LocalDate.parse("2026-10-02"), 10)), series, Map.of());

        PortfolioView ike = PortfolioCalculator.view(in, "xtb:IKE");
        assertThat(ike.positions()).extracting(PositionRow::key).containsExactlyInAnyOrder("AAA.DE", "cash");
        assertMoney(ike.summary().contributions(), "1000");
        assertMoney(ike.summary().value(), "1110");

        PortfolioView trades = PortfolioCalculator.view(in, "xtb:Trades");
        assertThat(trades.positions()).extracting(PositionRow::key).containsExactlyInAnyOrder("BBB.PL", "cash");
        assertMoney(trades.summary().value(), "500"); // 8*50 + 100
        assertMoney(trades.summary().contributions(), "500");

        PortfolioView bonds = PortfolioCalculator.view(in, "bonds");
        assertThat(bonds.positions()).extracting(PositionRow::key).containsExactly("edo");
        assertMoney(bonds.summary().contributions(), "1000");
        assertMoney(bonds.summary().value(), "1004.80");

        PortfolioView all = PortfolioCalculator.view(in, "all");
        assertMoney(all.summary().contributions(), "2500");
    }

    @Test
    void allMergesSameTickerAcrossAccounts() {
        List<OperationRow> ops = new ArrayList<>(basicOps());
        ops.add(deposit("xtb:Trades", "2026-05-01", "500"));
        ops.add(buy("xtb:Trades", "AAA.DE", "2026-05-02", "450", "t1", "5"));
        PortfolioView v = PortfolioCalculator.view(inputs(List.of(IKE, TRADES), ops), "all");
        assertThat(v.positions().stream().filter(p -> p.key().equals("AAA.DE"))).hasSize(1);
        assertMoney(row(v, "AAA.DE").value(), "1500");
        assertMoney(row(v, "AAA.DE").gainPct(), "11.11"); // (1500-1350)/1350
        assertMoney(row(v, "cash").value(), "160"); // 110 + 50
    }

    @Test
    void unknownAccountIsNotFound() {
        assertThatThrownBy(() -> PortfolioCalculator.view(basic(), "xtb:nope")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> PortfolioCalculator.detail(basic(), "xtb:nope", "AAA.DE"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void seriesHasOnePointPerDayFromFirstDepositAndWeekendRepeatsLastPrice() {
        PortfolioView v = PortfolioCalculator.view(basic(), "all");
        List<SeriesPoint> s = v.series();
        assertThat(s.get(0).day()).isEqualTo(LocalDate.parse("2026-03-02"));
        assertThat(s.get(s.size() - 1).day()).isEqualTo(TODAY);
        assertThat(s).hasSize((int) java.time.temporal.ChronoUnit.DAYS.between(s.get(0).day(), TODAY) + 1);
        for (int i = 1; i < s.size(); i++) {
            assertThat(s.get(i).day()).isEqualTo(s.get(i - 1).day().plusDays(1));
        }
        SeriesPoint thu = point(s, "2026-10-29");
        SeriesPoint fri = point(s, "2026-10-30");
        SeriesPoint sat = point(s, "2026-10-31");
        SeriesPoint sun = point(s, "2026-11-01");
        assertMoney(thu.value(), "1010"); // 10*90 + 110
        assertMoney(fri.value(), "1110");
        assertMoney(sat.value(), "1110");
        assertMoney(sun.value(), "1110");
        assertMoney(s.get(s.size() - 1).value(), v.summary().value().toPlainString());
        assertMoney(s.get(s.size() - 1).contributions(), v.summary().contributions().toPlainString());
        // Day of the deposit: cash only, no purchase yet; before the dividend cash excludes it.
        assertMoney(s.get(0).value(), "1000");
        assertMoney(point(s, "2026-03-03").value(), "1000"); // priced? no close yet -> at cost 900 + 100 cash
        assertMoney(point(s, "2026-03-31").contributions(), "1000");
    }

    private static SeriesPoint point(List<SeriesPoint> s, String day) {
        return s.stream().filter(p -> p.day().equals(LocalDate.parse(day))).findFirst().orElseThrow();
    }

    @Test
    void seriesBeforeFirstPriceValuesAtCostWithoutWarning() {
        PortfolioView v = PortfolioCalculator.view(basic(), "all");
        // 2026-04-01: dividend lands; no close before 10-29 so position at cost 900, cash 110
        assertMoney(point(v.series(), "2026-04-01").value(), "1010");
        assertThat(v.warnings()).noneMatch(w -> w.contains("Brak ceny"));
    }

    @Test
    void dayIsCalculatedInWarsawTime() {
        // 23:30 UTC on 2026-03-02 is already 2026-03-03 in Warsaw.
        OperationRow late = new OperationRow("xtb:IKE", "late", OperationType.DEPOSIT, null,
                Instant.parse("2026-03-02T23:30:00Z"), bd("100"), null, null, null);
        PortfolioView v = PortfolioCalculator.view(inputs(List.of(IKE), List.of(late)), "all");
        assertThat(v.series().get(0).day()).isEqualTo(LocalDate.parse("2026-03-03"));
    }

    @Test
    void missingPriceValuesAtCostWithWarningNamingTicker() {
        List<OperationRow> ops = new ArrayList<>(basicOps());
        ops.add(buy("xtb:IKE", "CCC.DE", "2026-03-04", "50", "p9", "5"));
        ops.add(deposit("xtb:IKE", "2026-03-04", "50"));
        PortfolioView v = PortfolioCalculator.view(inputs(List.of(IKE), ops), "all");
        PositionRow ccc = row(v, "CCC.DE");
        assertMoney(ccc.value(), "50");
        assertThat(ccc.priceDate()).isNull();
        assertMoney(ccc.gainPct(), "0");
        assertThat(v.warnings().stream().filter(w -> w.contains("CCC.DE"))).hasSize(1);
        // The date shown never overstates freshness: CCC has none, AAA is the only dated price.
        assertThat(v.summary().pricesAsOf()).isEqualTo(LocalDate.parse("2026-10-30"));
    }

    @Test
    void stalePriceWarnsOnceWithDate() {
        MarketData old = market(Map.of("AAA.DE", Map.of("2026-10-20", "100")));
        PortfolioInputs in = new PortfolioInputs(List.of(IKE), basicOps(), List.of(), Map.of(), Map.of(), old,
                List.of(), INSTRUMENTS, Map.of(), TODAY);
        PortfolioView v = PortfolioCalculator.view(in, "all");
        assertThat(v.warnings().stream().filter(w -> w.contains("AAA.DE"))).hasSize(1)
                .allMatch(w -> w.contains("2026-10-20"));
        // exactly 7 days old is not stale
        MarketData edge = market(Map.of("AAA.DE", Map.of("2026-10-26", "100")));
        PortfolioInputs in2 = new PortfolioInputs(List.of(IKE), basicOps(), List.of(), Map.of(), Map.of(), edge,
                List.of(), INSTRUMENTS, Map.of(), TODAY);
        assertThat(PortfolioCalculator.view(in2, "all").warnings()).noneMatch(w -> w.contains("AAA.DE"));
    }

    @Test
    void pricesAsOfIsOldestInstrumentPriceDate() {
        MarketData m = market(Map.of("AAA.DE", Map.of("2026-10-30", "100"), "BBB.PL", Map.of("2026-10-28", "50")));
        List<OperationRow> ops = new ArrayList<>(basicOps());
        ops.add(buy("xtb:IKE", "BBB.PL", "2026-03-05", "30", "p2", "1"));
        PortfolioInputs in = new PortfolioInputs(List.of(IKE), ops, List.of(), Map.of(), Map.of(), m, List.of(),
                INSTRUMENTS, Map.of(), TODAY);
        assertThat(PortfolioCalculator.view(in, "all").summary().pricesAsOf()).isEqualTo(LocalDate.parse("2026-10-28"));
    }

    @Test
    void missingCpiWarningsAreSortedByMonth() {
        Map<String, EdoSeries> series = Map.of(
                "EDO0335", new EdoSeries("EDO0335", bd("5.00"), bd("1.50")),
                "EDO0135", new EdoSeries("EDO0135", bd("5.00"), bd("1.50")));
        PortfolioInputs in = withBonds(basic(), List.of(
                new BondPurchase(1, "EDO0335", LocalDate.parse("2025-03-10"), 1),
                new BondPurchase(2, "EDO0135", LocalDate.parse("2025-01-10"), 1)), series, Map.of());
        List<String> cpi = PortfolioCalculator.view(in, "all").warnings().stream()
                .filter(w -> w.contains("2025-12") || w.contains("2026-02")).toList();
        assertThat(cpi).hasSize(2);
        assertThat(cpi.get(0)).contains("2025-12");
        assertThat(cpi.get(1)).contains("2026-02");
    }

    @Test
    void bondWithoutSeriesRatesIsValuedAtNominalWithWarning() {
        PortfolioInputs in = withBonds(basic(),
                List.of(new BondPurchase(1, "EDO1036", LocalDate.parse("2026-10-02"), 2)), Map.of(), Map.of());
        PortfolioView v = PortfolioCalculator.view(in, "all");
        assertMoney(row(v, "edo").value(), "200");
        assertThat(v.warnings()).anyMatch(w -> w.contains("EDO1036"));
    }

    @Test
    void reconciliationWarnsOnVolumeMismatchOnly() {
        Instant r = Instant.parse("2026-11-01T10:00:00Z");
        PortfolioInputs ok = new PortfolioInputs(List.of(IKE), basicOps(), List.of(), Map.of(), Map.of(), PRICES,
                List.of(new SnapshotRow("xtb:IKE", "AAA.DE", bd("10.00005"), bd("1000"), r)), INSTRUMENTS, Map.of(),
                TODAY);
        assertThat(PortfolioCalculator.view(ok, "all").warnings()).noneMatch(w -> w.contains("AAA.DE"));
        PortfolioInputs bad = new PortfolioInputs(List.of(IKE), basicOps(), List.of(), Map.of(), Map.of(), PRICES,
                List.of(new SnapshotRow("xtb:IKE", "AAA.DE", bd("11"), bd("1100"), r)), INSTRUMENTS, Map.of(), TODAY);
        assertThat(PortfolioCalculator.view(bad, "all").warnings()).anyMatch(w -> w.contains("AAA.DE"));
        // A snapshot of another account is out of scope for this filter.
        PortfolioInputs other = new PortfolioInputs(List.of(IKE, TRADES), basicOps(), List.of(), Map.of(), Map.of(),
                PRICES, List.of(new SnapshotRow("xtb:Trades", "AAA.DE", bd("99"), bd("1"), r)), INSTRUMENTS, Map.of(),
                TODAY);
        assertThat(PortfolioCalculator.view(other, "xtb:IKE").warnings()).noneMatch(w -> w.contains("AAA.DE"));
    }

    @Test
    void syncErrorsProduceOneWarningListingTickersAndFx() {
        PortfolioInputs in = new PortfolioInputs(List.of(IKE), basicOps(), List.of(), Map.of(), Map.of(), PRICES,
                List.of(), INSTRUMENTS, Map.of("BBB.PL", "timeout", "FX:EUR", "boom"), TODAY);
        List<String> w = PortfolioCalculator.view(in, "all").warnings().stream()
                .filter(x -> x.contains("BBB.PL")).toList();
        assertThat(w).hasSize(1);
        assertThat(w.get(0)).contains("EUR");
    }

    @Test
    void noDataGivesEmptyViewWithoutException() {
        PortfolioView v = PortfolioCalculator.view(inputs(List.of(), List.of()), "all");
        assertThat(v.empty()).isTrue();
        assertThat(v.positions()).isEmpty();
        assertThat(v.series()).isEmpty();
        assertThat(v.summary().gainPct()).isNull();
        assertThat(v.summary().returnValue()).isNull();
        assertThat(v.summary().pricesAsOf()).isNull();
        assertMoney(v.summary().value(), "0");
    }

    @Test
    void singleDepositOnTheSameDayHasNoReturn() {
        PortfolioInputs in = inputs(List.of(IKE), List.of(deposit("xtb:IKE", "2026-11-02", "100")));
        PortfolioView v = PortfolioCalculator.view(in, "all");
        assertThat(v.summary().returnValue()).isNull();
        assertThat(v.series()).hasSize(1);
        assertThat(v.series().get(0).cumulativeReturn()).isNull();
    }

    @Test
    void headlineIsCumulativeUnderAYearAndAnnualizedFromAYear() {
        PortfolioView shortHistory = PortfolioCalculator.view(basic(), "all");
        assertThat(shortHistory.summary().returnAnnualized()).isFalse();
        assertThat(shortHistory.summary().returnValue()).isNotNull();
        // 1000 -> 1110 over 245 days: cumulative return must be 11%
        assertThat(shortHistory.summary().returnValue()).isEqualTo(0.11, org.assertj.core.data.Offset.offset(1e-6));
        SeriesPoint last = shortHistory.series().get(shortHistory.series().size() - 1);
        assertThat(last.cumulativeReturn()).isEqualTo(0.11, org.assertj.core.data.Offset.offset(1e-6));

        List<OperationRow> ops = List.of(deposit("xtb:IKE", "2025-10-01", "1000"),
                buy("xtb:IKE", "AAA.DE", "2025-10-02", "900", "p1", "10"));
        PortfolioView longHistory = PortfolioCalculator.view(inputs(List.of(IKE), ops), "all");
        assertThat(longHistory.summary().returnAnnualized()).isTrue();
        assertThat(longHistory.summary().returnValue()).isNotNull();
    }

    @Test
    void detailMergesPurchaseRowsSharingPositionId() {
        List<OperationRow> ops = new ArrayList<>(basicOps());
        ops.add(op("xtb:IKE", OperationType.PURCHASE, "AAA.DE", "2026-03-03", "-45", "p1", "5", "9"));
        ops.add(buy("xtb:IKE", "AAA.DE", "2026-05-02", "100", "p2", "1"));
        PositionDetail d = PortfolioCalculator.detail(inputs(List.of(IKE), ops), "all", "AAA.DE");
        assertThat(d.purchases()).hasSize(2);
        var first = d.purchases().get(0);
        assertThat(first.day()).isEqualTo(LocalDate.parse("2026-03-03"));
        assertMoney(first.volume(), "15");
        assertMoney(first.amountPln(), "945");
        assertMoney(first.price(), "0");
        assertMoney(first.valueNow(), "1500");
        assertThat(first.accountName()).isEqualTo("IKE XTB");
        assertThat(first.label()).isEqualTo("AAA.DE");
        assertMoney(d.row().value(), "1600");
    }

    @Test
    void detailSeriesIsPositionValueAndCostPerDay() {
        PositionDetail d = PortfolioCalculator.detail(basic(), "all", "AAA.DE");
        assertThat(d.series().get(0).day()).isEqualTo(LocalDate.parse("2026-03-03"));
        assertThat(d.series()).allMatch(p -> p.cumulativeReturn() == null);
        assertMoney(point(d.series(), "2026-10-29").value(), "900");
        assertMoney(point(d.series(), "2026-10-29").contributions(), "900");
        SeriesPoint last = d.series().get(d.series().size() - 1);
        assertMoney(last.value(), "1000");
        assertMoney(last.contributions(), "900");
    }

    @Test
    void detailOfEdoListsEachPurchaseWithSeriesCode() {
        Map<String, EdoSeries> series = Map.of("EDO1036", new EdoSeries("EDO1036", bd("5.60"), bd("2.00")));
        PortfolioInputs in = withBonds(basic(),
                List.of(new BondPurchase(1, "EDO1036", LocalDate.parse("2026-10-02"), 10)), series, Map.of());
        PositionDetail d = PortfolioCalculator.detail(in, "all", "edo");
        assertThat(d.purchases()).hasSize(1);
        var p = d.purchases().get(0);
        assertThat(p.label()).isEqualTo("EDO1036");
        assertMoney(p.volume(), "10");
        assertMoney(p.price(), "100");
        assertMoney(p.amountPln(), "1000");
        assertMoney(p.valueNow(), "1004.80");
        assertThat(p.accountName()).isEqualTo("Obligacje");
        assertThat(d.series().get(0).day()).isEqualTo(LocalDate.parse("2026-10-02"));
        assertMoney(d.series().get(0).value(), "1000");
        assertMoney(d.series().get(0).contributions(), "1000");
        assertMoney(d.series().get(d.series().size() - 1).value(), "1004.80");
    }

    @Test
    void detailOfCashUnknownKeyOrKeyOutsideFilterIsNotFound() {
        assertThatThrownBy(() -> PortfolioCalculator.detail(basic(), "all", "cash")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> PortfolioCalculator.detail(basic(), "all", "ZZZ")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> PortfolioCalculator.detail(basic(), "all", "edo")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> PortfolioCalculator.detail(basic(), "bonds", "AAA.DE"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void wholeHistoryDashboardStaysFast() {
        List<OperationRow> ops = new ArrayList<>();
        Map<String, Map<String, String>> closes = new HashMap<>();
        List<InstrumentRow> instruments = new ArrayList<>();
        LocalDate start = TODAY.minusYears(4);
        for (int t = 0; t < 12; t++) {
            String ticker = "T" + t + ".DE";
            instruments.add(new InstrumentRow(ticker, "Instrument " + t, "ETF", null, "PLN"));
            Map<String, String> days = new HashMap<>();
            for (LocalDate d = start; !d.isAfter(TODAY); d = d.plusDays(1)) {
                days.put(d.toString(), String.valueOf(50 + (d.toEpochDay() % 17)));
            }
            closes.put(ticker, days);
        }
        for (LocalDate d = start; !d.isAfter(TODAY); d = d.plusMonths(1)) {
            ops.add(deposit("xtb:IKE", d.toString(), "1000"));
            for (int t = 0; t < 12; t++) {
                ops.add(buy("xtb:IKE", "T" + t + ".DE", d.toString(), "50", "px" + t + d, "1"));
            }
        }
        List<BondPurchase> bonds = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            bonds.add(new BondPurchase(i, "EDO0330", start.plusMonths(i), 5));
        }
        PortfolioInputs in = new PortfolioInputs(List.of(IKE, BONDS), ops, bonds,
                Map.of("EDO0330", new EdoSeries("EDO0330", bd("5.00"), bd("1.50"))), Map.of(), market(closes),
                List.of(), instruments, Map.of(), TODAY);
        long t0 = System.nanoTime();
        PortfolioView v = PortfolioCalculator.view(in, "all");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(v.series()).hasSizeGreaterThan(1400);
        assertThat(ms).isLessThan(5000);
    }
}

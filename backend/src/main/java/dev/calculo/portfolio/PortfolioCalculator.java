package dev.calculo.portfolio;

import dev.calculo.ClockConfig;
import dev.calculo.NotFoundException;
import dev.calculo.account.Account;
import dev.calculo.bonds.BondPurchase;
import dev.calculo.bonds.BondValue;
import dev.calculo.bonds.EdoCalculator;
import dev.calculo.bonds.EdoSeries;
import dev.calculo.portfolio.PortfolioView.PositionDetail;
import dev.calculo.portfolio.PortfolioView.PositionRow;
import dev.calculo.portfolio.PortfolioView.PurchaseRow;
import dev.calculo.portfolio.PortfolioView.SeriesPoint;
import dev.calculo.portfolio.PortfolioView.Summary;
import dev.calculo.prices.MarketData.PricedAt;
import dev.calculo.xtb.LedgerRepository.InstrumentRow;
import dev.calculo.xtb.LedgerRepository.OperationRow;
import dev.calculo.xtb.LedgerRepository.SnapshotRow;
import dev.calculo.xtb.ParsedExport.OperationType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Pure portfolio calculation. Calendar days are Warsaw days; money is PLN rounded HALF_UP to the grosz.
 * An instrument without a known price by a given day is valued at its purchase cost to that day.
 */
public final class PortfolioCalculator {
    public static final String ALL = "all";
    public static final String BONDS_FILTER = "bonds";
    public static final String CASH_KEY = "cash";
    public static final String EDO_KEY = "edo";

    private static final BigDecimal NOMINAL = BigDecimal.valueOf(100);
    private static final BigDecimal VOLUME_TOLERANCE = new BigDecimal("0.0001");
    private static final int STALE_DAYS = 7;
    private static final String INSTRUMENT = "INSTRUMENT";

    private PortfolioCalculator() {}

    public static PortfolioView view(PortfolioInputs in, String accountFilter) {
        Ctx c = new Ctx(in, accountFilter);
        List<Draft> drafts = c.drafts();
        BigDecimal value = drafts.stream().map(Draft::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal contributions = c.contribs.stream().map(Dated::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal gain = value.subtract(contributions);
        Optional<Returns.Headline> headline = c.empty()
                ? Optional.empty() : Returns.headline(c.flows(), c.today, value.doubleValue());
        LocalDate pricesAsOf = drafts.stream().filter(d -> d.priceDate != null).map(d -> d.priceDate)
                .min(LocalDate::compareTo).orElse(null);
        Summary summary = new Summary(scale(value), scale(contributions), scale(gain), pct(gain, contributions),
                headline.map(h -> finite(h.value())).orElse(null),
                headline.map(Returns.Headline::annualized).orElse(false), pricesAsOf);
        return new PortfolioView(summary, c.rows(drafts, value), c.series(), c.warnings(drafts), c.empty());
    }

    public static PositionDetail detail(PortfolioInputs in, String accountFilter, String key) {
        Ctx c = new Ctx(in, accountFilter);
        List<Draft> drafts = c.drafts();
        BigDecimal total = drafts.stream().map(Draft::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        Draft draft = drafts.stream().filter(d -> d.key.equals(key) && !d.kind.equals("CASH")).findFirst()
                .orElseThrow(() -> new NotFoundException("Nie znaleziono pozycji: " + key));
        PositionRow row = c.rows(List.of(draft), total).get(0);
        boolean bonds = key.equals(EDO_KEY);
        List<PurchaseRow> purchases = bonds ? c.bondPurchaseRows() : c.instrumentPurchaseRows(key);
        LocalDate start = purchases.get(0).day();
        List<SeriesPoint> series = c.walk(bonds ? List.of() : List.of(key), bonds, false, start).stream()
                .map(p -> new SeriesPoint(p.day, scale(p.value), scale(p.cost), null)).toList();
        return new PositionDetail(row, purchases, series);
    }

    // ---- helpers ----

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        if (whole.signum() <= 0) return null;
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 2, RoundingMode.HALF_UP);
    }

    private static Double finite(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
    }

    private record Dated(LocalDate day, BigDecimal amount) {}

    private record Lot(LocalDate day, BigDecimal volume, BigDecimal cost) {}

    private record Op(OperationRow row, LocalDate day) {}

    private record Bond(BondPurchase purchase, EdoSeries series) {}

    private record Point(LocalDate day, BigDecimal value, BigDecimal cost) {}

    /** One position at {@code today} before shares are known. */
    private static final class Draft {
        String key;
        String name;
        String kind;
        BigDecimal value;
        BigDecimal cost; // null for cash
        LocalDate priceDate;

        BigDecimal value() { return value; }
    }

    private static final class Ctx {
        final PortfolioInputs in;
        final LocalDate today;
        final boolean includeBonds;
        final Map<String, String> accountNames = new HashMap<>();
        final String filter;
        final List<Op> ops = new ArrayList<>();
        final Map<String, List<Lot>> lots = new TreeMap<>();
        final List<Dated> cash = new ArrayList<>();
        final List<Dated> contribs = new ArrayList<>();
        final List<Bond> bonds = new ArrayList<>();
        final Map<String, String> names = new HashMap<>();

        Ctx(PortfolioInputs in, String requested) {
            this.in = in;
            this.today = in.today();
            this.filter = requested == null || requested.isBlank() ? ALL : requested;
            boolean all = filter.equals(ALL);
            boolean bondsOnly = filter.equals(BONDS_FILTER);
            if (!all && !bondsOnly && in.accounts().stream().noneMatch(a -> a.id().equals(filter))) {
                throw new NotFoundException("Nie znaleziono konta: " + filter);
            }
            this.includeBonds = all || bondsOnly;
            for (Account a : in.accounts()) accountNames.put(a.id(), a.name());
            for (InstrumentRow i : in.instruments()) names.put(i.ticker(), i.name());

            for (OperationRow r : in.operations()) {
                if (bondsOnly || !(all || r.accountId().equals(filter))) continue;
                LocalDate day = r.occurredAt().atZone(ClockConfig.WARSAW).toLocalDate();
                if (day.isAfter(today)) continue;
                ops.add(new Op(r, day));
            }
            ops.sort(Comparator.comparing(Op::day));
            for (Op o : ops) {
                OperationRow r = o.row;
                cash.add(new Dated(o.day, r.amountPln()));
                if (r.type() == OperationType.DEPOSIT) contribs.add(new Dated(o.day, r.amountPln()));
                if (r.type() == OperationType.PURCHASE && r.ticker() != null) {
                    BigDecimal volume = r.volume() == null ? BigDecimal.ZERO : r.volume();
                    lots.computeIfAbsent(r.ticker(), t -> new ArrayList<>())
                            .add(new Lot(o.day, volume, r.amountPln().negate()));
                }
            }
            if (includeBonds) {
                for (BondPurchase p : in.bonds()) {
                    if (p.purchaseDate().isAfter(today)) continue;
                    bonds.add(new Bond(p, in.series().get(p.seriesCode())));
                    contribs.add(new Dated(p.purchaseDate(), NOMINAL.multiply(BigDecimal.valueOf(p.quantity()))));
                }
                bonds.sort(Comparator.comparing(b -> b.purchase.purchaseDate()));
            }
            contribs.sort(Comparator.comparing(Dated::day));
        }

        boolean empty() {
            return ops.isEmpty() && bonds.isEmpty();
        }

        /** Contribution flows, same-day amounts summed, for Returns. */
        List<Flow> flows() {
            Map<LocalDate, BigDecimal> byDay = new TreeMap<>();
            for (Dated d : contribs) byDay.merge(d.day, d.amount, BigDecimal::add);
            return byDay.entrySet().stream().map(e -> new Flow(e.getKey(), e.getValue().doubleValue())).toList();
        }

        BigDecimal tickerValue(String ticker, BigDecimal qty, BigDecimal cost, LocalDate day) {
            if (qty.signum() <= 0) return BigDecimal.ZERO;
            return in.market().pricePln(ticker, day)
                    .map(p -> scale(qty.multiply(p.pln()))).orElse(cost);
        }

        BondValue perBond(Bond b, LocalDate day) {
            return EdoCalculator.valuePerBond(b.purchase.purchaseDate(), b.series, m -> Optional.ofNullable(in.cpi().get(m)), day);
        }

        BigDecimal bondValue(Bond b, LocalDate day) {
            BigDecimal qty = BigDecimal.valueOf(b.purchase.quantity());
            return b.series == null ? NOMINAL.multiply(qty) : scale(perBond(b, day).value().multiply(qty));
        }

        /** Daily value and cost of the chosen tickers (plus bonds and cash when asked) from {@code start} to today. */
        List<Point> walk(Collection<String> tickers, boolean withBonds, boolean withCash, LocalDate start) {
            List<String> ts = List.copyOf(tickers);
            int n = ts.size();
            List<List<Lot>> lists = new ArrayList<>();
            for (String t : ts) lists.add(lots.getOrDefault(t, List.of()));
            int[] idx = new int[n];
            BigDecimal[] qty = new BigDecimal[n];
            BigDecimal[] cost = new BigDecimal[n];
            java.util.Arrays.fill(qty, BigDecimal.ZERO);
            java.util.Arrays.fill(cost, BigDecimal.ZERO);
            int cashIdx = 0;
            BigDecimal cashSum = BigDecimal.ZERO;
            int active = 0;
            List<Point> points = new ArrayList<>();
            for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
                BigDecimal value = BigDecimal.ZERO;
                BigDecimal costSum = BigDecimal.ZERO;
                for (int i = 0; i < n; i++) {
                    List<Lot> list = lists.get(i);
                    while (idx[i] < list.size() && !list.get(idx[i]).day.isAfter(day)) {
                        Lot lot = list.get(idx[i]++);
                        qty[i] = qty[i].add(lot.volume);
                        cost[i] = cost[i].add(lot.cost);
                    }
                    if (qty[i].signum() > 0) {
                        value = value.add(tickerValue(ts.get(i), qty[i], cost[i], day));
                        costSum = costSum.add(cost[i]);
                    }
                }
                if (withCash) {
                    while (cashIdx < cash.size() && !cash.get(cashIdx).day.isAfter(day)) {
                        cashSum = cashSum.add(cash.get(cashIdx++).amount);
                    }
                    value = value.add(cashSum);
                }
                if (withBonds) {
                    while (active < bonds.size() && !bonds.get(active).purchase.purchaseDate().isAfter(day)) active++;
                    for (int j = 0; j < active; j++) {
                        Bond b = bonds.get(j);
                        value = value.add(bondValue(b, day));
                        costSum = costSum.add(NOMINAL.multiply(BigDecimal.valueOf(b.purchase.quantity())));
                    }
                }
                points.add(new Point(day, value, costSum));
            }
            return points;
        }

        List<Draft> drafts() {
            List<Draft> drafts = new ArrayList<>();
            for (Map.Entry<String, List<Lot>> e : lots.entrySet()) {
                BigDecimal qty = e.getValue().stream().map(Lot::volume).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (qty.signum() <= 0) continue;
                BigDecimal cost = e.getValue().stream().map(Lot::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
                Optional<PricedAt> price = in.market().pricePln(e.getKey(), today);
                Draft d = new Draft();
                d.key = e.getKey();
                d.name = names.getOrDefault(e.getKey(), e.getKey());
                d.kind = INSTRUMENT;
                d.cost = cost;
                d.value = price.map(p -> scale(qty.multiply(p.pln()))).orElse(cost);
                d.priceDate = price.map(PricedAt::priceDate).orElse(null);
                drafts.add(d);
            }
            if (!bonds.isEmpty()) {
                Draft d = new Draft();
                d.key = EDO_KEY;
                d.name = "Obligacje EDO";
                d.kind = "BONDS";
                d.value = BigDecimal.ZERO;
                d.cost = BigDecimal.ZERO;
                for (Bond b : bonds) {
                    d.value = d.value.add(bondValue(b, today));
                    d.cost = d.cost.add(NOMINAL.multiply(BigDecimal.valueOf(b.purchase.quantity())));
                }
                drafts.add(d);
            }
            BigDecimal cashTotal = cash.stream().map(Dated::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (cashTotal.signum() != 0) {
                Draft d = new Draft();
                d.key = CASH_KEY;
                d.name = "Gotówka";
                d.kind = "CASH";
                d.value = cashTotal;
                drafts.add(d);
            }
            drafts.sort(Comparator.comparing(Draft::value).reversed().thenComparing(d -> d.key));
            return drafts;
        }

        List<PositionRow> rows(List<Draft> drafts, BigDecimal total) {
            return drafts.stream().map(d -> new PositionRow(d.key, d.name, d.kind, scale(d.value),
                    total.signum() > 0 ? d.value.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP) : null,
                    d.cost == null ? null : pct(d.value.subtract(d.cost), d.cost), d.priceDate)).toList();
        }

        List<SeriesPoint> series() {
            if (empty()) return List.of();
            LocalDate start = contribs.isEmpty() ? earliestEvent() : contribs.get(0).day;
            Set<String> tickers = lots.keySet();
            List<Point> points = walk(tickers, includeBonds, true, start);
            List<Flow> flows = flows();
            List<SeriesPoint> series = new ArrayList<>(points.size());
            int ci = 0;
            BigDecimal contributed = BigDecimal.ZERO;
            for (Point p : points) {
                while (ci < contribs.size() && !contribs.get(ci).day.isAfter(p.day)) {
                    contributed = contributed.add(contribs.get(ci++).amount);
                }
                OptionalDouble r = Returns.cumulative(flows, p.day, p.value.doubleValue());
                series.add(new SeriesPoint(p.day, scale(p.value), scale(contributed),
                        r.isPresent() ? finite(r.getAsDouble()) : null));
            }
            return series;
        }

        LocalDate earliestEvent() {
            LocalDate first = null;
            for (Op o : ops) first = first == null || o.day.isBefore(first) ? o.day : first;
            for (Bond b : bonds) {
                LocalDate d = b.purchase.purchaseDate();
                first = first == null || d.isBefore(first) ? d : first;
            }
            return first;
        }

        List<String> warnings(List<Draft> drafts) {
            List<String> w = new ArrayList<>();
            syncWarning(w);
            for (Draft d : drafts) {
                if (!d.kind.equals(INSTRUMENT)) continue;
                if (d.priceDate == null) {
                    w.add("Brak ceny dla " + d.key + ": pozycja wyceniona po koszcie zakupu.");
                } else if (ChronoUnit.DAYS.between(d.priceDate, today) > STALE_DAYS) {
                    w.add("Cena " + d.key + " pochodzi z " + d.priceDate + " (starsza niż " + STALE_DAYS + " dni).");
                }
            }
            reconciliationWarnings(w);
            bondWarnings(w);
            return w;
        }

        private void syncWarning(List<String> w) {
            List<String> failed = new TreeSet<>(in.syncStatus().keySet()).stream()
                    .map(k -> k.startsWith("FX:") ? "kurs " + k.substring(3) : k).toList();
            if (!failed.isEmpty()) {
                w.add("Nie udało się zaktualizować notowań: " + String.join(", ", failed) + ".");
            }
        }

        private void reconciliationWarnings(List<String> w) {
            Map<String, Map<String, BigDecimal>> computed = new TreeMap<>();
            for (Op o : ops) {
                OperationRow r = o.row;
                if (r.type() == OperationType.PURCHASE && r.ticker() != null && r.volume() != null) {
                    computed.computeIfAbsent(r.accountId(), a -> new TreeMap<>()).merge(r.ticker(), r.volume(), BigDecimal::add);
                }
            }
            Map<String, Map<String, BigDecimal>> reported = new TreeMap<>();
            for (SnapshotRow s : in.snapshots()) {
                if (filter.equals(BONDS_FILTER) || !(filter.equals(ALL) || s.accountId().equals(filter))) continue;
                reported.computeIfAbsent(s.accountId(), a -> new TreeMap<>()).merge(s.ticker(), s.volume(), BigDecimal::add);
            }
            // Only accounts that have a snapshot are checked; both directions, so unexplained sold-out lots show up.
            reported.forEach((account, snap) -> {
                Map<String, BigDecimal> calc = computed.getOrDefault(account, Map.of());
                for (String ticker : new TreeSet<>(java.util.stream.Stream.concat(snap.keySet().stream(),
                        calc.keySet().stream()).collect(Collectors.toSet()))) {
                    BigDecimal s = snap.getOrDefault(ticker, BigDecimal.ZERO);
                    BigDecimal k = calc.getOrDefault(ticker, BigDecimal.ZERO);
                    if (s.subtract(k).abs().compareTo(VOLUME_TOLERANCE) > 0) {
                        w.add("Rozbieżność ilości " + ticker + " na koncie " + accountNames.getOrDefault(account, account)
                                + ": w eksporcie " + s.stripTrailingZeros().toPlainString() + ", z operacji "
                                + k.stripTrailingZeros().toPlainString() + ".");
                    }
                }
            });
        }

        private void bondWarnings(List<String> w) {
            Set<String> noSeries = new TreeSet<>();
            Set<YearMonth> missing = new TreeSet<>();
            for (Bond b : bonds) {
                if (b.series == null) {
                    noSeries.add(b.purchase.seriesCode());
                } else {
                    missing.addAll(perBond(b, today).missingCpi());
                }
            }
            for (String code : noSeries) {
                w.add("Brak oprocentowania serii " + code + ": obligacje wyceniono po nominale.");
            }
            for (YearMonth m : missing) {
                w.add("Brak odczytu inflacji za " + m + ": wartość obligacji EDO jest przybliżona.");
            }
        }

        List<PurchaseRow> bondPurchaseRows() {
            String account = accountNames.getOrDefault("bonds", "Obligacje");
            return bonds.stream().map(b -> {
                BigDecimal qty = BigDecimal.valueOf(b.purchase.quantity());
                return new PurchaseRow(b.purchase.purchaseDate(), account, b.purchase.seriesCode(), qty, NOMINAL,
                        scale(NOMINAL.multiply(qty)), bondValue(b, today));
            }).toList();
        }

        /** Rows sharing an account and position id are fills of one purchase and merge into one row. */
        List<PurchaseRow> instrumentPurchaseRows(String ticker) {
            record Acc(LocalDate day, String account, BigDecimal[] sums, BigDecimal price) {}
            Map<String, Acc> merged = new LinkedHashMap<>();
            for (Op o : ops) {
                OperationRow r = o.row;
                if (r.type() != OperationType.PURCHASE || !ticker.equals(r.ticker())) continue;
                String id = r.positionId() == null ? "ext:" + r.externalId() : r.positionId();
                Acc acc = merged.computeIfAbsent(r.accountId() + "|" + id, k -> new Acc(o.day, r.accountId(),
                        new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO}, r.price()));
                acc.sums[0] = acc.sums[0].add(r.volume() == null ? BigDecimal.ZERO : r.volume());
                acc.sums[1] = acc.sums[1].add(r.amountPln().negate());
            }
            Optional<PricedAt> price = in.market().pricePln(ticker, today);
            return merged.values().stream().map(a -> new PurchaseRow(a.day, accountNames.getOrDefault(a.account, a.account),
                    ticker, a.sums[0], a.price, a.sums[1],
                    price.map(p -> scale(a.sums[0].multiply(p.pln()))).orElse(a.sums[1]))).toList();
        }
    }
}

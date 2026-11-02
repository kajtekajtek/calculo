package dev.calculo.prices;

import dev.calculo.ClockConfig;
import dev.calculo.xtb.InstrumentsChanged;
import dev.calculo.xtb.LedgerRepository;
import dev.calculo.xtb.LedgerRepository.InstrumentRow;
import dev.calculo.xtb.LedgerRepository.OperationRow;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Keeps daily closes and PLN exchange rates up to date for every instrument that has operations. */
@Service
public class PriceSyncService {
    private static final Logger log = LoggerFactory.getLogger(PriceSyncService.class);
    private static final String PLN = "PLN";
    /** NBP publishes no rate on weekends and holidays, so start a bit earlier to have one for the first price day. */
    private static final int FX_LOOKBACK_DAYS = 7;

    private final LedgerRepository ledger;
    private final MarketDataRepository repository;
    private final PriceProvider prices;
    private final FxProvider fx;
    private final SymbolMapper symbols;
    private final PricesProperties props;
    private final Clock clock;
    private final Map<String, String> status = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "price-sync");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean queued = new AtomicBoolean();

    public PriceSyncService(LedgerRepository ledger, MarketDataRepository repository, PriceProvider prices,
                            FxProvider fx, SymbolMapper symbols, PricesProperties props, Clock clock) {
        this.ledger = ledger;
        this.repository = repository;
        this.prices = prices;
        this.fx = fx;
        this.symbols = symbols;
        this.props = props;
        this.clock = clock;
    }

    /** Latest error per ticker (FX failures are keyed "FX:EUR"); an entry disappears once that sync succeeds. */
    public Map<String, String> syncStatus() {
        return new TreeMap<>(status);
    }

    /** Runs a sync on the background thread; a run already waiting in the queue covers this request. */
    public void syncAsync() {
        if (!queued.compareAndSet(false, true)) return;
        executor.execute(() -> {
            queued.set(false);
            try {
                sync();
            } catch (RuntimeException e) {
                log.warn("Price sync failed", e);
            }
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        if (props.syncOnStartup()) syncAsync();
    }

    @EventListener(InstrumentsChanged.class)
    void onInstrumentsChanged() {
        if (props.syncOnImport()) syncAsync();
    }

    @Scheduled(cron = "${calculo.prices.cron:0 30 18 * * *}", zone = "Europe/Warsaw")
    void scheduled() {
        if (props.scheduleEnabled()) syncAsync();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** Fetches what is missing for every instrument and currency. Never throws for provider problems. */
    public synchronized void sync() {
        LocalDate today = LocalDate.now(clock.withZone(ClockConfig.WARSAW));
        Map<String, LocalDate> firstDays = firstOperationDays();
        Map<String, String> currencies = new HashMap<>();
        for (InstrumentRow instrument : ledger.instruments()) {
            LocalDate firstDay = firstDays.get(instrument.ticker());
            if (firstDay == null) continue;
            if (instrument.currency() != null) currencies.put(instrument.ticker(), instrument.currency());
            syncTicker(instrument, firstDay, today).ifPresent(c -> currencies.put(instrument.ticker(), c));
        }
        Map<String, LocalDate> fxStart = new HashMap<>();
        currencies.forEach((ticker, currency) -> {
            if (!PLN.equalsIgnoreCase(currency)) fxStart.merge(currency, firstDays.get(ticker),
                    (a, b) -> a.isBefore(b) ? a : b);
        });
        fxStart.forEach((currency, firstDay) -> syncFx(currency, firstDay, today));
    }

    private java.util.Optional<String> syncTicker(InstrumentRow instrument, LocalDate firstDay, LocalDate today) {
        String ticker = instrument.ticker();
        try {
            // All-or-nothing: every range is fetched before anything is stored, so a failure leaves no gap.
            // Re-fetching the last stored day corrects a close saved while the market was still open.
            java.util.List<DailyPrice> fetched = new java.util.ArrayList<>();
            String currency = null;
            for (DateRange range : missingRanges(firstDay, repository.earliestPriceDay(ticker),
                    repository.latestPriceDay(ticker), today)) {
                PriceSeries series = prices.history(symbols.symbol(ticker, instrument.priceSymbol()),
                        range.from(), range.to());
                fetched.addAll(series.prices());
                currency = series.currency();
            }
            if (currency == null) return java.util.Optional.empty();
            repository.savePrices(ticker, currency, fetched);
            status.remove(ticker);
            return java.util.Optional.of(currency);
        } catch (PriceUnavailableException | RuntimeException e) {
            log.warn("Price sync failed for {}: {}", ticker, e.getMessage());
            status.put(ticker, String.valueOf(e.getMessage()));
            return java.util.Optional.empty();
        }
    }

    private void syncFx(String currency, LocalDate firstDay, LocalDate today) {
        String key = "FX:" + currency;
        try {
            Map<LocalDate, BigDecimal> fetched = new TreeMap<>();
            for (DateRange range : missingRanges(firstDay.minusDays(FX_LOOKBACK_DAYS),
                    repository.earliestFxDay(currency), repository.latestFxDay(currency), today)) {
                fetched.putAll(fx.rates(currency, range.from(), range.to()));
            }
            repository.saveFx(currency, fetched);
            status.remove(key);
        } catch (PriceUnavailableException | RuntimeException e) {
            log.warn("FX sync failed for {}: {}", currency, e.getMessage());
            status.put(key, String.valueOf(e.getMessage()));
        }
    }

    private record DateRange(LocalDate from, LocalDate to) {}

    /** Ranges to fetch: everything when nothing is stored, else the older part and the part since the last day. */
    private static java.util.List<DateRange> missingRanges(LocalDate needFrom, java.util.Optional<LocalDate> earliest,
                                                            java.util.Optional<LocalDate> latest, LocalDate today) {
        java.util.List<DateRange> ranges = new java.util.ArrayList<>();
        if (earliest.isEmpty() || latest.isEmpty()) {
            if (!needFrom.isAfter(today)) ranges.add(new DateRange(needFrom, today));
            return ranges;
        }
        if (needFrom.isBefore(earliest.get())) ranges.add(new DateRange(needFrom, earliest.get()));
        if (!latest.get().isAfter(today)) ranges.add(new DateRange(latest.get(), today));
        return ranges;
    }

    private Map<String, LocalDate> firstOperationDays() {
        Map<String, LocalDate> first = new HashMap<>();
        for (OperationRow op : ledger.allOperations()) {
            if (op.ticker() == null) continue;
            LocalDate day = op.occurredAt().atZone(ClockConfig.WARSAW).toLocalDate();
            first.merge(op.ticker(), day, (a, b) -> a.isBefore(b) ? a : b);
        }
        return first;
    }
}

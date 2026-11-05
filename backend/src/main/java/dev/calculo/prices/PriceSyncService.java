package dev.calculo.prices;

import dev.calculo.ClockConfig;
import dev.calculo.xtb.InstrumentsChanged;
import dev.calculo.xtb.LedgerRepository;
import dev.calculo.xtb.LedgerRepository.InstrumentRow;
import dev.calculo.xtb.LedgerRepository.OperationRow;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    /** How long to leave a provider alone after a 429 that does not say. */
    private static final Duration DEFAULT_COOLDOWN = Duration.ofHours(1);
    /** {@link #syncStatus()} key for a provider that cannot work at all (e.g. no API key). */
    public static final String PROVIDER_STATUS = "PROVIDER";

    /** From this hour in Warsaw the day's close counts as final (the provider publishes closes after the session). */
    private static final java.time.LocalTime SETTLED_FROM = java.time.LocalTime.of(22, 0);

    /** The latest weekday whose close is final: today from {@link #SETTLED_FROM} in Warsaw, else an earlier day. */
    static LocalDate lastSettledDay(Instant now) {
        java.time.LocalDateTime local = java.time.LocalDateTime.ofInstant(now, ClockConfig.WARSAW);
        LocalDate day = local.toLocalTime().isBefore(SETTLED_FROM) ? local.toLocalDate().minusDays(1) : local.toLocalDate();
        while (day.getDayOfWeek().getValue() > 5) day = day.minusDays(1);
        return day;
    }

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
    /** Runs queued or in progress, so a status request right after a trigger already sees one. */
    private final AtomicInteger inFlight = new AtomicInteger();
    /** Kept in memory: after a restart the first request finds out again whether the limit still applies. */
    private volatile Instant notBefore;

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

    public PriceSyncState state() {
        return new PriceSyncState(inFlight.get() > 0, activeCooldown());
    }

    private Instant activeCooldown() {
        Instant until = notBefore;
        return until != null && clock.instant().isBefore(until) ? until : null;
    }

    /**
     * Runs a sync on the background thread; a run already waiting in the queue covers this request.
     * Does nothing during a rate-limit cooldown.
     */
    public void syncAsync() {
        if (activeCooldown() != null) return;
        if (!queued.compareAndSet(false, true)) return;
        inFlight.incrementAndGet();
        executor.execute(() -> {
            queued.set(false);
            try {
                sync();
            } catch (RuntimeException e) {
                log.warn("Price sync failed", e);
            } finally {
                inFlight.decrementAndGet();
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

    // Half an hour after the day counts as settled (see lastSettledDay), so the evening run gets that day's close.
    @Scheduled(cron = "${calculo.prices.cron:0 30 22 * * *}", zone = "Europe/Warsaw")
    void scheduled() {
        if (props.scheduleEnabled()) syncAsync();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Fetches what is missing for every instrument and currency. Never throws for provider problems.
     * A rate limit ends the run and starts a cooldown during which this does nothing.
     */
    public synchronized void sync() {
        if (activeCooldown() != null) return;
        inFlight.incrementAndGet();
        try {
            syncEverything();
        } catch (ProviderNotConfiguredException e) {
            status.put(PROVIDER_STATUS, String.valueOf(e.getMessage()));
            log.warn("Price sync stopped: {}", e.getMessage());
        } catch (RateLimitedException e) {
            Duration wait = e.retryAfter() == null ? DEFAULT_COOLDOWN : e.retryAfter();
            notBefore = clock.instant().plus(wait);
            log.warn("Price sync stopped until {}: {}", notBefore, e.getMessage());
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private void syncEverything() throws RateLimitedException, ProviderNotConfiguredException {
        LocalDate today = LocalDate.now(clock.withZone(ClockConfig.WARSAW));
        LocalDate settled = lastSettledDay(clock.instant());
        Map<String, LocalDate> firstDays = firstOperationDays();
        Map<String, String> currencies = new HashMap<>();
        for (InstrumentRow instrument : ledger.instruments()) {
            LocalDate firstDay = firstDays.get(instrument.ticker());
            if (firstDay == null) continue;
            if (instrument.currency() != null) currencies.put(instrument.ticker(), instrument.currency());
            syncTicker(instrument, firstDay, settled).ifPresent(c -> currencies.put(instrument.ticker(), c));
        }
        Map<String, LocalDate> fxStart = new HashMap<>();
        currencies.forEach((ticker, currency) -> {
            if (!PLN.equalsIgnoreCase(currency)) fxStart.merge(currency, firstDays.get(ticker),
                    (a, b) -> a.isBefore(b) ? a : b);
        });
        for (Map.Entry<String, LocalDate> e : fxStart.entrySet()) syncFx(e.getKey(), e.getValue(), today);
    }

    /** Fetches closes up to {@code settled} only, so a stored day never changes and is never asked for again. */
    private java.util.Optional<String> syncTicker(InstrumentRow instrument, LocalDate firstDay, LocalDate settled)
            throws RateLimitedException, ProviderNotConfiguredException {
        String ticker = instrument.ticker();
        try {
            // All-or-nothing: every range is fetched before anything is stored, so a failure leaves no gap.
            java.util.List<DailyPrice> fetched = new java.util.ArrayList<>();
            String currency = null;
            for (DateRange range : missingPriceRanges(firstDay, repository.earliestPriceDay(ticker),
                    repository.latestPriceDay(ticker), settled)) {
                PriceSeries series = prices.history(symbols.symbol(ticker, instrument.priceSymbol()),
                        range.from(), range.to());
                fetched.addAll(series.prices());
                currency = series.currency();
            }
            if (currency == null) return java.util.Optional.empty();
            repository.savePrices(ticker, currency, fetched);
            status.remove(ticker);
            status.remove(PROVIDER_STATUS);
            return java.util.Optional.of(currency);
        } catch (ProviderNotConfiguredException e) {
            throw e;
        } catch (RateLimitedException e) {
            status.put(ticker, String.valueOf(e.getMessage()));
            throw e;
        } catch (PriceUnavailableException | RuntimeException e) {
            log.warn("Price sync failed for {}: {}", ticker, e.getMessage());
            status.put(ticker, String.valueOf(e.getMessage()));
            return java.util.Optional.empty();
        }
    }

    private void syncFx(String currency, LocalDate firstDay, LocalDate today) throws RateLimitedException {
        String key = "FX:" + currency;
        try {
            Map<LocalDate, BigDecimal> fetched = new TreeMap<>();
            for (DateRange range : missingRanges(firstDay.minusDays(FX_LOOKBACK_DAYS),
                    repository.earliestFxDay(currency), repository.latestFxDay(currency), today)) {
                fetched.putAll(fx.rates(currency, range.from(), range.to()));
            }
            repository.saveFx(currency, fetched);
            status.remove(key);
        } catch (RateLimitedException e) {
            status.put(key, String.valueOf(e.getMessage()));
            throw e;
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

    /** Like {@link #missingRanges}, but the stored days are final, so the newer part starts the day after. */
    private static java.util.List<DateRange> missingPriceRanges(LocalDate needFrom, java.util.Optional<LocalDate> earliest,
                                                                 java.util.Optional<LocalDate> latest, LocalDate settled) {
        java.util.List<DateRange> ranges = new java.util.ArrayList<>();
        if (earliest.isEmpty() || latest.isEmpty()) {
            if (!needFrom.isAfter(settled)) ranges.add(new DateRange(needFrom, settled));
            return ranges;
        }
        if (needFrom.isBefore(earliest.get())) ranges.add(new DateRange(needFrom, earliest.get()));
        if (latest.get().isBefore(settled)) ranges.add(new DateRange(latest.get().plusDays(1), settled));
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

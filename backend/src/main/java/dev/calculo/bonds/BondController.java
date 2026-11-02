package dev.calculo.bonds;

import dev.calculo.BadRequestException;
import dev.calculo.ClockConfig;
import dev.calculo.NotFoundException;
import dev.calculo.account.Account;
import dev.calculo.account.AccountRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class BondController {
    static final String BOND_ACCOUNT_ID = "bonds";
    private static final Pattern SERIES_CODE = Pattern.compile("EDO(0[1-9]|1[0-2])\\d{2}");
    private static final BigDecimal MAX_RATE = BigDecimal.valueOf(100);

    public record BondRequest(LocalDate purchaseDate, Integer quantity, BigDecimal firstYearRate, BigDecimal margin) {}

    public record SeriesRequest(BigDecimal firstYearRate, BigDecimal margin) {}

    public record CpiRequest(BigDecimal pct) {}

    public record CpiEntry(String month, BigDecimal pct) {}

    public record RatesResponse(List<EdoSeries> series, List<CpiEntry> cpi, List<String> missingCpi) {}

    private final BondRepository bonds;
    private final AccountRepository accounts;
    private final EdoSeriesSource source;
    private final Clock clock;
    private final String accountName;

    public BondController(BondRepository bonds, AccountRepository accounts, EdoSeriesSource source, Clock clock,
                          @Value("${calculo.bond-account-name:Obligacje}") String accountName) {
        this.bonds = bonds;
        this.accounts = accounts;
        this.source = source;
        this.clock = clock;
        this.accountName = accountName;
    }

    @GetMapping("/bonds")
    public List<BondPurchase> list() {
        return bonds.allPurchases();
    }

    @PostMapping("/bonds")
    @Transactional
    public ResponseEntity<?> create(@RequestBody BondRequest request) {
        validate(request);
        String code = EdoCalculator.seriesCode(request.purchaseDate());
        Optional<ResponseEntity<Map<String, Object>>> conflict = ensureSeries(code, request);
        if (conflict.isPresent()) {
            return conflict.get();
        }
        accounts.createIfMissing(new Account(BOND_ACCOUNT_ID, accountName, "bonds"));
        BondPurchase created = bonds.insertPurchase(BOND_ACCOUNT_ID, code, request.purchaseDate(), request.quantity());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/bonds/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable long id, @RequestBody BondRequest request) {
        validate(request);
        if (bonds.findPurchase(id).isEmpty()) {
            throw new NotFoundException("Nie znaleziono zakupu obligacji.");
        }
        String code = EdoCalculator.seriesCode(request.purchaseDate());
        Optional<ResponseEntity<Map<String, Object>>> conflict = ensureSeries(code, request);
        if (conflict.isPresent()) {
            return conflict.get();
        }
        bonds.updatePurchase(id, code, request.purchaseDate(), request.quantity());
        return ResponseEntity.ok(new BondPurchase(id, code, request.purchaseDate(), request.quantity()));
    }

    @DeleteMapping("/bonds/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        if (!bonds.deletePurchase(id)) {
            throw new NotFoundException("Nie znaleziono zakupu obligacji.");
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/rates")
    public RatesResponse rates() {
        Map<String, EdoSeries> series = bonds.allSeries();
        Map<YearMonth, BigDecimal> cpi = bonds.cpi();
        LocalDate today = today();
        Set<YearMonth> missing = new TreeSet<>();
        for (BondPurchase p : bonds.allPurchases()) {
            EdoSeries s = series.get(p.seriesCode());
            if (s != null && !p.purchaseDate().isAfter(today)) {
                missing.addAll(EdoCalculator.valuePerBond(p.purchaseDate(), s,
                        m -> Optional.ofNullable(cpi.get(m)), today).missingCpi());
            }
        }
        return new RatesResponse(List.copyOf(series.values()),
                cpi.entrySet().stream().map(e -> new CpiEntry(e.getKey().toString(), e.getValue())).toList(),
                missing.stream().map(YearMonth::toString).toList());
    }

    @PutMapping("/rates/series/{code}")
    public EdoSeries putSeries(@PathVariable String code, @RequestBody SeriesRequest request) {
        if (!SERIES_CODE.matcher(code).matches()) {
            throw new BadRequestException("Nieprawidłowy kod serii: " + code);
        }
        EdoSeries series = new EdoSeries(code, rate(request.firstYearRate(), "Oprocentowanie w pierwszym roku"),
                rate(request.margin(), "Marża"));
        bonds.upsertSeries(series);
        return series;
    }

    @PutMapping("/rates/cpi/{month}")
    public CpiEntry putCpi(@PathVariable String month, @RequestBody CpiRequest request) {
        YearMonth ym = parseMonth(month);
        if (request.pct() == null || request.pct().abs().compareTo(MAX_RATE) > 0) {
            throw new BadRequestException("Inflacja musi być liczbą z zakresu od -100 do 100.");
        }
        bonds.upsertCpi(ym, request.pct());
        return new CpiEntry(ym.toString(), request.pct());
    }

    @DeleteMapping("/rates/cpi/{month}")
    public ResponseEntity<Void> deleteCpi(@PathVariable String month) {
        if (!bonds.deleteCpi(parseMonth(month))) {
            throw new NotFoundException("Brak odczytu inflacji dla " + month + ".");
        }
        return ResponseEntity.noContent().build();
    }

    private void validate(BondRequest r) {
        if (r.purchaseDate() == null) {
            throw new BadRequestException("Podaj datę zakupu.");
        }
        if (r.purchaseDate().isAfter(today())) {
            throw new BadRequestException("Data zakupu nie może być z przyszłości.");
        }
        if (r.quantity() == null || r.quantity() < 1) {
            throw new BadRequestException("Liczba obligacji musi być większa od zera.");
        }
        if ((r.firstYearRate() == null) != (r.margin() == null)) {
            throw new BadRequestException("Podaj oba oprocentowania albo żadnego.");
        }
    }

    /**
     * Makes sure the series exists: already stored, given in the request, or found by the source.
     * Writes only when the series can be resolved; otherwise returns the 409 response and writes nothing.
     */
    private Optional<ResponseEntity<Map<String, Object>>> ensureSeries(String code, BondRequest request) {
        if (bonds.seriesExists(code)) {
            return Optional.empty();
        }
        Optional<EdoSeries> found;
        if (request.firstYearRate() != null) {
            found = Optional.of(new EdoSeries(code, rate(request.firstYearRate(), "Oprocentowanie w pierwszym roku"),
                    rate(request.margin(), "Marża")));
        } else {
            found = source.fetch(code);
        }
        if (found.isEmpty()) {
            return Optional.of(ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "message", "Nie znaleziono oprocentowania serii " + code + ". Podaj je ręcznie.",
                    "seriesCode", code,
                    "needsRates", true)));
        }
        bonds.upsertSeries(found.get());
        return Optional.empty();
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ClockConfig.WARSAW));
    }

    private static BigDecimal rate(BigDecimal value, String label) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX_RATE) > 0) {
            throw new BadRequestException(label + " musi być liczbą z zakresu od 0 do 100.");
        }
        return value;
    }

    private static YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Nieprawidłowy miesiąc (oczekiwano rrrr-MM): " + month);
        }
    }
}

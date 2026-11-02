package dev.calculo.demo;

import dev.calculo.ClockConfig;
import dev.calculo.account.Account;
import dev.calculo.account.AccountRepository;
import dev.calculo.bonds.BondRepository;
import dev.calculo.bonds.EdoCalculator;
import dev.calculo.bonds.EdoSeries;
import dev.calculo.prices.PriceSyncService;
import dev.calculo.xtb.LedgerRepository;
import dev.calculo.xtb.ParsedExport.CashOperation;
import dev.calculo.xtb.ParsedExport.OperationType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fills an empty database with a fictional portfolio, relative to today, and loads fictional prices.
 * Idempotent: it seeds only when there are no accounts at all, so it never touches real data.
 */
@Component
@Profile("demo")
class DemoDataSeeder implements ApplicationRunner {
    static final String IKE = "demo:IKE";
    static final String TRADES = "demo:Trades";
    private static final String BONDS = "bonds";
    private static final int HISTORY_DAYS = 300;

    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final BondRepository bonds;
    private final PriceSyncService priceSync;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String bondAccountName;
    private long nextId = 1;

    DemoDataSeeder(AccountRepository accounts, LedgerRepository ledger, BondRepository bonds,
                   PriceSyncService priceSync, TransactionTemplate tx, Clock clock,
                   @Value("${calculo.bond-account-name:Obligacje}") String bondAccountName) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.bonds = bonds;
        this.priceSync = priceSync;
        this.tx = tx;
        this.clock = clock;
        this.bondAccountName = bondAccountName;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (accounts.all().isEmpty()) tx.executeWithoutResult(status -> seed());
        // Fake providers only fill the demo data: never run against a database that holds something else.
        if (accounts.all().stream().anyMatch(a -> IKE.equals(a.id()))) priceSync.sync();
    }

    private void seed() {
        LocalDate start = LocalDate.now(clock.withZone(ClockConfig.WARSAW)).minusDays(HISTORY_DAYS);
        nextId = 1;

        accounts.createIfMissing(new Account(IKE, "Konto demo IKE", "demo"));
        ledger.insertInstrumentIfMissing("AAA.DE", "Alpha ETF (demo)", "ETF");
        ledger.insertInstrumentIfMissing("BBB.PL", "Beta SA (demo)", "Stock");
        deposit(IKE, start, 6000);
        buy(IKE, "AAA.DE", start.plusDays(1), 10);
        deposit(IKE, start.plusDays(90), 3000);
        buy(IKE, "BBB.PL", start.plusDays(95), 40);
        buy(IKE, "AAA.DE", start.plusDays(150), 5);
        deposit(IKE, start.plusDays(200), 2000);
        buy(IKE, "BBB.PL", start.plusDays(205), 20);

        accounts.createIfMissing(new Account(TRADES, "Konto demo zwykłe", "demo"));
        ledger.insertInstrumentIfMissing("CCC.PL", "Gamma ETF (demo)", "ETF");
        deposit(TRADES, start.plusDays(30), 4000);
        buy(TRADES, "CCC.PL", start.plusDays(32), 15);
        deposit(TRADES, start.plusDays(120), 2000);
        buy(TRADES, "CCC.PL", start.plusDays(125), 10);

        accounts.createIfMissing(new Account(BONDS, bondAccountName, "bonds"));
        edo(start.plusDays(60), 20, "5.60", "2.00");
        edo(start.plusDays(180), 10, "5.90", "2.00");
    }

    private void deposit(String account, LocalDate day, int amount) {
        ledger.insertOperation(account, new CashOperation("demo-" + nextId++, OperationType.DEPOSIT, null, null, null,
                instant(day), BigDecimal.valueOf(amount), null, null, null, "demo"));
    }

    private void buy(String account, String ticker, LocalDate wanted, int volume) {
        LocalDate day = wanted;
        while (!DemoMarket.isTradingDay(day)) day = day.plusDays(1);
        String symbol = ticker.replace(".PL", ".WA");
        BigDecimal price = DemoMarket.close(symbol, day);
        BigDecimal fx = "EUR".equals(DemoMarket.currency(symbol)) ? DemoMarket.eurRate(day) : BigDecimal.ONE;
        BigDecimal amount = price.multiply(BigDecimal.valueOf(volume)).multiply(fx).setScale(2, RoundingMode.HALF_UP);
        long id = nextId++;
        ledger.insertOperation(account, new CashOperation("demo-" + id, OperationType.PURCHASE, ticker, null, null,
                instant(day), amount.negate(), "demo-pos-" + id, BigDecimal.valueOf(volume), price, "demo"));
    }

    private void edo(LocalDate day, int quantity, String firstYearRate, String margin) {
        String code = EdoCalculator.seriesCode(day);
        bonds.upsertSeries(new EdoSeries(code, new BigDecimal(firstYearRate), new BigDecimal(margin)));
        bonds.insertPurchase(BONDS, code, day, quantity);
    }

    private static java.time.Instant instant(LocalDate day) {
        return day.atTime(10, 0).atZone(ClockConfig.WARSAW).toInstant();
    }
}

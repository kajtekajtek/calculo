package dev.calculo.portfolio;

import dev.calculo.ClockConfig;
import dev.calculo.account.Account;
import dev.calculo.account.AccountRepository;
import dev.calculo.bonds.BondRepository;
import dev.calculo.portfolio.PortfolioView.PositionDetail;
import dev.calculo.prices.MarketDataRepository;
import dev.calculo.prices.PriceSyncService;
import dev.calculo.xtb.LedgerRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PortfolioService {
    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final BondRepository bonds;
    private final MarketDataRepository marketData;
    private final PriceSyncService priceSync;
    private final Clock clock;

    public PortfolioService(AccountRepository accounts, LedgerRepository ledger, BondRepository bonds,
                            MarketDataRepository marketData, PriceSyncService priceSync, Clock clock) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.bonds = bonds;
        this.marketData = marketData;
        this.priceSync = priceSync;
        this.clock = clock;
    }

    public List<Account> accounts() {
        return accounts.all();
    }

    @Transactional(readOnly = true)
    public PortfolioView view(String accountFilter) {
        return PortfolioCalculator.view(inputs(), accountFilter);
    }

    @Transactional(readOnly = true)
    public PositionDetail detail(String key, String accountFilter) {
        return PortfolioCalculator.detail(inputs(), accountFilter, key);
    }

    private PortfolioInputs inputs() {
        // One transaction keeps the reads consistent with each other.
        LocalDate today = LocalDate.now(clock.withZone(ClockConfig.WARSAW));
        return new PortfolioInputs(accounts.all(), ledger.allOperations(), bonds.allPurchases(), bonds.allSeries(),
                bonds.cpi(), marketData.load(), ledger.snapshots(), ledger.instruments(), priceSync.syncStatus(),
                today);
    }
}

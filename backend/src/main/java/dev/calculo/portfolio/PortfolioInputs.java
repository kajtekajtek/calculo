package dev.calculo.portfolio;

import dev.calculo.account.Account;
import dev.calculo.bonds.BondPurchase;
import dev.calculo.bonds.EdoSeries;
import dev.calculo.prices.MarketData;
import dev.calculo.xtb.LedgerRepository.InstrumentRow;
import dev.calculo.xtb.LedgerRepository.OperationRow;
import dev.calculo.xtb.LedgerRepository.SnapshotRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/** Everything {@link PortfolioCalculator} needs; {@code syncStatus} maps a failed ticker or "FX:code" to its error. */
public record PortfolioInputs(List<Account> accounts, List<OperationRow> operations, List<BondPurchase> bonds,
                              Map<String, EdoSeries> series, Map<YearMonth, BigDecimal> cpi, MarketData market,
                              List<SnapshotRow> snapshots, List<InstrumentRow> instruments,
                              Map<String, String> syncStatus, LocalDate today) {}

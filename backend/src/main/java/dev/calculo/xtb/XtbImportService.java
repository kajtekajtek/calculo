package dev.calculo.xtb;

import dev.calculo.account.Account;
import dev.calculo.account.AccountRepository;
import dev.calculo.xtb.ParsedExport.CashOperation;
import dev.calculo.xtb.ParsedExport.OperationType;
import dev.calculo.xtb.ParsedExport.ParsedAccount;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class XtbImportService {
    static final BigDecimal TOLERANCE = new BigDecimal("0.0001");

    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    public XtbImportService(AccountRepository accounts, LedgerRepository ledger, TransactionTemplate tx,
                            ApplicationEventPublisher events) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.tx = tx;
        this.events = events;
    }

    /** Computes what a commit would do, without writing anything. */
    public ImportPreview preview(String filename, InputStream in) {
        return summarize(XtbExportParser.parse(filename, in)).preview();
    }

    /** Stores the export in one transaction. Nothing is stored when the export has problems. */
    public ImportPreview commit(String filename, InputStream in) {
        ParsedExport parsed = XtbExportParser.parse(filename, in);
        ImportPreview preview = tx.execute(status -> {
            Summary summary = summarize(parsed);
            if (!summary.preview().canSave()) return summary.preview();
            for (ParsedAccount account : parsed.accounts()) store(account);
            return summary.preview();
        });
        if (preview.canSave()) events.publishEvent(new InstrumentsChanged());
        return preview;
    }

    private void store(ParsedAccount account) {
        String id = accountId(account);
        accounts.createIfMissing(new Account(id, accountName(account.product()), "XTB"));
        for (CashOperation op : account.operations()) {
            if (op.ticker() != null && !op.ticker().isBlank()) {
                ledger.insertInstrumentIfMissing(op.ticker(), op.instrumentName(), op.category());
            }
            ledger.insertOperation(id, op);
        }
        ledger.replaceSnapshot(id, account.openPositions(), account.reportedAt());
    }

    private record Summary(ImportPreview preview) {}

    private Summary summarize(ParsedExport parsed) {
        int fresh = 0;
        int duplicates = 0;
        List<String> warnings = new ArrayList<>();
        for (ParsedAccount account : parsed.accounts()) {
            String id = accountId(account);
            Set<String> known = ledger.externalIds(id);
            Set<String> seen = new HashSet<>();
            Map<String, BigDecimal> purchased = new LinkedHashMap<>();
            ledger.storedPurchaseVolumes(id).forEach(v -> purchased.put(v.ticker(), v.volume()));
            for (CashOperation op : account.operations()) {
                if (known.contains(op.externalId()) || !seen.add(op.externalId())) {
                    duplicates++;
                    continue;
                }
                fresh++;
                if (op.type() == OperationType.PURCHASE && op.volume() != null && op.ticker() != null) {
                    purchased.merge(op.ticker(), op.volume(), BigDecimal::add);
                }
            }
            reconcile(account, purchased, warnings);
        }
        boolean canSave = parsed.problems().isEmpty();
        return new Summary(new ImportPreview(fresh, duplicates, parsed.problems(), warnings, canSave));
    }

    private static void reconcile(ParsedAccount account, Map<String, BigDecimal> purchased, List<String> warnings) {
        Map<String, BigDecimal> reported = new LinkedHashMap<>();
        account.openPositions().forEach(p -> reported.merge(p.ticker(), p.volume(), BigDecimal::add));
        Set<String> tickers = new java.util.TreeSet<>(reported.keySet());
        tickers.addAll(purchased.keySet());
        for (String ticker : tickers) {
            BigDecimal bought = purchased.getOrDefault(ticker, BigDecimal.ZERO);
            BigDecimal open = reported.getOrDefault(ticker, BigDecimal.ZERO);
            if (bought.subtract(open).abs().compareTo(TOLERANCE) > 0) {
                warnings.add("Konto " + accountName(account.product()) + ", " + ticker + ": zakupy dają "
                        + bought.stripTrailingZeros().toPlainString() + " szt., a Open Positions pokazuje "
                        + open.stripTrailingZeros().toPlainString() + " szt.");
            }
        }
    }

    static String accountId(ParsedAccount account) {
        return "xtb:" + account.product();
    }

    static String accountName(String product) {
        return switch (product) {
            case "IKE" -> "IKE XTB";
            case "My Trades" -> "Zwykłe XTB";
            default -> product;
        };
    }
}

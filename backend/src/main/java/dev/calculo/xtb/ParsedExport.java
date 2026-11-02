package dev.calculo.xtb;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Result of parsing an XTB export: one account per XLSX, plus rows that could not be understood. */
public record ParsedExport(List<ParsedAccount> accounts, List<ImportProblem> problems) {

    public enum OperationType { DEPOSIT, PURCHASE, DIVIDEND, INTEREST, INTEREST_TAX }

    public record CashOperation(String externalId, OperationType type, String ticker, String instrumentName,
                                String category, Instant occurredAt, BigDecimal amountPln, String positionId,
                                BigDecimal volume, BigDecimal price, String comment) {}

    public record OpenPosition(String ticker, BigDecimal volume, BigDecimal valuePln) {}

    public record ParsedAccount(String product, List<CashOperation> operations, List<OpenPosition> openPositions,
                                Instant reportedAt) {}

    public record ImportProblem(String file, String sheet, int row, String message) {}
}

package dev.calculo.xtb;

import dev.calculo.xtb.ParsedExport.CashOperation;
import dev.calculo.xtb.ParsedExport.OperationType;
import dev.calculo.xtb.ParsedExport.ParsedAccount;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XtbExportParserTest {

    private static final double T = 46100.25;
    private static final String DUMMY_PDF = "%PDF-1.7 not a spreadsheet";

    private static ParsedExport parse(String name, byte[] bytes) {
        return XtbExportParser.parse(name, new ByteArrayInputStream(bytes));
    }

    private static XtbFixture ike() {
        return XtbFixture.account("1111111");
    }

    private static CashOperation only(ParsedExport p) {
        assertThat(p.accounts()).hasSize(1);
        assertThat(p.accounts().get(0).operations()).hasSize(1);
        return p.accounts().get(0).operations().get(0);
    }

    @Test
    void parsesPurchaseWithFractionalCommentAndSerialTime() {
        byte[] file = ike().cash("Stock purchase", "Alpha ETF", "AAA.DE", "ETF", T, -150.5, 1001L,
                "OPEN BUY 1/1.2500 @ 50.25", "IKE", 555L).build();
        CashOperation op = only(parse("a.xlsx", file));
        assertThat(op.type()).isEqualTo(OperationType.PURCHASE);
        assertThat(op.volume()).isEqualByComparingTo("1");
        assertThat(op.price()).isEqualByComparingTo("50.25");
        assertThat(op.externalId()).isEqualTo("1001");
        assertThat(op.positionId()).isEqualTo("555");
        assertThat(op.ticker()).isEqualTo("AAA.DE");
        assertThat(op.instrumentName()).isEqualTo("Alpha ETF");
        assertThat(op.category()).isEqualTo("ETF");
        assertThat(op.amountPln()).isEqualByComparingTo("-150.5");
        assertThat(op.occurredAt().toString()).startsWith("2026-03-19T06:00:00");
    }

    @Test
    void parsesPlainPurchaseComment() {
        byte[] file = ike().cash("Stock purchase", "Beta", "BBB.PL", "STOCK", T, -10.0, 1L,
                "OPEN BUY 0.2500 @ 200.40", "IKE", 7L).build();
        CashOperation op = only(parse("a.xlsx", file));
        assertThat(op.volume()).isEqualByComparingTo("0.2500");
        assertThat(op.price()).isEqualByComparingTo("200.40");
    }

    @Test
    void parsesPurchaseCommentWithTickerBeforeVolume() {
        byte[] file = ike().cash("Stock purchase", "Beta", "BBB.PL", "STOCK", T, -10.0, 1L,
                "OPEN BUY BBB.PL 2.5 @ 33.10", "IKE", 7L).build();
        CashOperation op = only(parse("a.xlsx", file));
        assertThat(op.volume()).isEqualByComparingTo("2.5");
        assertThat(op.price()).isEqualByComparingTo("33.10");
    }

    @Test
    void rowsSharingPositionIdSumToTotalVolume() {
        byte[] file = ike()
                .cash("Stock purchase", "Alpha ETF", "AAA.DE", "ETF", T, -100.0, 1L, "OPEN BUY 1/1.2500 @ 50.25", "IKE", 9L)
                .cash("Stock purchase", "Alpha ETF", "AAA.DE", "ETF", T, -50.0, 2L, "OPEN BUY 0.2500/1.2500 @ 50.25", "IKE", 9L)
                .build();
        ParsedAccount acc = parse("a.xlsx", file).accounts().get(0);
        BigDecimal sum = acc.operations().stream().map(CashOperation::volume).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("1.2500");
        assertThat(acc.operations()).extracting(CashOperation::positionId).containsOnly("9");
    }

    @Test
    void timeSerialConvertsToUtcInstant() {
        // serial 25569 is the Unix epoch
        byte[] file = ike().cash("Deposit", "", "", "", 25569.5, 100.0, 1L, "", "IKE", null).build();
        assertThat(only(parse("a.xlsx", file)).occurredAt()).isEqualTo(Instant.parse("1970-01-01T12:00:00Z"));
    }

    @Test
    void mapsOperationTypes() {
        byte[] file = ike()
                .cash("Deposit", "", "", "", T, 1.0, 1L, "", "IKE", null)
                .cash("IKE deposit", "", "", "", T, 2.0, 2L, "", "IKE", null)
                .cash("Dividend", "Alpha", "AAA.DE", "ETF", T, 3.0, 3L, "AAA.DE dividend", "IKE", null)
                .cash("Free funds interest", "", "", "", T, 0.4, 4L, "interest", "IKE", null)
                .cash("Free funds interest tax", "", "", "", T, -0.1, 5L, "tax", "IKE", null)
                .build();
        ParsedExport p = parse("a.xlsx", file);
        assertThat(p.problems()).isEmpty();
        assertThat(p.accounts().get(0).operations()).extracting(CashOperation::type).containsExactly(
                OperationType.DEPOSIT, OperationType.DEPOSIT, OperationType.DIVIDEND,
                OperationType.INTEREST, OperationType.INTEREST_TAX);
    }

    @Test
    void footerRowsWithoutTimeAreSkippedSilently() {
        byte[] file = ike().cash("Deposit", "", "", "", T, 1.0, 1L, "", "IKE", null)
                .cashFooter("Total", 123.45).build();
        ParsedExport p = parse("a.xlsx", file);
        assertThat(p.problems()).isEmpty();
        assertThat(p.accounts().get(0).operations()).hasSize(1);
    }

    @Test
    void timelessWithdrawalRowIsAProblem() {
        byte[] file = ike().cash("Withdrawal", "", "", "", null, -5.0, 1L, "", "IKE", null).build();
        ParsedExport p = parse("a.xlsx", file);
        assertThat(p.problems()).hasSize(1);
        assertThat(p.problems().get(0).row()).isEqualTo(6);
        assertThat(p.accounts().get(0).operations()).isEmpty();
    }

    @Test
    void timelessUnknownTypeWithoutIdIsAProblem() {
        byte[] file = ike().cash("Withdrawal", "", "", "", null, -5.0, null, "", "IKE", null).build();
        ParsedExport p = parse("a.xlsx", file);
        assertThat(p.problems()).hasSize(1);
    }

    @Test
    void timelessPurchaseRowIsAProblem() {
        byte[] file = ike().cash("Stock purchase", "Alpha", "AAA.DE", "ETF", null, -5.0, 1L,
                "OPEN BUY 1 @ 10.00", "IKE", 3L).build();
        ParsedExport p = parse("a.xlsx", file);
        assertThat(p.problems()).hasSize(1);
        assertThat(p.problems().get(0).message()).containsIgnoringCase("czas");
        assertThat(p.accounts().get(0).operations()).isEmpty();
    }

    @Test
    void openPositionsKeepAggregateRowsOnlyAndReadReportTime() {
        Instant at = Instant.parse("2026-11-02T10:00:00Z");
        byte[] file = ike().reportedAt(at)
                .cash("Deposit", "", "", "", T, 1.0, 1L, "", "IKE", null)
                .openAggregate("IKE", "Alpha ETF", "AAA.DE", "ETF", 1.2500, 125.5)
                .openDetail("IKE", 111L, "AAA.DE", 1.0, 100.0)
                .openDetail("IKE", 112L, "AAA.DE", 0.2500, 60.5)
                .openAggregate("IKE", "Beta SA", "BBB.PL", "STOCK", 3, 300)
                .build();
        ParsedAccount acc = parse("a.xlsx", file).accounts().get(0);
        assertThat(acc.openPositions()).hasSize(2);
        assertThat(acc.openPositions().get(0).ticker()).isEqualTo("AAA.DE");
        assertThat(acc.openPositions().get(0).volume()).isEqualByComparingTo("1.2500");
        assertThat(acc.openPositions().get(0).valuePln()).isEqualByComparingTo("125.5");
        assertThat(acc.openPositions().get(1).ticker()).isEqualTo("BBB.PL");
        assertThat(acc.reportedAt()).isEqualTo(at);
    }

    @Test
    void productComesFromDataNotFromAccountNumber() {
        byte[] file = XtbFixture.account("2222222")
                .cash("Deposit", "", "", "", T, 1.0, 1L, "", "IKE", null)
                .openAggregate("IKE", "Alpha ETF", "AAA.DE", "ETF", 1, 100).build();
        assertThat(parse("11111111_x.xlsx", file).accounts().get(0).product()).isEqualTo("IKE");
    }

    @Test
    void zipWithTwoWorkbooksGivesTwoAccounts() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("one.xlsx", ike().cash("Deposit", "", "", "", T, 1.0, 1L, "", "IKE", null).build());
        files.put("two.xlsx", XtbFixture.account("3333333").cash("Deposit", "", "", "", T, 1.0, 1L, "", "My Trades", null).build());
        ParsedExport p = parse("export.zip", XtbFixture.zip(files));
        assertThat(p.accounts()).extracting(ParsedAccount::product).containsExactly("IKE", "My Trades");
    }

    @Test
    void singleXlsxGivesOneAccount() {
        byte[] file = ike().cash("Deposit", "", "", "", T, 1.0, 1L, "", "IKE", null).build();
        assertThat(parse("one.xlsx", file).accounts()).hasSize(1);
    }

    @Test
    void unknownTypeBecomesProblemWithRowNumberAndRestIsParsed() {
        byte[] file = ike()
                .cash("Deposit", "", "", "", T, 1.0, 1L, "", "IKE", null)
                .cash("Stock sale", "Alpha", "AAA.DE", "ETF", T, 5.0, 2L, "CLOSE BUY 1 @ 10", "IKE", 3L)
                .cash("Deposit", "", "", "", T, 2.0, 3L, "", "IKE", null)
                .build();
        ParsedExport p = parse("a.xlsx", file);
        assertThat(p.accounts().get(0).operations()).hasSize(2);
        assertThat(p.problems()).hasSize(1);
        assertThat(p.problems().get(0).row()).isEqualTo(7);
        assertThat(p.problems().get(0).sheet()).isEqualTo("Cash Operations");
        assertThat(p.problems().get(0).file()).isEqualTo("a.xlsx");
        assertThat(p.problems().get(0).message()).contains("Stock sale");
    }

    @Test
    void withdrawalIsAProblem() {
        byte[] file = ike().cash("Withdrawal", "", "", "", T, -5.0, 1L, "", "IKE", null).build();
        ParsedExport p = parse("a.xlsx", file);
        assertThat(p.problems()).hasSize(1);
        assertThat(p.accounts().get(0).operations()).isEmpty();
    }

    @Test
    void nonEmptyClosedPositionsIsAProblem() {
        byte[] file = ike().cash("Deposit", "", "", "", T, 1.0, 1L, "", "IKE", null)
                .closedRow("Alpha ETF", "AAA.DE", "ETF", 1).build();
        ParsedExport p = parse("a.xlsx", file);
        assertThat(p.problems()).hasSize(1);
        assertThat(p.problems().get(0).sheet()).isEqualTo("Closed Positions");
        assertThat(p.problems().get(0).message()).containsIgnoringCase("sprzedaż nie jest jeszcze obsługiwana");
    }

    @Test
    void emptyClosedPositionsIsNotAProblem() {
        byte[] file = ike().cash("Deposit", "", "", "", T, 1.0, 1L, "", "IKE", null).build();
        assertThat(parse("a.xlsx", file).problems()).isEmpty();
    }

    @Test
    void unreadablePurchaseCommentIsAProblem() {
        byte[] file = ike().cash("Stock purchase", "Alpha", "AAA.DE", "ETF", T, -1.0, 1L, "garbled", "IKE", 1L).build();
        ParsedExport p = parse("a.xlsx", file);
        assertThat(p.problems()).hasSize(1);
        assertThat(p.accounts().get(0).operations()).isEmpty();
    }

    @Test
    void pdfBytesAreRejected() {
        assertThatThrownBy(() -> parse("a.pdf", DUMMY_PDF.getBytes()))
                .isInstanceOf(XtbFormatException.class);
    }

    @Test
    void zipWithoutXlsxIsRejected() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("notes.txt", "hello".getBytes());
        assertThatThrownBy(() -> parse("a.zip", XtbFixture.zip(files)))
                .isInstanceOf(XtbFormatException.class).hasMessageContaining("XLSX");
    }

    @Test
    void workbookWithoutCashOperationsIsRejected() {
        byte[] file = ike().withoutSheet("Cash Operations").build();
        assertThatThrownBy(() -> parse("a.xlsx", file))
                .isInstanceOf(XtbFormatException.class).hasMessageContaining("Cash Operations");
    }

    @Test
    void zipWithMoreThanTenFilesIsRejected() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (int i = 0; i < 11; i++) files.put("f" + i + ".txt", "x".getBytes());
        assertThatThrownBy(() -> parse("a.zip", XtbFixture.zip(files)))
                .isInstanceOf(XtbFormatException.class);
    }

    @Test
    void zipUnpackingToMoreThan100MbIsRejected() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("big.bin", new byte[101 * 1024 * 1024]);
        assertThatThrownBy(() -> parse("a.zip", XtbFixture.zip(files)))
                .isInstanceOf(XtbFormatException.class);
    }
}

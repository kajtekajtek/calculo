package dev.calculo.xtb;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Builds synthetic XTB export files (XLSX and ZIP) in memory. Reused by other test classes. */
public final class XtbFixture {

    private static final Instant EXCEL_EPOCH = Instant.parse("1899-12-30T00:00:00Z");

    private static final String[] CASH_HEADER =
            {"Type", "Instrument", "Ticker", "Category", "Time", "Amount", "ID", "Comment", "Product", "Position ID"};
    private static final String[] OPEN_HEADER = {"Product", "Instrument/Position", "Ticker", "Category", "Type",
            "Volume", "Value", "Current price", "Open price", "Open time (UTC)", "Stop Loss", "Take Profit",
            "Net Profit %", "Net Profit", "Gross Profit", "Margin", "Open Commission", "Swap", "Rollover"};
    private static final String[] CLOSED_HEADER =
            {"Instrument", "Ticker", "Category", "Type", "Volume", "Open time (UTC)", "Close time (UTC)"};

    private final String accountNumber;
    private final List<Object[]> cashRows = new ArrayList<>();
    private final List<Object[]> openRows = new ArrayList<>();
    private final List<Object[]> closedRows = new ArrayList<>();
    private final Set<String> omittedSheets = new HashSet<>();
    private double reportedAt = serial(Instant.parse("2026-11-02T10:00:00Z"));

    private XtbFixture(String accountNumber) {
        this.accountNumber = accountNumber;
    }

    public static XtbFixture account(String accountNumber) {
        return new XtbFixture(accountNumber);
    }

    /** Excel serial number (UTC) for an instant. */
    public static double serial(Instant instant) {
        return (instant.toEpochMilli() - EXCEL_EPOCH.toEpochMilli()) / 86_400_000d;
    }

    /** A Cash Operations row. {@code time == null} produces a footer row without a timestamp. */
    public XtbFixture cash(String type, String instrument, String ticker, String category, Double time,
                           Double amount, Long id, String comment, String product, Long positionId) {
        cashRows.add(new Object[]{type, instrument, ticker, category, time, amount, id, comment, product, positionId});
        return this;
    }

    /** A footer row as in real exports: label in Type, a summed Amount, no Time and no ID. */
    public XtbFixture cashFooter(String label, double amount) {
        cashRows.add(new Object[]{label, null, null, null, null, amount});
        return this;
    }

    /** An aggregate Open Positions row (Category filled, Type empty). */
    public XtbFixture openAggregate(String product, String name, String ticker, String category,
                                    double volume, double value) {
        openRows.add(new Object[]{product, name, ticker, category, null, volume, value});
        return this;
    }

    /** A per-position BUY detail row of Open Positions. */
    public XtbFixture openDetail(String product, long positionId, String ticker, double volume, double value) {
        openRows.add(new Object[]{product, (double) positionId, ticker, null, "BUY", volume, value});
        return this;
    }

    public XtbFixture closedRow(String instrument, String ticker, String category, double volume) {
        closedRows.add(new Object[]{instrument, ticker, category, "BUY", volume});
        return this;
    }

    public XtbFixture reportedAt(Instant instant) {
        this.reportedAt = serial(instant);
        return this;
    }

    public XtbFixture withoutSheet(String name) {
        omittedSheets.add(name);
        return this;
    }

    public byte[] build() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!omittedSheets.contains("Closed Positions")) {
                Sheet s = wb.createSheet("Closed Positions");
                meta(s, "Closed Positions");
                fill(s, 4, CLOSED_HEADER, closedRows);
                if (closedRows.isEmpty()) s.createRow(5).createCell(0).setCellValue("Profit/loss");
            }
            if (!omittedSheets.contains("Cash Operations")) {
                Sheet s = wb.createSheet("Cash Operations");
                meta(s, "Cash Operations");
                fill(s, 4, CASH_HEADER, cashRows);
            }
            if (!omittedSheets.contains("Open Positions")) {
                Sheet s = wb.createSheet("Open Positions");
                s.createRow(0).createCell(0).setCellValue("Account number");
                s.getRow(0).createCell(1).setCellValue(accountNumber + "0");
                s.createRow(1).createCell(0).setCellValue("Open Positions");
                Row r3 = s.createRow(2);
                r3.createCell(0).setCellValue("Data as of report generated");
                r3.createCell(1).setCellValue(reportedAt);
                Row r4 = s.createRow(3);
                String[] metrics = {"Product", "Metric", "Amount", "Currency"};
                for (int i = 0; i < metrics.length; i++) r4.createCell(i).setCellValue(metrics[i]);
                s.createRow(6).createCell(0).setCellValue("Note");
                fill(s, 8, OPEN_HEADER, openRows);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Zips the given files (name to bytes) in the given order. */
    public static byte[] zip(Map<String, byte[]> files) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(f.getKey()));
                zip.write(f.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Rewrites a ZIP (an XLSX is one) the way real XTB exports are written: each entry is followed by a data
     * descriptor whose sizes take 8 bytes, with no ZIP64 extra field announcing it. Readers that trust the central
     * directory cope; java.util.zip.ZipInputStream does not.
     */
    public static byte[] withWideDataDescriptors(byte[] zip) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) entries.put(e.getName(), in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ByteArrayOutputStream central = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            byte[] name = e.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] data = e.getValue();
            CRC32 crc = new CRC32();
            crc.update(data);
            Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream packed = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            while (!deflater.finished()) packed.write(chunk, 0, deflater.deflate(chunk));
            deflater.end();
            byte[] compressed = packed.toByteArray();
            int offset = body.size();
            // Local header: sizes and CRC deferred to the data descriptor (flag bit 3).
            body.writeBytes(le(30).putInt(0x04034b50).putShort((short) 20).putShort((short) 8).putShort((short) 8)
                    .putInt(0).putInt(0).putInt(0).putInt(0).putShort((short) name.length).putShort((short) 0).array());
            body.writeBytes(name);
            body.writeBytes(compressed);
            body.writeBytes(le(24).putInt(0x08074b50).putInt((int) crc.getValue())
                    .putLong(compressed.length).putLong(data.length).array());
            central.writeBytes(le(46).putInt(0x02014b50).putShort((short) 20).putShort((short) 20).putShort((short) 8)
                    .putShort((short) 8).putInt(0).putInt((int) crc.getValue()).putInt(compressed.length)
                    .putInt(data.length).putShort((short) name.length).putShort((short) 0).putShort((short) 0)
                    .putShort((short) 0).putShort((short) 0).putInt(0).putInt(offset).array());
            central.writeBytes(name);
        }
        int centralOffset = body.size();
        body.writeBytes(central.toByteArray());
        body.writeBytes(le(22).putInt(0x06054b50).putShort((short) 0).putShort((short) 0)
                .putShort((short) entries.size()).putShort((short) entries.size()).putInt(central.size())
                .putInt(centralOffset).putShort((short) 0).array());
        return body.toByteArray();
    }

    private static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private void meta(Sheet s, String title) {
        s.createRow(0).createCell(0).setCellValue("Account number");
        s.getRow(0).createCell(1).setCellValue(Double.parseDouble(accountNumber));
        s.createRow(1).createCell(0).setCellValue(title);
        s.createRow(2).createCell(0).setCellValue("Date from (UTC)");
        s.createRow(3).createCell(0).setCellValue("Date to (UTC)");
    }

    private static void fill(Sheet s, int headerRow, String[] header, List<Object[]> rows) {
        Row h = s.createRow(headerRow);
        for (int i = 0; i < header.length; i++) h.createCell(i).setCellValue(header[i]);
        int r = headerRow + 1;
        for (Object[] values : rows) {
            Row row = s.createRow(r++);
            for (int i = 0; i < values.length; i++) {
                Object v = values[i];
                if (v == null) continue;
                if (v instanceof String str) row.createCell(i).setCellValue(str);
                else row.createCell(i).setCellValue(((Number) v).doubleValue());
            }
        }
    }
}

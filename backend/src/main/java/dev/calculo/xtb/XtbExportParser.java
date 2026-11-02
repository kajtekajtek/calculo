package dev.calculo.xtb;

import dev.calculo.xtb.ParsedExport.CashOperation;
import dev.calculo.xtb.ParsedExport.ImportProblem;
import dev.calculo.xtb.ParsedExport.OpenPosition;
import dev.calculo.xtb.ParsedExport.OperationType;
import dev.calculo.xtb.ParsedExport.ParsedAccount;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Parses XTB account exports (a ZIP of XLSX files or a single XLSX) into plain records. No I/O beyond the stream. */
public final class XtbExportParser {

    static final int MAX_ZIP_FILES = 10;
    static final long MAX_UNPACKED_BYTES = 100L * 1024 * 1024;

    private static final Instant EXCEL_EPOCH = Instant.parse("1899-12-30T00:00:00Z");
    // The ticker between "BUY" and the volume is optional: some exports include it.
    private static final Pattern PURCHASE_COMMENT =
            Pattern.compile("OPEN BUY (?:[A-Za-z][^\\s]* )?(\\d+(?:\\.\\d+)?)(?:/\\d+(?:\\.\\d+)?)? @ (\\d+(?:\\.\\d+)?)");
    private static final int HEADER_SEARCH_ROWS = 40;

    private XtbExportParser() {}

    public static ParsedExport parse(String filename, InputStream in) {
        Map<String, byte[]> workbooks = unpack(filename, in);
        List<ParsedAccount> accounts = new ArrayList<>();
        List<ImportProblem> problems = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : workbooks.entrySet()) {
            accounts.add(parseWorkbook(e.getKey(), e.getValue(), problems));
        }
        return new ParsedExport(accounts, problems);
    }

    // ---- container handling ----

    private static Map<String, byte[]> unpack(String filename, InputStream in) {
        Map<String, byte[]> xlsx = new LinkedHashMap<>();
        byte[] whole;
        try {
            whole = readLimited(in);
        } catch (IOException e) {
            throw new XtbFormatException("Nie udało się odczytać pliku.", e);
        }
        long total = 0;
        int entries = 0;
        boolean singleXlsx = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(whole))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                entries++;
                String name = entry.getName();
                if (name.equals("[Content_Types].xml")) singleXlsx = true;
                boolean wanted = name.toLowerCase(Locale.ROOT).endsWith(".xlsx") && !name.startsWith("__MACOSX/");
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = zip.read(chunk)) > 0) {
                    total += n;
                    if (total > MAX_UNPACKED_BYTES) {
                        throw new XtbFormatException("Archiwum jest zbyt duże po rozpakowaniu.");
                    }
                    if (wanted) buf.write(chunk, 0, n);
                }
                if (!singleXlsx && entries > MAX_ZIP_FILES) {
                    throw new XtbFormatException("Archiwum zawiera zbyt wiele plików.");
                }
                if (wanted) xlsx.put(name, buf.toByteArray());
            }
        } catch (IOException e) {
            throw new XtbFormatException("Plik nie jest prawidłowym archiwum ZIP ani plikiem XLSX.", e);
        }
        if (singleXlsx) {
            Map<String, byte[]> one = new LinkedHashMap<>();
            one.put(filename, whole);
            return one;
        }
        if (entries == 0) {
            throw new XtbFormatException("Plik nie jest ani archiwum ZIP, ani plikiem XLSX z eksportu XTB.");
        }
        if (xlsx.isEmpty()) {
            throw new XtbFormatException("Archiwum ZIP nie zawiera plików XLSX.");
        }
        return xlsx;
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        long total = 0;
        while ((n = in.read(chunk)) > 0) {
            total += n;
            if (total > MAX_UNPACKED_BYTES) throw new XtbFormatException("Plik jest zbyt duży.");
            out.write(chunk, 0, n);
        }
        return out.toByteArray();
    }

    // ---- workbook ----

    private static ParsedAccount parseWorkbook(String file, byte[] bytes, List<ImportProblem> problems) {
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet cash = sheet(wb, "Cash Operations");
            if (cash == null) {
                throw new XtbFormatException("W pliku " + file + " brakuje arkusza Cash Operations.");
            }
            Sheet open = sheet(wb, "Open Positions");
            Sheet closed = sheet(wb, "Closed Positions");

            List<String> products = new ArrayList<>();
            List<CashOperation> operations = parseCashOperations(file, cash, products, problems);
            Instant[] reportedAt = new Instant[1];
            List<OpenPosition> positions = open == null
                    ? List.of()
                    : parseOpenPositions(file, open, products, reportedAt, problems);
            if (closed != null) checkClosedPositions(file, closed, problems);

            if (products.isEmpty()) {
                throw new XtbFormatException("W pliku " + file + " nie udało się ustalić produktu (konta).");
            }
            return new ParsedAccount(products.get(0), operations, positions, reportedAt[0]);
        } catch (IOException | RuntimeException e) {
            if (e instanceof XtbFormatException x) throw x;
            throw new XtbFormatException("Plik " + file + " nie jest prawidłowym plikiem XLSX.", e);
        }
    }

    private static Sheet sheet(Workbook wb, String name) {
        for (Sheet s : wb) {
            if (s.getSheetName().trim().equalsIgnoreCase(name)) return s;
        }
        return null;
    }

    // ---- Cash Operations ----

    private static List<CashOperation> parseCashOperations(String file, Sheet sheet, List<String> products,
                                                            List<ImportProblem> problems) {
        Row header = findHeader(sheet, "type", "time", "amount");
        if (header == null) {
            throw new XtbFormatException("W arkuszu Cash Operations nie znaleziono wiersza nagłówka.");
        }
        Map<String, Integer> col = columns(header);
        List<CashOperation> result = new ArrayList<>();
        for (int r = header.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            Double time = number(cell(row, col, "time"));
            int rowNo = r + 1;
            String product = text(cell(row, col, "product"));
            if (!product.isEmpty() && !products.contains(product)) products.add(product);
            if (time == null) {
                // Footer rows (e.g. "Total") have no ID and a footer label as type; anything else lost its time and must be reported.
                String label = text(cell(row, col, "type"));
                boolean footer = text(cell(row, col, "id")).isEmpty()
                        && (label.isEmpty() || label.equalsIgnoreCase("Total") || label.equalsIgnoreCase("Profit/loss"));
                if (footer) continue;
                problems.add(new ImportProblem(file, "Cash Operations", rowNo,
                        "Brak czasu operacji lub czas jest nieczytelny."));
                continue;
            }
            String typeName = text(cell(row, col, "type"));
            String comment = text(cell(row, col, "comment"));
            OperationType type = operationType(typeName);
            if (type == null) {
                problems.add(new ImportProblem(file, "Cash Operations", rowNo,
                        "Nieobsługiwany typ operacji: \"" + typeName + "\"."));
                continue;
            }
            Double amount = number(cell(row, col, "amount"));
            String id = text(cell(row, col, "id"));
            if (amount == null || id.isEmpty()) {
                problems.add(new ImportProblem(file, "Cash Operations", rowNo,
                        "Brak kwoty lub identyfikatora operacji."));
                continue;
            }
            BigDecimal volume = null;
            BigDecimal price = null;
            if (type == OperationType.PURCHASE) {
                Matcher m = PURCHASE_COMMENT.matcher(comment);
                if (!m.find()) {
                    problems.add(new ImportProblem(file, "Cash Operations", rowNo,
                            "Nie udało się odczytać liczby sztuk i ceny z komentarza zakupu."));
                    continue;
                }
                volume = new BigDecimal(m.group(1));
                price = new BigDecimal(m.group(2));
            }
            String positionId = text(cell(row, col, "position id"));
            result.add(new CashOperation(id, type, text(cell(row, col, "ticker")),
                    text(cell(row, col, "instrument")), text(cell(row, col, "category")),
                    toInstant(time), BigDecimal.valueOf(amount),
                    positionId.isEmpty() ? null : positionId, volume, price, comment));
        }
        return result;
    }

    private static OperationType operationType(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "deposit", "ike deposit" -> OperationType.DEPOSIT;
            case "stock purchase" -> OperationType.PURCHASE;
            case "dividend" -> OperationType.DIVIDEND;
            case "free funds interest" -> OperationType.INTEREST;
            case "free funds interest tax" -> OperationType.INTEREST_TAX;
            default -> null;
        };
    }

    // ---- Open Positions ----

    private static List<OpenPosition> parseOpenPositions(String file, Sheet sheet, List<String> products,
                                                          Instant[] reportedAt, List<ImportProblem> problems) {
        Row meta = sheet.getRow(2);
        if (meta != null) {
            Double serial = number(meta.getCell(1));
            if (serial != null) reportedAt[0] = toInstant(serial);
        }
        Row header = findHeader(sheet, "product", "instrument/position", "ticker");
        if (header == null) {
            throw new XtbFormatException("W arkuszu Open Positions nie znaleziono wiersza nagłówka.");
        }
        Map<String, Integer> col = columns(header);
        List<OpenPosition> result = new ArrayList<>();
        for (int r = header.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String product = text(cell(row, col, "product"));
            if (!product.isEmpty() && !products.contains(product)) products.add(product);
            boolean aggregate = !text(cell(row, col, "category")).isEmpty() && text(cell(row, col, "type")).isEmpty();
            if (!aggregate) continue; // individual BUY rows are covered by the aggregate row
            String ticker = text(cell(row, col, "ticker"));
            Double volume = number(cell(row, col, "volume"));
            Double value = number(cell(row, col, "value"));
            if (ticker.isEmpty() || volume == null || value == null) {
                problems.add(new ImportProblem(file, "Open Positions", r + 1,
                        "Nie udało się odczytać tickera, liczby sztuk lub wartości pozycji."));
                continue;
            }
            result.add(new OpenPosition(ticker, BigDecimal.valueOf(volume), BigDecimal.valueOf(value)));
        }
        return result;
    }

    // ---- Closed Positions ----

    private static void checkClosedPositions(String file, Sheet sheet, List<ImportProblem> problems) {
        Row header = findHeader(sheet, "instrument", "ticker", "type");
        if (header == null) return; // empty sheet: only a footer
        Map<String, Integer> col = columns(header);
        for (int r = header.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row != null && !text(cell(row, col, "ticker")).isEmpty()) {
                problems.add(new ImportProblem(file, "Closed Positions", r + 1,
                        "Sprzedaż nie jest jeszcze obsługiwana."));
            }
        }
    }

    // ---- helpers ----

    private static Row findHeader(Sheet sheet, String... required) {
        int last = Math.min(sheet.getLastRowNum(), HEADER_SEARCH_ROWS);
        for (int r = 0; r <= last; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            Map<String, Integer> cols = columns(row);
            if (cols.keySet().containsAll(List.of(required))) return row;
        }
        return null;
    }

    private static Map<String, Integer> columns(Row header) {
        Map<String, Integer> map = new HashMap<>();
        for (Cell c : header) {
            String name = text(c).toLowerCase(Locale.ROOT);
            if (!name.isEmpty()) map.putIfAbsent(name, c.getColumnIndex());
        }
        return map;
    }

    private static Cell cell(Row row, Map<String, Integer> col, String name) {
        Integer idx = col.get(name);
        return idx == null ? null : row.getCell(idx);
    }

    private static String text(Cell c) {
        if (c == null) return "";
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue().trim();
            case NUMERIC -> BigDecimal.valueOf(c.getNumericCellValue()).stripTrailingZeros().toPlainString();
            default -> "";
        };
    }

    private static Double number(Cell c) {
        if (c == null) return null;
        if (c.getCellType() == CellType.NUMERIC) return c.getNumericCellValue();
        if (c.getCellType() == CellType.STRING) {
            try {
                return Double.valueOf(c.getStringCellValue().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Instant toInstant(double excelSerial) {
        return EXCEL_EPOCH.plusMillis(Math.round(excelSerial * 86_400_000d));
    }
}

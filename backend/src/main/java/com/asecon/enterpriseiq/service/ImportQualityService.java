package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.ImportQualityDto;
import com.asecon.enterpriseiq.model.ImportJob;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ImportQualityService {
    private final Path importsRoot;
    private final long maxRows;

    private static final List<DateTimeFormatter> FLEX_DATES = List.of(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("d/M/uuuu"),
        DateTimeFormatter.ofPattern("dd/MM/uuuu"),
        DateTimeFormatter.ofPattern("d-M-uuuu"),
        DateTimeFormatter.ofPattern("dd-MM-uuuu")
    );

    public ImportQualityService(@Value("${app.storage.imports}") String importsRoot,
                                @Value("${app.quality.max-rows:100000}") long maxRows) {
        this.importsRoot = Path.of(importsRoot).toAbsolutePath().normalize();
        this.maxRows = Math.max(10_000, maxRows);
    }

    public ImportQualityDto compute(ImportJob job) throws IOException {
        if (job == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta import");
        String ref = job.getStorageRef();
        if (ref == null || ref.isBlank()) {
            throw new ResponseStatusException(HttpStatus.GONE, "Este import no tiene fichero asociado.");
        }
        if (Path.of(ref).isAbsolute()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "storageRef inválido");
        }
        Path file = importsRoot.resolve(ref).toAbsolutePath().normalize();
        if (!file.startsWith(importsRoot)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "storageRef inválido (path traversal)");
        }
        if (!Files.exists(file)) {
            throw new ResponseStatusException(HttpStatus.GONE, "El fichero del import ya no está disponible (retención de storage).");
        }

        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        YearMonth ym = parseYm(job.getPeriod());

        Stats stats = name.endsWith(".xlsx") ? scanXlsx(file, ym) : scanCsv(file, ym);
        List<ImportQualityDto.Issue> issues = buildIssues(stats, ym);
        return new ImportQualityDto(
            job.getPeriod(),
            stats.rowsNonEmpty,
            stats.rowsEmpty,
            stats.rowsParsed,
            stats.missingTxnDate,
            stats.missingAmount,
            stats.dateParseErrors,
            stats.amountParseErrors,
            stats.minDate == null ? null : stats.minDate.toString(),
            stats.maxDate == null ? null : stats.maxDate.toString(),
            stats.outsidePeriodRows,
            stats.duplicateRows,
            stats.missingCounterpartyRows,
            stats.balanceEndMismatchRows,
            issues,
            stats.examples
        );
    }

    private Stats scanCsv(Path filePath, YearMonth ym) throws IOException {
        Stats s = new Stats();
        char delimiter = detectDelimiter(filePath);
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            CSVParser parser = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(reader);

            for (CSVRecord record : parser) {
                if (s.rowsSeen >= maxRows) break;
                s.rowsSeen++;
                scanRow(
                    s,
                    safeGet(record, "txn_date"),
                    safeGet(record, "amount"),
                    safeGet(record, "description"),
                    safeGet(record, "counterparty"),
                    safeGet(record, "balance_end"),
                    ym
                );
            }
        }
        return s;
    }

    private Stats scanXlsx(Path filePath, YearMonth ym) throws IOException {
        Stats s = new Stats();
        try (InputStream is = Files.newInputStream(filePath); var wb = new XSSFWorkbook(is)) {
            Sheet sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : null;
            if (sheet == null) return s;

            DataFormatter formatter = new DataFormatter(Locale.US, true);
            int firstRow = sheet.getFirstRowNum();
            Row headerRow = sheet.getRow(firstRow);
            if (headerRow == null) return s;

            List<String> headers = new ArrayList<>();
            int lastCell = Math.min(200, Math.max(0, headerRow.getLastCellNum()));
            for (int c = 0; c < lastCell; c++) {
                String h = formatter.formatCellValue(headerRow.getCell(c));
                headers.add(normalizeHeader(h));
            }

            for (int r = firstRow + 1; r <= sheet.getLastRowNum() && s.rowsSeen < maxRows; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                String txnDateRaw = getCell(headers, row, formatter, "txn_date");
                String amountRaw = getCell(headers, row, formatter, "amount");
                String descRaw = getCell(headers, row, formatter, "description");
                String cpRaw = getCell(headers, row, formatter, "counterparty");
                String balRaw = getCell(headers, row, formatter, "balance_end");

                s.rowsSeen++;
                scanRow(s, txnDateRaw, amountRaw, descRaw, cpRaw, balRaw, ym);
            }
        }
        return s;
    }

    private static String getCell(List<String> headers, Row row, DataFormatter formatter, String key) {
        int idx = headers.indexOf(key);
        if (idx < 0) return "";
        var cell = row.getCell(idx);
        if (cell == null) return "";
        try {
            if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                var dt = cell.getLocalDateTimeCellValue();
                return dt == null ? "" : dt.toLocalDate().toString();
            }
        } catch (Exception ignored) {}
        String v = formatter.formatCellValue(cell);
        return v == null ? "" : v.trim();
    }

    private void scanRow(Stats s,
                         String txnDateRaw,
                         String amountRaw,
                         String descRaw,
                         String counterpartyRaw,
                         String balanceEndRaw,
                         YearMonth ym) {
        boolean emptyDate = txnDateRaw == null || txnDateRaw.isBlank();
        boolean emptyAmt = amountRaw == null || amountRaw.isBlank();
        if (emptyDate && emptyAmt) {
            s.rowsEmpty++;
            return;
        }

        s.rowsNonEmpty++;
        if (emptyDate) s.missingTxnDate++;
        if (emptyAmt) s.missingAmount++;

        LocalDate d = null;
        BigDecimal amt = null;
        BigDecimal bal = null;

        if (!emptyDate) {
            try {
                d = parseFlexibleDate(txnDateRaw);
                if (s.minDate == null || d.isBefore(s.minDate)) s.minDate = d;
                if (s.maxDate == null || d.isAfter(s.maxDate)) s.maxDate = d;
                if (ym != null && YearMonth.from(d).compareTo(ym) != 0) s.outsidePeriodRows++;
            } catch (Exception ex) {
                s.dateParseErrors++;
                addExample(s, "Fecha inválida: " + truncate(txnDateRaw, 24));
            }
        }

        if (!emptyAmt) {
            try {
                amt = parseAmount(amountRaw);
            } catch (Exception ex) {
                s.amountParseErrors++;
                addExample(s, "Importe inválido: " + truncate(amountRaw, 24));
            }
        }

        if (amt != null && d != null) {
            s.rowsParsed++;
            if (counterpartyRaw == null || counterpartyRaw.isBlank()) {
                s.missingCounterpartyRows++;
            }

            String dupKey = d + "|" + amt.stripTrailingZeros().toPlainString() + "|" + normalizeTiny(descRaw) + "|" + normalizeTiny(counterpartyRaw);
            if (!s.seen.add(dupKey)) {
                s.duplicateRows++;
                addExample(s, "Duplicado: " + d + " · " + amt.stripTrailingZeros().toPlainString());
            }
        }

        if (balanceEndRaw != null && !balanceEndRaw.isBlank()) {
            try {
                bal = parseAmount(balanceEndRaw);
            } catch (Exception ex) {
                // ignore balance parse errors; they are informational
                addExample(s, "Saldo inválido: " + truncate(balanceEndRaw, 24));
            }
        }

        if (bal != null) {
            if (s.prevBalanceEnd != null && amt != null) {
                BigDecimal expected = s.prevBalanceEnd.add(amt);
                if (expected.subtract(bal).abs().compareTo(new BigDecimal("0.05")) > 0) {
                    s.balanceEndMismatchRows++;
                    addExample(s, "Saldo no cuadra: prev+" + amt.stripTrailingZeros().toPlainString() + " ≠ " + bal.stripTrailingZeros().toPlainString());
                }
            }
            s.prevBalanceEnd = bal;
        }
    }

    private static List<ImportQualityDto.Issue> buildIssues(Stats s, YearMonth ym) {
        List<ImportQualityDto.Issue> out = new ArrayList<>();
        if (s.rowsNonEmpty == 0) {
            out.add(new ImportQualityDto.Issue("HIGH", "EMPTY", "No se detectaron filas válidas", "Sube un fichero con datos y cabeceras."));
            return out;
        }

        double dateErrRate = (double) s.dateParseErrors / (double) Math.max(1L, s.rowsNonEmpty);
        double amtErrRate = (double) s.amountParseErrors / (double) Math.max(1L, s.rowsNonEmpty);

        if (dateErrRate >= 0.02) {
            out.add(new ImportQualityDto.Issue(
                "HIGH",
                "DATE_PARSE",
                "Fechas con formato inválido",
                "Revisa el separador (/, -) y que no haya celdas con texto (p. ej. 'Saldo', 'TOTAL')."
            ));
        } else if (s.dateParseErrors > 0) {
            out.add(new ImportQualityDto.Issue("MEDIUM", "DATE_PARSE", "Algunas fechas no se pudieron leer", "Revisa filas con formatos raros o vacías."));
        }

        if (amtErrRate >= 0.02) {
            out.add(new ImportQualityDto.Issue(
                "HIGH",
                "AMOUNT_PARSE",
                "Importes con formato inválido",
                "Revisa separadores de miles/decimales y símbolos. Ej: '1.234,56' o '1234.56'."
            ));
        } else if (s.amountParseErrors > 0) {
            out.add(new ImportQualityDto.Issue("MEDIUM", "AMOUNT_PARSE", "Algunos importes no se pudieron leer", "Revisa filas con texto o valores extraños."));
        }

        if (s.outsidePeriodRows > 0 && ym != null) {
            out.add(new ImportQualityDto.Issue(
                "MEDIUM",
                "OUTSIDE_PERIOD",
                "Hay movimientos fuera del periodo",
                "El import está marcado como " + ym + ". Si el fichero contiene varios meses, súbelo por periodos o usa Universal."
            ));
        }

        if (s.duplicateRows > 0) {
            out.add(new ImportQualityDto.Issue("LOW", "DUPLICATES", "Posibles duplicados", "Revisa duplicados (fecha+importe+texto+contraparte)."));
        }

        if (s.balanceEndMismatchRows > 0) {
            out.add(new ImportQualityDto.Issue("LOW", "BALANCE_END", "El saldo final no cuadra en algunas filas", "Puede ser por filas intermedias/ajustes. Revisa la columna balance_end."));
        }

        double missingCpRate = (double) s.missingCounterpartyRows / (double) Math.max(1L, s.rowsParsed);
        if (s.rowsParsed > 0 && missingCpRate >= 0.5) {
            out.add(new ImportQualityDto.Issue("LOW", "COUNTERPARTY", "Falta contraparte en muchas filas", "Si puedes, incluye proveedor/cliente/beneficiario para mejores insights."));
        }

        return out;
    }

    private static String normalizeTiny(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        s = s.replaceAll("\\s+", " ");
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    private static void addExample(Stats s, String e) {
        if (s.examples.size() >= 8) return;
        if (e == null || e.isBlank()) return;
        s.examples.add(e);
    }

    private static String truncate(String raw, int max) {
        if (raw == null) return "";
        String s = raw.trim();
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static YearMonth parseYm(String period) {
        if (period == null || period.isBlank()) return null;
        try {
            return YearMonth.parse(period.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalizeHeader(String header) {
        if (header == null) return "";
        return header.trim().toLowerCase(Locale.ROOT);
    }

    private static String safeGet(CSVRecord record, String header) {
        try {
            if (!record.isMapped(header)) return "";
            String v = record.get(header);
            return v == null ? "" : v.trim();
        } catch (Exception ex) {
            return "";
        }
    }

    private static char detectDelimiter(Path filePath) {
        try {
            String first;
            try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
                first = reader.readLine();
            }
            if (first == null) first = "";
            long commas = first.chars().filter(ch -> ch == ',').count();
            long semis = first.chars().filter(ch -> ch == ';').count();
            return semis > commas ? ';' : ',';
        } catch (Exception ignored) {
            return ',';
        }
    }

    private static LocalDate parseFlexibleDate(String raw) {
        if (raw == null) throw new IllegalArgumentException("empty date");
        String s = raw.trim();
        if (s.isBlank()) throw new IllegalArgumentException("empty date");
        for (DateTimeFormatter f : FLEX_DATES) {
            try {
                return LocalDate.parse(s, f);
            } catch (DateTimeParseException ignored) {}
        }
        throw new IllegalArgumentException("invalid date: " + raw);
    }

    private static BigDecimal parseAmount(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isBlank()) return null;
        s = s.replace("€", "").replace(" ", "");

        boolean hasDot = s.contains(".");
        boolean hasComma = s.contains(",");
        if (hasDot && hasComma) {
            if (s.lastIndexOf(',') > s.lastIndexOf('.')) {
                s = s.replace(".", "").replace(",", ".");
            } else {
                s = s.replace(",", "");
            }
        } else if (hasComma) {
            s = s.replace(".", "").replace(",", ".");
        }
        BigDecimal v = new BigDecimal(s);
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static final class Stats {
        long rowsSeen;
        long rowsNonEmpty;
        long rowsEmpty;
        long rowsParsed;
        long missingTxnDate;
        long missingAmount;
        long dateParseErrors;
        long amountParseErrors;
        LocalDate minDate;
        LocalDate maxDate;
        long outsidePeriodRows;
        long duplicateRows;
        long missingCounterpartyRows;
        long balanceEndMismatchRows;
        BigDecimal prevBalanceEnd;
        final Set<String> seen = new HashSet<>();
        final List<String> examples = new ArrayList<>();
    }
}


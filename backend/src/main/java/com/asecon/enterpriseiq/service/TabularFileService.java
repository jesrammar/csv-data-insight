package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.UniversalXlsxOptionsDto;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TabularFileService {
    private static final Logger log = LoggerFactory.getLogger(TabularFileService.class);
    private static final DateTimeFormatter XLSX_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter XLSX_DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> HEADER_SEMANTIC_ALIASES = Set.of(
        "naturaleza", "tipo", "tipo partida", "categoria", "clasificacion",
        "financial nature", "row type", "classification", "line type"
    );
    private static final Set<String> HEADER_CODE_ALIASES = Set.of(
        "codigo", "cuenta", "account", "code", "concept code", "account code"
    );
    private static final Set<String> HEADER_LABEL_ALIASES = Set.of(
        "concepto", "descripcion", "description", "partida", "label", "concept", "detalle", "nombre"
    );

    public record TabularCsv(String filename,
                             byte[] bytes,
                             Charset charset,
                             boolean convertedFromXlsx,
                             UniversalXlsxOptionsDto xlsxMetadata) {}

    public record XlsxOptions(Integer sheetIndex, Integer headerRow1Based) {}

    public record XlsxPreview(List<String> sheets,
                              Integer detectedSheetIndex,
                              Integer detectedHeaderRow1Based,
                              List<String> detectedHeaders,
                              List<List<String>> sampleRows) {}

    private final int maxXlsxRows;
    private final int maxXlsxSeconds;
    private final MeterRegistry meterRegistry;

    public TabularFileService(@Value("${app.upload.xlsx.max-rows:120000}") int maxXlsxRows,
                              @Value("${app.upload.xlsx.max-seconds:25}") int maxXlsxSeconds,
                              MeterRegistry meterRegistry) {
        this.maxXlsxRows = Math.max(0, maxXlsxRows);
        this.maxXlsxSeconds = Math.max(5, maxXlsxSeconds);
        this.meterRegistry = meterRegistry;
    }

    public TabularCsv toCsv(MultipartFile file, XlsxOptions xlsxOptions) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Archivo vacio");
        }
        if (isXlsx(file)) {
            return convertXlsxToCsv(file, xlsxOptions);
        }
        String filename = file.getOriginalFilename() == null ? "data.csv" : file.getOriginalFilename();
        return new TabularCsv(filename, file.getBytes(), null, false, null);
    }

    public static boolean isXlsx(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) return true;
        String ct = file.getContentType();
        return ct != null && ct.toLowerCase(Locale.ROOT).contains("spreadsheetml");
    }

    public XlsxPreview previewXlsx(MultipartFile file, XlsxOptions xlsxOptions, int maxSampleRows) throws IOException {
        if (!isXlsx(file)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Se esperaba un XLSX");
        }
        if (maxSampleRows < 1) maxSampleRows = 1;
        if (maxSampleRows > 20) maxSampleRows = 20;

        long start = System.nanoTime();
        String resultTag = "ok";
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            if (wb.getNumberOfSheets() == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "XLSX sin hojas");
            }
            List<String> sheets = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                sheets.add(wb.getSheetName(i));
            }

            int sheetIndex = resolveSheetIndex(wb, xlsxOptions);
            Sheet sheet = wb.getSheetAt(sheetIndex);

            DataFormatter formatter = new DataFormatter(Locale.US, true);
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();

            HeaderInfo headerInfo = resolveHeader(sheet, formatter, evaluator, xlsxOptions);
            if (headerInfo == null || headerInfo.headerCount <= 0) {
                return new XlsxPreview(sheets, sheetIndex, null, List.of(), List.of());
            }

            List<List<String>> sample = new ArrayList<>();
            int emptyStreak = 0;
            for (int r = headerInfo.rowIndex + 1; r <= sheet.getLastRowNum() && sample.size() < maxSampleRows; r++) {
                if ((r % 200) == 0) checkTimeout(start);
                Row row = sheet.getRow(r);
                if (row == null) {
                    emptyStreak++;
                    if (emptyStreak >= 30) break;
                    continue;
                }
                List<String> values = new ArrayList<>(headerInfo.headerCount);
                boolean any = false;
                for (int c = 0; c < headerInfo.headerCount; c++) {
                    Cell cell = row.getCell(c);
                    String v = formatCellValueSafe(cell, formatter, evaluator);
                    v = v == null ? "" : v.trim();
                    values.add(v);
                    if (!v.isBlank()) any = true;
                }
                if (!any) {
                    emptyStreak++;
                    if (emptyStreak >= 30) break;
                    continue;
                }
                emptyStreak = 0;
                sample.add(values);
            }

            return new XlsxPreview(
                sheets,
                sheetIndex,
                headerInfo.rowIndex + 1,
                List.of(headerInfo.headers),
                sample
            );
        } catch (ResponseStatusException ex) {
            resultTag = safeErrorTag(ex);
            throw ex;
        } catch (Exception ex) {
            resultTag = "invalid";
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "XLSX invalido o no soportado");
        } finally {
            try {
                long durMs = Math.max(0L, (System.nanoTime() - start) / 1_000_000L);
                Timer.builder("ingestion.xlsx.preview.duration")
                    .tag("result", resultTag)
                    .register(meterRegistry)
                    .record(durMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                Counter.builder("ingestion.xlsx.preview.count")
                    .tag("result", resultTag)
                    .register(meterRegistry)
                    .increment();
            } catch (Exception ignored) {}
        }
    }

    private TabularCsv convertXlsxToCsv(MultipartFile file, XlsxOptions xlsxOptions) throws IOException {
        String filename = file.getOriginalFilename() == null ? "data.xlsx" : file.getOriginalFilename();
        long start = System.nanoTime();
        String resultTag = "ok";
        int dataRows = 0;
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            if (wb.getNumberOfSheets() == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "XLSX sin hojas");
            }
            int sheetIndex = resolveSheetIndex(wb, xlsxOptions);
            Sheet sheet = wb.getSheetAt(sheetIndex);
            DataFormatter formatter = new DataFormatter(Locale.US, true);
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();

            HeaderInfo headerInfo = resolveHeader(sheet, formatter, evaluator, xlsxOptions);
            if (headerInfo == null || headerInfo.headerCount <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se detectaron encabezados en el XLSX");
            }

            dataRows = Math.max(0, sheet.getLastRowNum() - headerInfo.rowIndex);
            if (maxXlsxRows > 0 && dataRows > maxXlsxRows) {
                log.warn("METRIC ingestion.xlsx.rejected filename={} sheetIndex={} dataRows={} maxRows={}", filename, sheetIndex, dataRows, maxXlsxRows);
                throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "XLSX demasiado grande (" + dataRows + " filas). Límite: " + maxXlsxRows + ". Recomendación: filtra/exporta un periodo o sube un fichero más pequeño."
                );
            }

            StringWriter writer = new StringWriter();
            try (CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setDelimiter(',').build())) {
                printer.printRecord((Object[]) headerInfo.headers);

                int emptyStreak = 0;
                for (int r = headerInfo.rowIndex + 1; r <= sheet.getLastRowNum(); r++) {
                    if ((r % 200) == 0) checkTimeout(start);
                    Row row = sheet.getRow(r);
                    if (row == null) {
                        emptyStreak++;
                        if (emptyStreak >= 30) break;
                        continue;
                    }
                    String[] values = new String[headerInfo.headerCount];
                    boolean any = false;
                    for (int c = 0; c < headerInfo.headerCount; c++) {
                        Cell cell = row.getCell(c);
                        String v = formatCellValueSafe(cell, formatter, evaluator);
                        v = v == null ? "" : v.trim();
                        values[c] = v;
                        if (!v.isBlank()) any = true;
                    }
                    if (!any) {
                        emptyStreak++;
                        if (emptyStreak >= 30) break;
                        continue;
                    }
                    emptyStreak = 0;
                    printer.printRecord((Object[]) values);
                }
            }

            byte[] bytes = writer.toString().getBytes(StandardCharsets.UTF_8);
            return new TabularCsv(
                filename.replaceAll("(?i)\\.xlsx$", ".csv"),
                bytes,
                StandardCharsets.UTF_8,
                true,
                new UniversalXlsxOptionsDto(
                    sheetIndex,
                    headerInfo.rowIndex + 1,
                    safeSheetName(wb, sheetIndex),
                    headerInfo.headers.length > 0 ? headerInfo.headers[0] : null
                )
            );
        } catch (ResponseStatusException ex) {
            resultTag = safeErrorTag(ex);
            throw ex;
        } catch (Exception ex) {
            resultTag = "invalid";
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "XLSX invalido o no soportado");
        } finally {
            try {
                long durMs = Math.max(0L, (System.nanoTime() - start) / 1_000_000L);
                Timer.builder("ingestion.xlsx.convert.duration")
                    .tag("result", resultTag)
                    .register(meterRegistry)
                    .record(durMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                DistributionSummary.builder("ingestion.xlsx.convert.rows")
                    .baseUnit("rows")
                    .register(meterRegistry)
                    .record(dataRows);
                Counter.builder("ingestion.xlsx.convert.count")
                    .tag("result", resultTag)
                    .register(meterRegistry)
                    .increment();
            } catch (Exception ignored) {}
        }
    }

    private void checkTimeout(long startNs) {
        long elapsedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
        if (elapsedMs > (long) maxXlsxSeconds * 1000L) {
            log.warn("METRIC ingestion.xlsx.timeout maxSeconds={} elapsedMs={}", maxXlsxSeconds, elapsedMs);
            try {
                Counter.builder("ingestion.xlsx.timeout.count")
                    .register(meterRegistry)
                    .increment();
            } catch (Exception ignored) {}
            throw new ResponseStatusException(
                HttpStatus.REQUEST_TIMEOUT,
                "Tiempo de análisis agotado (" + maxXlsxSeconds + "s). Recomendación: exporta menos filas/columnas o sube el fichero por periodos."
            );
        }
    }

    private static String safeErrorTag(ResponseStatusException ex) {
        if (ex == null) return "http_unknown";
        try {
            return "http_" + ex.getStatusCode().value();
        } catch (Exception ignored) {
            return "http_unknown";
        }
    }

    private record HeaderInfo(int rowIndex, int headerCount, String[] headers) {}

    private static int resolveSheetIndex(Workbook wb, XlsxOptions xlsxOptions) {
        if (xlsxOptions == null || xlsxOptions.sheetIndex == null) return 0;
        int idx = xlsxOptions.sheetIndex;
        if (idx < 0) idx = 0;
        if (idx >= wb.getNumberOfSheets()) idx = 0;
        return idx;
    }

    private static HeaderInfo resolveHeader(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator, XlsxOptions xlsxOptions) {
        if (xlsxOptions != null && xlsxOptions.headerRow1Based != null) {
            int row1 = xlsxOptions.headerRow1Based;
            if (row1 >= 1) {
                int r = row1 - 1;
                Row row = sheet.getRow(r);
                if (row != null && row.getLastCellNum() > 0) {
                    int lastCell = Math.min(row.getLastCellNum(), 200);
                    int lastNonEmptyCol = -1;
                    for (int c = 0; c < lastCell; c++) {
                        Cell cell = row.getCell(c);
                        String v = formatCellValueSafe(cell, formatter, evaluator);
                        if (v != null && !v.trim().isEmpty()) {
                            lastNonEmptyCol = c;
                        }
                    }
                    if (lastNonEmptyCol >= 0) {
                        int headerCount = lastNonEmptyCol + 1;
                        String[] headers = buildHeaders(row, headerCount, formatter, evaluator);
                        return new HeaderInfo(r, headerCount, headers);
                    }
                }
            }
        }
        return findHeader(sheet, formatter, evaluator);
    }

    private static HeaderInfo findHeader(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        int maxRow = Math.min(sheet.getLastRowNum(), 40);
        int bestRow = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        int bestLastCol = -1;

        for (int r = 0; r <= maxRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            int lastCell = row.getLastCellNum();
            if (lastCell <= 0) continue;
            lastCell = Math.min(lastCell, 200);

            int nonEmpty = 0;
            int lastNonEmptyCol = -1;
            int numericLike = 0;
            int textLike = 0;
            int monthLike = 0;
            int descriptiveLike = 0;
            int semanticLike = 0;
            int codeLike = 0;
            Set<String> distinct = new HashSet<>();
            for (int c = 0; c < lastCell; c++) {
                Cell cell = row.getCell(c);
                String v = formatCellValueSafe(cell, formatter, evaluator);
                if (v != null && !v.trim().isEmpty()) {
                    nonEmpty++;
                    lastNonEmptyCol = c;
                    String t = v.trim();
                    distinct.add(t.toLowerCase(Locale.ROOT));
                    if (looksNumericHeader(t) && !looksYearHeader(t)) numericLike++;
                    if (looksTextHeader(t) || looksYearHeader(t)) textLike++;
                    if (normalizeMonthKey(t) != null) monthLike++;
                    if (containsHeaderAlias(t, HEADER_LABEL_ALIASES)) descriptiveLike++;
                    if (containsHeaderAlias(t, HEADER_SEMANTIC_ALIASES)) semanticLike++;
                    if (containsHeaderAlias(t, HEADER_CODE_ALIASES)) codeLike++;
                }
            }

            if (nonEmpty < 2 || lastNonEmptyCol < 1) continue;

            double distinctRatio = distinct.isEmpty() ? 0.0 : (double) distinct.size() / (double) nonEmpty;
            double textRatio = (double) textLike / (double) nonEmpty;
            double numericRatio = (double) numericLike / (double) nonEmpty;
            double monthlyScore = Math.min(1.0, monthLike / 12.0);
            double descriptiveScore = Math.min(1.0, descriptiveLike / 3.0);
            double semanticScore = Math.min(1.0, (semanticLike + codeLike) / 3.0);
            double downstreamDensity = downstreamNumericDensity(sheet, r, lastNonEmptyCol + 1, formatter, evaluator);

            double score = 0.0;
            score += monthlyScore * 18.0;
            score += descriptiveScore * 8.0;
            score += semanticScore * 8.0;
            score += downstreamDensity * 7.0;
            score += distinctRatio * 5.5;
            score += textRatio * 5.0;
            score -= numericRatio * 6.5;
            score += Math.min(3.5, (double) lastNonEmptyCol / 55.0);
            if (monthLike >= 6 && descriptiveLike > 0) score += 3.0;
            if ((semanticLike > 0 || codeLike > 0) && downstreamDensity > 0.45d) score += 2.0;
            score -= r * 0.12;

            if (score > bestScore) {
                bestRow = r;
                bestScore = score;
                bestLastCol = lastNonEmptyCol;
            }
        }

        if (bestRow < 0) {
            return null;
        }

        Row headerRow = sheet.getRow(bestRow);
        int headerCount = bestLastCol + 1;
        if (headerRow == null || headerCount <= 0) return null;
        String[] headers = buildHeaders(headerRow, headerCount, formatter, evaluator);
        return new HeaderInfo(bestRow, headerCount, headers);
    }

    private static double downstreamNumericDensity(Sheet sheet,
                                                   int headerRowIndex,
                                                   int headerCount,
                                                   DataFormatter formatter,
                                                   FormulaEvaluator evaluator) {
        int inspectedRows = 0;
        int numericSlots = 0;
        int numericHits = 0;
        int lastRow = Math.min(sheet.getLastRowNum(), headerRowIndex + 12);
        for (int r = headerRowIndex + 1; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            inspectedRows++;
            for (int c = 0; c < Math.min(headerCount, 200); c++) {
                Cell cell = row.getCell(c);
                String value = formatCellValueSafe(cell, formatter, evaluator);
                if (value == null || value.trim().isEmpty()) continue;
                numericSlots++;
                if (looksNumericHeader(value) || looksYearHeader(value)) {
                    numericHits++;
                }
            }
        }
        if (inspectedRows == 0 || numericSlots == 0) return 0d;
        return (double) numericHits / (double) numericSlots;
    }

    private static boolean looksYearHeader(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (!s.matches("\\d{4}")) return false;
        try {
            int y = Integer.parseInt(s);
            return y >= 1900 && y <= 2100;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean looksNumericHeader(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return false;
        s = s.replace("€", "").replace(" ", "");
        // Amount-like values (0.00, 15,006.39, -1234,56...)
        s = s.replaceAll("[^0-9,\\.-]", "");
        if (s.isEmpty()) return false;
        // Must contain at least one digit and be mostly numeric separators
        int digits = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') digits++;
        }
        return digits >= 1;
    }

    private static boolean looksTextHeader(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) return true;
        }
        // Common header separators
        return s.contains("_") || s.contains(" ") || s.contains("-");
    }

    private static boolean containsHeaderAlias(String raw, Set<String> aliases) {
        String normalized = normalizeHeaderToken(raw);
        if (normalized.isEmpty()) return false;
        for (String alias : aliases) {
            String normalizedAlias = normalizeHeaderToken(alias);
            if (!normalizedAlias.isEmpty() && normalized.contains(normalizedAlias)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeMonthKey(String raw) {
        String normalized = normalizeHeaderToken(raw);
        if (normalized.startsWith("ene") || normalized.startsWith("jan")) return "ENERO";
        if (normalized.startsWith("feb")) return "FEBRERO";
        if (normalized.startsWith("mar")) return "MARZO";
        if (normalized.startsWith("abr") || normalized.startsWith("apr")) return "ABRIL";
        if (normalized.startsWith("may")) return "MAYO";
        if (normalized.startsWith("jun")) return "JUNIO";
        if (normalized.startsWith("jul")) return "JULIO";
        if (normalized.startsWith("ago") || normalized.startsWith("aug")) return "AGOSTO";
        if (normalized.startsWith("sep")) return "SEPTIEMBRE";
        if (normalized.startsWith("oct")) return "OCTUBRE";
        if (normalized.startsWith("nov")) return "NOVIEMBRE";
        if (normalized.startsWith("dic") || normalized.startsWith("dec")) return "DICIEMBRE";
        return null;
    }

    private static String normalizeHeaderToken(String raw) {
        if (raw == null) return "";
        String normalized = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT)
            .replace('_', ' ')
            .replace('-', ' ')
            .replaceAll("[€$£¥]", " ")
            .replaceAll("\\s+", " ")
            .trim();
        if (normalized.endsWith("s") && normalized.length() > 4) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String safeSheetName(Workbook wb, int sheetIndex) {
        try {
            if (wb == null || sheetIndex < 0 || sheetIndex >= wb.getNumberOfSheets()) return null;
            return wb.getSheetName(sheetIndex);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String[] buildHeaders(Row headerRow, int headerCount, DataFormatter formatter, FormulaEvaluator evaluator) {
        String[] headers = new String[headerCount];
        Map<String, Integer> seen = new HashMap<>();

        for (int c = 0; c < headerCount; c++) {
            Cell cell = headerRow.getCell(c);
            String raw = formatCellValueSafe(cell, formatter, evaluator);
            String h = raw == null ? "" : raw.trim();
            if (h.isEmpty()) {
                h = "col_" + (c + 1);
            }
            Integer count = seen.get(h);
            if (count == null) {
                seen.put(h, 1);
                headers[c] = h;
            } else {
                int next = count + 1;
                seen.put(h, next);
                headers[c] = h + "_" + next;
            }
        }
        return headers;
    }

    private static String formatCellValueSafe(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) return "";
        try {
            if (shouldBypassExcelFormat(cell)) {
                return rawCellValue(cell, evaluator);
            }
            return formatter.formatCellValue(cell, evaluator);
        } catch (RuntimeException ex) {
            return rawCellValue(cell, evaluator);
        }
    }

    private static boolean shouldBypassExcelFormat(Cell cell) {
        if (cell == null) return false;
        try {
            String format = cell.getCellStyle() == null ? null : cell.getCellStyle().getDataFormatString();
            if (format == null || format.isBlank()) return false;
            String normalized = format.toLowerCase(Locale.ROOT);
            if (!normalized.contains("[$")) return false;
            CellType type = cell.getCellType();
            return type == CellType.NUMERIC || type == CellType.FORMULA;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String rawCellValue(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) return "";
        try {
            return switch (cell.getCellType()) {
                case STRING -> safeText(cell.getStringCellValue());
                case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
                case NUMERIC -> formatNumericOrDate(cell);
                case FORMULA -> formatFormulaCell(cell, evaluator);
                case BLANK, ERROR, _NONE -> "";
            };
        } catch (Exception ignored) {
            try {
                return safeText(cell.toString());
            } catch (Exception ignoredAgain) {
                return "";
            }
        }
    }

    private static String formatFormulaCell(Cell cell, FormulaEvaluator evaluator) {
        if (evaluator == null) return safeText(cell.toString());
        try {
            CellValue value = evaluator.evaluate(cell);
            if (value == null) return safeText(cell.toString());
            return switch (value.getCellType()) {
                case STRING -> safeText(value.getStringValue());
                case BOOLEAN -> Boolean.toString(value.getBooleanValue());
                case NUMERIC -> formatEvaluatedNumeric(cell, value.getNumberValue());
                case BLANK, ERROR, _NONE -> "";
                case FORMULA -> safeText(cell.toString());
            };
        } catch (Exception ignored) {
            return safeText(cell.toString());
        }
    }

    private static String formatNumericOrDate(Cell cell) {
        try {
            if (DateUtil.isCellDateFormatted(cell)) {
                LocalDateTime value = cell.getLocalDateTimeCellValue();
                if (value.toLocalTime().equals(LocalTime.MIDNIGHT)) {
                    return XLSX_DATE_FMT.format(value);
                }
                return XLSX_DATETIME_FMT.format(value);
            }
        } catch (Exception ignored) {}
        return decimal(cell.getNumericCellValue());
    }

    private static String formatEvaluatedNumeric(Cell cell, double value) {
        try {
            if (DateUtil.isCellDateFormatted(cell)) {
                LocalDateTime dateTime = DateUtil.getLocalDateTime(value, false);
                if (dateTime.toLocalTime().equals(LocalTime.MIDNIGHT)) {
                    return XLSX_DATE_FMT.format(dateTime);
                }
                return XLSX_DATETIME_FMT.format(dateTime);
            }
        } catch (Exception ignored) {}
        return decimal(value);
    }

    private static String decimal(double value) {
        try {
            return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        } catch (Exception ignored) {
            return Double.toString(value);
        }
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }
}

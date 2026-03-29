package com.asecon.enterpriseiq.service;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TabularFileService {
    public record TabularCsv(String filename, byte[] bytes, Charset charset, boolean convertedFromXlsx) {}

    public record XlsxOptions(Integer sheetIndex, Integer headerRow1Based) {}

    public record XlsxPreview(List<String> sheets,
                              Integer detectedSheetIndex,
                              Integer detectedHeaderRow1Based,
                              List<String> detectedHeaders,
                              List<List<String>> sampleRows) {}

    public TabularCsv toCsv(MultipartFile file, XlsxOptions xlsxOptions) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Archivo vacio");
        }
        if (isXlsx(file)) {
            return convertXlsxToCsv(file, xlsxOptions);
        }
        String filename = file.getOriginalFilename() == null ? "data.csv" : file.getOriginalFilename();
        return new TabularCsv(filename, file.getBytes(), null, false);
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
                    String v = cell == null ? "" : formatter.formatCellValue(cell, evaluator);
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
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "XLSX invalido o no soportado");
        }
    }

    private TabularCsv convertXlsxToCsv(MultipartFile file, XlsxOptions xlsxOptions) throws IOException {
        String filename = file.getOriginalFilename() == null ? "data.xlsx" : file.getOriginalFilename();
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

            StringWriter writer = new StringWriter();
            try (CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setDelimiter(',').build())) {
                printer.printRecord((Object[]) headerInfo.headers);

                int emptyStreak = 0;
                for (int r = headerInfo.rowIndex + 1; r <= sheet.getLastRowNum(); r++) {
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
                        String v = cell == null ? "" : formatter.formatCellValue(cell, evaluator);
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
            return new TabularCsv(filename.replaceAll("(?i)\\.xlsx$", ".csv"), bytes, StandardCharsets.UTF_8, true);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "XLSX invalido o no soportado");
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
                        String v = cell == null ? "" : formatter.formatCellValue(cell, evaluator);
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
            Set<String> distinct = new HashSet<>();
            for (int c = 0; c < lastCell; c++) {
                Cell cell = row.getCell(c);
                String v = cell == null ? "" : formatter.formatCellValue(cell, evaluator);
                if (v != null && !v.trim().isEmpty()) {
                    nonEmpty++;
                    lastNonEmptyCol = c;
                    String t = v.trim();
                    distinct.add(t.toLowerCase(Locale.ROOT));
                    if (looksNumericHeader(t) && !looksYearHeader(t)) numericLike++;
                    if (looksTextHeader(t) || looksYearHeader(t)) textLike++;
                }
            }

            if (nonEmpty < 2 || lastNonEmptyCol < 1) continue;

            double distinctRatio = distinct.isEmpty() ? 0.0 : (double) distinct.size() / (double) nonEmpty;
            double textRatio = (double) textLike / (double) nonEmpty;
            double numericRatio = (double) numericLike / (double) nonEmpty;

            // Header rows tend to have more text labels (or years) and higher uniqueness.
            double score = 0.0;
            score += nonEmpty * 1.0;
            score += distinctRatio * 6.0;
            score += textRatio * 8.0;
            score -= numericRatio * 7.0;
            score += Math.min(4.0, (double) lastNonEmptyCol / 50.0);
            score -= r * 0.15; // prefer earlier rows for headers

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

    private static String[] buildHeaders(Row headerRow, int headerCount, DataFormatter formatter, FormulaEvaluator evaluator) {
        String[] headers = new String[headerCount];
        Map<String, Integer> seen = new HashMap<>();

        for (int c = 0; c < headerCount; c++) {
            Cell cell = headerRow.getCell(c);
            String raw = cell == null ? "" : formatter.formatCellValue(cell, evaluator);
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
}

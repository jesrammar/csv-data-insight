package com.asecon.enterpriseiq.service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

public final class BudgetLongNormalizer {
    private BudgetLongNormalizer() {}

    public enum RowType { ITEM, TOTAL, TEXT }

    public record LongRow(RowType rowType,
                          String code,
                          String label,
                          String monthKey,
                          String monthLabel,
                          BigDecimal amount) {}

    public record Result(List<String> monthKeys,
                         String labelHeader,
                         long totalRowsProduced,
                         List<LongRow> sampleRows,
                         byte[] longCsvBytes) {}

    private static final List<String> MONTH_KEYS = List.of(
        "ENERO", "FEBRERO", "MARZO", "ABRIL", "MAYO", "JUNIO",
        "JULIO", "AGOSTO", "SEPTIEMBRE", "OCTUBRE", "NOVIEMBRE", "DICIEMBRE"
    );

    private static final Map<String, String> MONTH_LABELS = Map.ofEntries(
        Map.entry("ENERO", "Enero"),
        Map.entry("FEBRERO", "Febrero"),
        Map.entry("MARZO", "Marzo"),
        Map.entry("ABRIL", "Abril"),
        Map.entry("MAYO", "Mayo"),
        Map.entry("JUNIO", "Junio"),
        Map.entry("JULIO", "Julio"),
        Map.entry("AGOSTO", "Agosto"),
        Map.entry("SEPTIEMBRE", "Septiembre"),
        Map.entry("OCTUBRE", "Octubre"),
        Map.entry("NOVIEMBRE", "Noviembre"),
        Map.entry("DICIEMBRE", "Diciembre")
    );

    public static Result normalizeToLongCsv(byte[] normalizedUniversalCsvBytes, int maxSourceRows, int maxSampleRows) {
        if (normalizedUniversalCsvBytes == null || normalizedUniversalCsvBytes.length == 0) {
            return new Result(List.of(), null, 0, List.of(), new byte[0]);
        }
        if (maxSourceRows < 1) maxSourceRows = 1_000;
        if (maxSourceRows > 50_000) maxSourceRows = 50_000;
        if (maxSampleRows < 0) maxSampleRows = 0;
        if (maxSampleRows > 500) maxSampleRows = 500;

        String head = new String(normalizedUniversalCsvBytes, 0, Math.min(normalizedUniversalCsvBytes.length, 4096), StandardCharsets.UTF_8);
        int eol = head.indexOf('\n');
        if (eol >= 0) head = head.substring(0, eol);
        char delimiter = detectDelimiter(head);

        Map<String, String> monthHeader = new LinkedHashMap<>();
        String labelHeader;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(normalizedUniversalCsvBytes), StandardCharsets.UTF_8))) {
            CSVParser parser = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setAllowMissingColumnNames(true)
                .setIgnoreEmptyLines(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .build()
                .parse(reader);

            List<String> headers = new ArrayList<>(parser.getHeaderMap().keySet());
            for (String h : headers) {
                if (h == null) continue;
                String norm = h.trim().toUpperCase(Locale.ROOT);
                if (MONTH_LABELS.containsKey(norm)) {
                    monthHeader.put(norm, h);
                }
            }
            long present = monthHeader.keySet().stream().filter(MONTH_LABELS::containsKey).count();
            if (present < 6) {
                return new Result(List.copyOf(monthHeader.keySet()), null, 0, List.of(), new byte[0]);
            }

            labelHeader = detectLabelHeader(parser, headers, monthHeader);
            if (labelHeader == null) {
                return new Result(List.copyOf(monthHeader.keySet()), null, 0, List.of(), new byte[0]);
            }
        } catch (Exception ex) {
            return new Result(List.of(), null, 0, List.of(), new byte[0]);
        }

        List<LongRow> sample = new ArrayList<>();
        long produced = 0;

        StringBuilder out = new StringBuilder(64 * 1024);
        out.append("row_type,code,label,month_key,month_label,amount\n");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(normalizedUniversalCsvBytes), StandardCharsets.UTF_8))) {
            CSVParser parser = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setAllowMissingColumnNames(true)
                .setIgnoreEmptyLines(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .build()
                .parse(reader);

            int rows = 0;
            for (CSVRecord record : parser) {
                rows++;
                if (rows > maxSourceRows) break;

                String labelRaw = clean(get(record, labelHeader));
                if (labelRaw == null) continue;

                ParsedLabel parsed = parsePartidaLabel(labelRaw);
                RowType rowType = classifyRowType(labelRaw, parsed.code());

                boolean anyMonthPresent = false;
                for (String mk : MONTH_KEYS) {
                    String h = monthHeader.get(mk);
                    if (h == null) continue;
                    String cell = clean(get(record, h));
                    if (cell == null) continue;
                    BigDecimal amount = parseMoney(cell);
                    if (amount == null) continue;

                    anyMonthPresent = true;
                    LongRow row = new LongRow(rowType, parsed.code(), parsed.label(), mk, MONTH_LABELS.getOrDefault(mk, mk), amount);
                    produced++;
                    if (sample.size() < maxSampleRows) sample.add(row);
                    appendCsvRow(out, row);
                }

                // Keep rows that have explicit 0s across months (common in budgets) already handled above.
                if (!anyMonthPresent) {
                    // Skip text-only rows (titles, blank separators)
                }
            }
        } catch (Exception ex) {
            return new Result(List.copyOf(monthHeader.keySet()), labelHeader, produced, sample, out.toString().getBytes(StandardCharsets.UTF_8));
        }

        return new Result(List.copyOf(monthHeader.keySet()), labelHeader, produced, sample, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void appendCsvRow(StringBuilder out, LongRow row) {
        out.append(row.rowType().name()).append(',');
        out.append(csvEscape(row.code())).append(',');
        out.append(csvEscape(row.label())).append(',');
        out.append(row.monthKey()).append(',');
        out.append(row.monthLabel()).append(',');
        out.append(row.amount() == null ? "" : row.amount().toPlainString());
        out.append('\n');
    }

    private static String csvEscape(String v) {
        if (v == null) return "";
        String s = v;
        boolean needsQuote = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (!needsQuote) return s;
        s = s.replace("\"", "\"\"");
        return "\"" + s + "\"";
    }

    private static String detectLabelHeader(CSVParser parser, List<String> headers, Map<String, String> monthHeader) {
        List<String> candidates = headers.stream()
            .filter(Objects::nonNull)
            .filter(h -> !monthHeader.containsValue(h))
            .limit(5)
            .toList();

        Map<String, Integer> hits = new LinkedHashMap<>();
        for (String c : candidates) hits.put(c, 0);

        int rows = 0;
        for (CSVRecord record : parser) {
            rows++;
            if (rows > 120) break;
            for (String c : candidates) {
                String v = clean(get(record, c));
                if (v == null) continue;
                String up = v.toUpperCase(Locale.ROOT);
                if (up.contains("TOTAL") || up.startsWith("700") || looksLikePartida(up)) {
                    hits.put(c, hits.getOrDefault(c, 0) + 1);
                }
            }
        }

        String best = null;
        int bestScore = -1;
        for (var e : hits.entrySet()) {
            if (e.getValue() > bestScore) {
                bestScore = e.getValue();
                best = e.getKey();
            }
        }
        if (bestScore < 1) return null;
        return best;
    }

    private record ParsedLabel(String code, String label) {}

    private static ParsedLabel parsePartidaLabel(String rawLabel) {
        if (rawLabel == null) return new ParsedLabel(null, null);
        String s = rawLabel.trim().replaceAll("\\s+", " ");
        if (s.isEmpty()) return new ParsedLabel(null, null);

        int space = s.indexOf(' ');
        String first = space > 0 ? s.substring(0, space) : s;
        String rest = space > 0 ? s.substring(space + 1).trim() : "";

        String code = isPartidaCode(first) ? first : null;
        if (code != null) {
            String label = rest.isEmpty() ? code : rest;
            return new ParsedLabel(code, label);
        }
        return new ParsedLabel(null, s);
    }

    private static boolean isPartidaCode(String token) {
        if (token == null) return false;
        String t = token.trim();
        if (t.length() < 2 || t.length() > 20) return false;
        // 700, 700-8, 700-21, 640.1 (allow dot)
        return t.matches("^[0-9]{1,4}([\\-.][0-9]{1,4})*$");
    }

    private static boolean looksLikePartida(String upper) {
        if (upper == null) return false;
        String s = upper.trim();
        if (s.length() < 2) return false;
        int space = s.indexOf(' ');
        String first = space > 0 ? s.substring(0, space) : s;
        return isPartidaCode(first);
    }

    private static RowType classifyRowType(String labelRaw, String code) {
        if (code != null) return RowType.ITEM;
        String up = labelRaw == null ? "" : labelRaw.toUpperCase(Locale.ROOT);
        if (up.contains("TOTAL")) return RowType.TOTAL;
        return RowType.TEXT;
    }

    private static String get(CSVRecord record, String header) {
        if (record == null || header == null) return null;
        try {
            if (!record.isMapped(header)) return null;
            return record.get(header);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String clean(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.isBlank() || "-".equals(s)) return null;
        return s;
    }

    private static BigDecimal parseMoney(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replace(" ", "");
        int comma = cleaned.lastIndexOf(',');
        int dot = cleaned.lastIndexOf('.');
        if (comma > dot) {
            cleaned = cleaned.replace(".", "").replace(",", ".");
        } else {
            cleaned = cleaned.replace(",", "");
        }
        cleaned = cleaned.replace("€", "");
        cleaned = cleaned.trim();
        if (cleaned.isBlank()) return null;
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static char detectDelimiter(String head) {
        if (head == null) return ',';
        int commas = count(head, ',');
        int semis = count(head, ';');
        int tabs = count(head, '\t');
        int pipes = count(head, '|');
        int max = commas;
        char best = ',';
        if (semis > max) { max = semis; best = ';'; }
        if (tabs > max) { max = tabs; best = '\t'; }
        if (pipes > max) { max = pipes; best = '|'; }
        return best;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}

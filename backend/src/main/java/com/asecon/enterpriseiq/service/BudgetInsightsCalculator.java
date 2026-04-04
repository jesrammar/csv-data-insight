package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.BudgetItemInsightDto;
import com.asecon.enterpriseiq.dto.BudgetLongInsightsDto;
import com.asecon.enterpriseiq.dto.BudgetMonthTotalDto;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

public final class BudgetInsightsCalculator {
    private BudgetInsightsCalculator() {}

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

    public static BudgetLongInsightsDto compute(String filename, Instant createdAt, byte[] normalizedUniversalCsvBytes, int maxSourceRows) {
        if (normalizedUniversalCsvBytes == null || normalizedUniversalCsvBytes.length == 0) {
            return new BudgetLongInsightsDto(filename, createdAt, 0, BigDecimal.ZERO, null, null, BigDecimal.ZERO, List.of(), List.of(), List.of());
        }
        if (maxSourceRows < 1) maxSourceRows = 1_000;
        if (maxSourceRows > 50_000) maxSourceRows = 50_000;

        String head = new String(normalizedUniversalCsvBytes, 0, Math.min(normalizedUniversalCsvBytes.length, 4096), StandardCharsets.UTF_8);
        int eol = head.indexOf('\n');
        if (eol >= 0) head = head.substring(0, eol);
        char delimiter = detectDelimiter(head);

        Map<String, String> monthHeader = new LinkedHashMap<>();
        String labelHeader;
        List<String> headers;

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

            headers = new ArrayList<>(parser.getHeaderMap().keySet());
            for (String h : headers) {
                if (h == null) continue;
                String norm = h.trim().toUpperCase(Locale.ROOT);
                if (MONTH_LABELS.containsKey(norm)) {
                    monthHeader.put(norm, h);
                }
            }
            long present = monthHeader.keySet().stream().filter(MONTH_LABELS::containsKey).count();
            if (present < 6) {
                return new BudgetLongInsightsDto(filename, createdAt, 0, BigDecimal.ZERO, null, null, BigDecimal.ZERO, List.of(), List.of(), List.of());
            }

            labelHeader = detectLabelHeader(parser, headers, monthHeader);
            if (labelHeader == null) {
                return new BudgetLongInsightsDto(filename, createdAt, 0, BigDecimal.ZERO, null, null, BigDecimal.ZERO, List.of(), List.of(), List.of());
            }
        } catch (Exception ex) {
            return new BudgetLongInsightsDto(filename, createdAt, 0, BigDecimal.ZERO, null, null, BigDecimal.ZERO, List.of(), List.of(), List.of());
        }

        Map<String, BigDecimal> monthTotals = new LinkedHashMap<>();
        for (String mk : MONTH_KEYS) {
            if (monthHeader.containsKey(mk)) monthTotals.put(mk, BigDecimal.ZERO);
        }

        Map<ItemKey, ItemAgg> items = new LinkedHashMap<>();

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
                if (parsed.code() == null) continue; // only ITEM rows (avoid totals/text)

                ItemKey key = new ItemKey(parsed.code(), parsed.label());
                ItemAgg agg = items.computeIfAbsent(key, k -> new ItemAgg());

                for (String mk : monthTotals.keySet()) {
                    String h = monthHeader.get(mk);
                    String cell = cleanAllowZero(get(record, h));
                    BigDecimal amount = cell == null ? BigDecimal.ZERO : parseMoney(cell);
                    if (amount == null) amount = BigDecimal.ZERO;
                    agg.months.merge(mk, amount, BigDecimal::add);
                }
            }
        } catch (Exception ex) {
            // ignore partial results
        }

        // Build month totals from items (avoid double-counting TOTAL rows)
        for (var e : items.entrySet()) {
            for (String mk : monthTotals.keySet()) {
                BigDecimal v = e.getValue().months.getOrDefault(mk, BigDecimal.ZERO);
                monthTotals.put(mk, monthTotals.get(mk).add(v));
            }
        }

        BigDecimal totalAbsAnnual = BigDecimal.ZERO;
        List<ItemComputed> computed = new ArrayList<>();
        for (var e : items.entrySet()) {
            BigDecimal annual = BigDecimal.ZERO;
            int zeroMonths = 0;
            for (String mk : monthTotals.keySet()) {
                BigDecimal v = e.getValue().months.getOrDefault(mk, BigDecimal.ZERO);
                annual = annual.add(v);
                if (v.compareTo(BigDecimal.ZERO) == 0) zeroMonths++;
            }
            BigDecimal abs = annual.abs();
            totalAbsAnnual = totalAbsAnnual.add(abs);
            computed.add(new ItemComputed(e.getKey(), annual, abs, zeroMonths));
        }

        final BigDecimal totalAbsAnnualFinal = totalAbsAnnual;
        computed.sort(Comparator.comparing(ItemComputed::absAnnual).reversed());

        List<BudgetItemInsightDto> topDrivers = computed.stream()
            .limit(10)
            .map(c -> new BudgetItemInsightDto(
                c.key.code,
                c.key.label,
                c.annual.setScale(2, RoundingMode.HALF_UP),
                c.zeroMonths,
                sharePct(c.absAnnual, totalAbsAnnualFinal)
            ))
            .toList();

        List<BudgetItemInsightDto> zeroHeavy = computed.stream()
            .filter(c -> c.zeroMonths >= 8)
            .sorted(Comparator.<ItemComputed>comparingInt(c -> c.zeroMonths).reversed()
                .thenComparing(ItemComputed::absAnnual, Comparator.reverseOrder()))
            .limit(15)
            .map(c -> new BudgetItemInsightDto(
                c.key.code,
                c.key.label,
                c.annual.setScale(2, RoundingMode.HALF_UP),
                c.zeroMonths,
                sharePct(c.absAnnual, totalAbsAnnualFinal)
            ))
            .toList();

        BigDecimal top3Abs = computed.stream().limit(3).map(ItemComputed::absAnnual).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal concentrationTop3 = sharePct(top3Abs, totalAbsAnnualFinal);

        // seasonality: best/worst month by total
        String bestMonth = null;
        String worstMonth = null;
        BigDecimal best = null;
        BigDecimal worst = null;
        for (var e : monthTotals.entrySet()) {
            BigDecimal v = e.getValue();
            if (best == null || v.compareTo(best) > 0) { best = v; bestMonth = e.getKey(); }
            if (worst == null || v.compareTo(worst) < 0) { worst = v; worstMonth = e.getKey(); }
        }

        List<BudgetMonthTotalDto> monthTotalDtos = monthTotals.entrySet().stream()
            .map(e -> new BudgetMonthTotalDto(e.getKey(), MONTH_LABELS.getOrDefault(e.getKey(), e.getKey()), e.getValue().setScale(2, RoundingMode.HALF_UP)))
            .toList();

        return new BudgetLongInsightsDto(
            filename,
            createdAt,
            items.size(),
            totalAbsAnnual.setScale(2, RoundingMode.HALF_UP),
            bestMonth,
            worstMonth,
            concentrationTop3,
            monthTotalDtos,
            topDrivers,
            zeroHeavy
        );
    }

    private static BigDecimal sharePct(BigDecimal part, BigDecimal total) {
        if (part == null) return BigDecimal.ZERO;
        if (total == null || total.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return part
            .divide(total, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100))
            .setScale(2, RoundingMode.HALF_UP);
    }

    private record ItemKey(String code, String label) {}
    private static final class ItemAgg { final Map<String, BigDecimal> months = new LinkedHashMap<>(); }
    private record ItemComputed(ItemKey key, BigDecimal annual, BigDecimal absAnnual, int zeroMonths) {}

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
                if (up.contains("TOTAL") || looksLikePartida(up)) {
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

    private static String cleanAllowZero(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.isBlank() || "-".equals(s)) return null;
        return s;
    }

    private static BigDecimal parseMoney(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        boolean negative = false;
        if (s.startsWith("(") && s.endsWith(")") && s.length() > 2) {
            negative = true;
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.endsWith("-") && s.length() > 1) {
            negative = true;
            s = s.substring(0, s.length() - 1).trim();
        }
        if (s.startsWith("-") && s.length() > 1) {
            negative = true;
            s = s.substring(1).trim();
        }

        s = s.replace("\u00A0", "").replace(" ", "");
        s = s.replaceAll("[^0-9,\\.\\-]", "");
        s = s.trim();
        if (s.isBlank() || "-".equals(s)) return null;

        int comma = s.lastIndexOf(',');
        int dot = s.lastIndexOf('.');
        if (comma > dot) {
            s = s.replace(".", "").replace(",", ".");
        } else {
            s = s.replace(",", "");
        }
        s = s.trim();
        if (s.isBlank() || "-".equals(s)) return null;

        try {
            BigDecimal parsed = new BigDecimal(s);
            return negative ? parsed.negate() : parsed;
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

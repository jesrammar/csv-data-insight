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
import java.util.Set;
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
            return new BudgetLongInsightsDto(filename, createdAt, null, null, 0, BigDecimal.ZERO, null, null, BigDecimal.ZERO, List.of(), List.of(), List.of(), List.of());
        }
        if (maxSourceRows < 1) maxSourceRows = 1_000;
        if (maxSourceRows > 50_000) maxSourceRows = 50_000;

        String head = new String(normalizedUniversalCsvBytes, 0, Math.min(normalizedUniversalCsvBytes.length, 4096), StandardCharsets.UTF_8);
        int eol = head.indexOf('\n');
        if (eol >= 0) head = head.substring(0, eol);
        char delimiter = detectDelimiter(head);

        BudgetLongInsightsDto canonicalResult = computeCanonicalLong(filename, createdAt, normalizedUniversalCsvBytes, delimiter, maxSourceRows);
        if (canonicalResult != null) {
            return canonicalResult;
        }

        Map<String, String> monthHeader = new LinkedHashMap<>();
        String labelHeader;
        String codeHeader;
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
                return new BudgetLongInsightsDto(filename, createdAt, null, null, 0, BigDecimal.ZERO, null, null, BigDecimal.ZERO, List.of(), List.of(), List.of(), List.of());
            }

            codeHeader = selectCodeHeader(headers);
            labelHeader = detectLabelHeader(parser, headers, monthHeader);
            if (labelHeader == null) {
                return new BudgetLongInsightsDto(filename, createdAt, null, null, 0, BigDecimal.ZERO, null, null, BigDecimal.ZERO, List.of(), List.of(), List.of(), List.of());
            }
        } catch (Exception ex) {
            return new BudgetLongInsightsDto(filename, createdAt, null, null, 0, BigDecimal.ZERO, null, null, BigDecimal.ZERO, List.of(), List.of(), List.of(), List.of());
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
                String rawCodeValue = clean(get(record, codeHeader));
                if (labelRaw == null && rawCodeValue == null) continue;
                ParsedLabel parsed = parsePartidaLabel(labelRaw);
                String code = firstNonBlank(normalizeCodeCandidate(rawCodeValue), parsed.code());
                String label = parsed.label();
                if (code == null || label == null) continue; // only ITEM rows (avoid totals/text)

                ItemKey key = new ItemKey(code, label, "UNKNOWN");
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

        return buildResult(filename, createdAt, monthTotals, items, false);
    }

    private static BudgetLongInsightsDto computeCanonicalLong(String filename,
                                                              Instant createdAt,
                                                              byte[] csvBytes,
                                                              char delimiter,
                                                              int maxSourceRows) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(csvBytes), StandardCharsets.UTF_8))) {
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
            boolean hasCanonicalAmounts = headers.contains("budget_amount") || headers.contains("amount");
            if (!(headers.contains("month_key") && headers.contains("label") && hasCanonicalAmounts)) {
                return null;
            }

            Map<String, BigDecimal> monthTotals = new LinkedHashMap<>();
            Map<ItemKey, ItemAgg> items = new LinkedHashMap<>();

            int rows = 0;
            for (CSVRecord record : parser) {
                rows++;
                if (rows > maxSourceRows) break;

                String rowType = clean(get(record, "row_type"));
                if (rowType != null && !"DETAIL".equalsIgnoreCase(rowType)) continue;

                String monthKey = clean(get(record, "month_key"));
                String label = clean(get(record, "label"));
                String semanticKind = firstNonBlank(
                    clean(get(record, "financial_nature")),
                    clean(get(record, "semantic_kind"))
                );
                String sectionKind = upper(clean(get(record, "section_kind")));
                String mappingStatus = upper(clean(get(record, "mapping_status")));
                BigDecimal amount = firstNonNull(
                    parseMoney(cleanAllowZero(get(record, "budget_amount"))),
                    parseMoney(cleanAllowZero(get(record, "amount"))),
                    parseMoney(cleanAllowZero(get(record, "actual_amount"))),
                    parseMoney(cleanAllowZero(get(record, "forecast_amount"))),
                    parseMoney(cleanAllowZero(get(record, "variance_amount")))
                );
                if (monthKey == null || label == null || amount == null || !isDriverSemantic(semanticKind)) continue;
                if (!sectionKind.isBlank() && !"P_AND_L".equals(sectionKind)) continue;
                if ("REVIEW".equals(mappingStatus)) continue;

                monthTotals.putIfAbsent(monthKey, BigDecimal.ZERO);
                String code = clean(get(record, "code"));
                ItemKey key = new ItemKey(code, label, upper(semanticKind));
                ItemAgg agg = items.computeIfAbsent(key, k -> new ItemAgg());
                agg.months.merge(monthKey, amount, BigDecimal::add);
            }

            if (items.isEmpty() || monthTotals.isEmpty()) {
                return new BudgetLongInsightsDto(filename, createdAt, null, null, 0, BigDecimal.ZERO, null, null, BigDecimal.ZERO, List.of(), List.of(), List.of(), List.of());
            }

            for (var entry : items.entrySet()) {
                for (String mk : monthTotals.keySet()) {
                    BigDecimal value = entry.getValue().months.getOrDefault(mk, BigDecimal.ZERO);
                    monthTotals.put(mk, monthTotals.get(mk).add(value));
                }
            }

            return buildResult(filename, createdAt, monthTotals, items, true);
        } catch (Exception ex) {
            return null;
        }
    }

    private static BudgetLongInsightsDto buildResult(String filename,
                                                     Instant createdAt,
                                                     Map<String, BigDecimal> monthTotals,
                                                     Map<ItemKey, ItemAgg> items,
                                                     boolean canonical) {
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
            computed.add(new ItemComputed(
                e.getKey(),
                annual,
                abs,
                zeroMonths,
                BudgetCanonicalClassifier.classifyZeroInterpretation(e.getKey().semanticKind, e.getKey().label, orderedValues(e.getValue().months, monthTotals))
            ));
        }

        final BigDecimal totalAbsAnnualFinal = totalAbsAnnual;
        computed.sort(Comparator.comparing(ItemComputed::absAnnual).reversed());

        List<BudgetItemInsightDto> topDrivers = computed.stream()
            .limit(10)
            .map(c -> new BudgetItemInsightDto(
                c.key.code,
                c.key.label,
                c.key.label,
                c.key.semanticKind,
                null,
                null,
                c.annual.setScale(2, RoundingMode.HALF_UP),
                c.zeroMonths,
                sharePct(c.absAnnual, totalAbsAnnualFinal),
                c.zeroInterpretation,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
            ))
            .toList();

        List<BudgetItemInsightDto> zeroHeavy = computed.stream()
            .filter(c -> c.zeroMonths >= 8)
            .filter(c -> !"NO_ACTIVITY".equals(c.zeroInterpretation))
            .filter(c -> !"NOT_APPLICABLE".equals(c.zeroInterpretation))
            .sorted(Comparator.<ItemComputed>comparingInt(c -> c.zeroMonths).reversed()
                .thenComparing(ItemComputed::absAnnual, Comparator.reverseOrder()))
            .limit(15)
            .map(c -> new BudgetItemInsightDto(
                c.key.code,
                c.key.label,
                c.key.label,
                c.key.semanticKind,
                null,
                null,
                c.annual.setScale(2, RoundingMode.HALF_UP),
                c.zeroMonths,
                sharePct(c.absAnnual, totalAbsAnnualFinal),
                c.zeroInterpretation,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
            ))
            .toList();

        BigDecimal top3Abs = computed.stream().limit(3).map(ItemComputed::absAnnual).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal concentrationTop3 = sharePct(top3Abs, totalAbsAnnualFinal);

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
            null,
            null,
            items.size(),
            totalAbsAnnual.setScale(2, RoundingMode.HALF_UP),
            bestMonth,
            worstMonth,
            concentrationTop3,
            monthTotalDtos,
            topDrivers,
            zeroHeavy,
            List.of()
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

    private static BigDecimal firstNonNull(BigDecimal... values) {
        if (values == null) return null;
        for (BigDecimal value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static boolean isDriverSemantic(String semanticKind) {
        return Set.of("REVENUE", "OPEX", "CAPEX").contains(upper(semanticKind));
    }

    private static List<BigDecimal> orderedValues(Map<String, BigDecimal> values, Map<String, BigDecimal> monthTotals) {
        List<BigDecimal> out = new ArrayList<>();
        for (String monthKey : monthTotals.keySet()) {
            out.add(values.getOrDefault(monthKey, BigDecimal.ZERO));
        }
        return out;
    }

    private static String upper(String value) {
        return value == null ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT);
    }

    private record ItemKey(String code, String label, String semanticKind) {}
    private static final class ItemAgg { final Map<String, BigDecimal> months = new LinkedHashMap<>(); }
    private record ItemComputed(ItemKey key, BigDecimal annual, BigDecimal absAnnual, int zeroMonths, String zeroInterpretation) {}

    private static String detectLabelHeader(CSVParser parser, List<String> headers, Map<String, String> monthHeader) {
        List<String> candidates = headers.stream()
            .filter(Objects::nonNull)
            .filter(h -> !monthHeader.containsValue(h))
            .limit(8)
            .toList();

        Map<String, Integer> hits = new LinkedHashMap<>();
        for (String c : candidates) hits.put(c, labelHeaderNameScore(c));

        int rows = 0;
        for (CSVRecord record : parser) {
            rows++;
            if (rows > 120) break;
            for (String c : candidates) {
                String v = clean(get(record, c));
                if (v == null) continue;
                hits.put(c, hits.getOrDefault(c, 0) + labelValueScore(v));
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
        if (bestScore < 2) return null;
        return best;
    }

    private static String selectCodeHeader(List<String> headers) {
        if (headers == null) return null;
        for (String header : headers) {
            if (looksLikeCodeHeader(header)) return header;
        }
        return null;
    }

    private static boolean looksLikeCodeHeader(String header) {
        if (header == null) return false;
        String normalized = BudgetSemanticResolver.normalize(header);
        return normalized.contains("code")
            || normalized.contains("codigo")
            || normalized.contains("cuenta")
            || normalized.contains("account")
            || normalized.endsWith(" id")
            || normalized.startsWith("id ");
    }

    private static int labelHeaderNameScore(String header) {
        if (header == null || header.isBlank()) return Integer.MIN_VALUE / 4;
        String normalized = BudgetSemanticResolver.normalize(header);
        int score = 0;
        if (normalized.contains("descripcion") || normalized.contains("description")) score += 160;
        if (normalized.contains("concept label") || normalized.contains("concepto")) score += 140;
        if (normalized.contains("detalle") || normalized.contains("nombre")) score += 120;
        if (normalized.contains("label")) score += 90;
        if (normalized.contains("concept")) score += 70;
        if (normalized.contains("partida")) score += 32;
        if (looksLikeCodeHeader(header)) score -= 120;
        return score;
    }

    private static int labelValueScore(String value) {
        if (value == null || value.isBlank()) return 0;
        int score = 0;
        String upper = value.toUpperCase(Locale.ROOT);
        boolean hasLetters = containsLetters(value);
        boolean looksLikeCodeOnly = looksLikePartida(upper) && !hasDescriptiveSuffix(value);
        if (upper.contains("TOTAL")) score += 2;
        if (hasLetters) score += 3;
        if (hasDescriptiveSuffix(value)) score += 6;
        if (looksLikeCodeOnly) score -= 10;
        return score;
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

    private static String normalizeCodeCandidate(String rawCode) {
        if (rawCode == null) return null;
        String trimmed = rawCode.trim();
        return isPartidaCode(trimmed) ? trimmed : null;
    }

    private static boolean containsLetters(String value) {
        if (value == null) return false;
        return value.chars().anyMatch(Character::isLetter);
    }

    private static boolean hasDescriptiveSuffix(String value) {
        ParsedLabel parsed = parsePartidaLabel(value);
        return parsed != null
            && parsed.code() != null
            && parsed.label() != null
            && !parsed.label().equals(parsed.code())
            && containsLetters(parsed.label());
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

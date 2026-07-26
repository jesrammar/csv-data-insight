package com.asecon.enterpriseiq.service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

public final class BudgetLongNormalizer {
    private BudgetLongNormalizer() {}

    public enum RowType { DETAIL, SUBTOTAL, TOTAL, DERIVED_KPI, ASSUMPTION, TEXT }

    public record LongRow(RowType rowType,
                          String code,
                          String label,
                          String semanticKind,
                          String sectionKind,
                          String financialNature,
                          String cashflowNature,
                          String blockId,
                          Integer sourceRow,
                          String monthKey,
                          String monthLabel,
                          BigDecimal amount,
                          BigDecimal budgetAmount,
                          BigDecimal actualAmount,
                          BigDecimal forecastAmount,
                          BigDecimal varianceAmount,
                          String currency,
                          String costCenter,
                          String department,
                          String confidence,
                          String mappingStatus) {}

    public record Result(List<String> monthKeys,
                         String labelHeader,
                         long totalRowsProduced,
                         List<LongRow> sampleRows,
                         boolean requiresConfirmation,
                         List<String> mappingNotes,
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

    private static final String CANONICAL_HEADER =
        "row_type,code,label,semantic_kind,section_kind,financial_nature,cashflow_nature,block_id,source_row,month_key,month_label,amount,budget_amount,actual_amount,forecast_amount,variance_amount,currency,cost_center,department,inference_confidence,mapping_status\n";

    private static final Pattern LEADING_ACCOUNTING_CODE = Pattern.compile(
        "^(?<code>[0-9]{2,4}(?:[-/][0-9]{1,4})?)(?:[\\s._-]+(?<label>.*))?$"
    );

    public static Result normalizeToLongCsv(byte[] normalizedUniversalCsvBytes, int maxSourceRows, int maxSampleRows) {
        return normalizeToLongCsv(normalizedUniversalCsvBytes, null, maxSourceRows, maxSampleRows);
    }

    public static Result normalizeToLongCsv(byte[] normalizedUniversalCsvBytes,
                                            String clientKey,
                                            int maxSourceRows,
                                            int maxSampleRows) {
        if (normalizedUniversalCsvBytes == null || normalizedUniversalCsvBytes.length == 0) {
            return new Result(List.of(), null, 0, List.of(), false, List.of(), new byte[0]);
        }
        if (maxSourceRows < 1) maxSourceRows = 1_000;
        if (maxSourceRows > 50_000) maxSourceRows = 50_000;
        if (maxSampleRows < 0) maxSampleRows = 0;
        if (maxSampleRows > 500) maxSampleRows = 500;

        String head = new String(normalizedUniversalCsvBytes, 0, Math.min(normalizedUniversalCsvBytes.length, 4096), StandardCharsets.UTF_8);
        int eol = head.indexOf('\n');
        if (eol >= 0) head = head.substring(0, eol);
        char delimiter = detectDelimiter(head);

        List<String> headers;
        List<Map<String, String>> sampleRows;
        try {
            headers = readHeaders(normalizedUniversalCsvBytes, delimiter);
            sampleRows = readSampleRows(normalizedUniversalCsvBytes, delimiter, 120);
        } catch (Exception ex) {
            return new Result(List.of(), null, 0, List.of(), false, List.of(), new byte[0]);
        }
        if (headers.isEmpty()) {
            return new Result(List.of(), null, 0, List.of(), false, List.of(), new byte[0]);
        }

        if (isCanonicalBudgetLongHeaders(headers)) {
            return previewCanonicalLongSource(normalizedUniversalCsvBytes, delimiter, maxSourceRows, maxSampleRows);
        }

        Map<String, String> monthHeader = detectWideMonthHeaders(headers);
        if (monthHeader.size() >= 6) {
            return normalizeWideSource(
                normalizedUniversalCsvBytes,
                delimiter,
                headers,
                sampleRows,
                monthHeader,
                clientKey,
                maxSourceRows,
                maxSampleRows
            );
        }

        BudgetSemanticResolver.Resolution resolution = BudgetSemanticResolver.resolve(headers, sampleRows, clientKey);
        BudgetSemanticResolver.HeaderInference monthNameInference = BudgetSemanticResolver.bestPeriodHeader(headers, sampleRows, clientKey, false);
        BudgetSemanticResolver.HeaderInference monthNumberInference = BudgetSemanticResolver.bestPeriodHeader(headers, sampleRows, clientKey, true);
        BudgetSemanticResolver.HeaderInference natureInference = BudgetSemanticResolver.detectNatureHeader(headers, sampleRows);

        String monthNameHeader = firstNonNull(
            monthNameInference == null ? null : monthNameInference.header(),
            findHeaderByTokens(headers, "mes nombre", "month name", "posting period", "periodo", "period", "month", "mes")
        );
        String monthNumberHeader = firstNonNull(
            monthNumberInference == null ? null : monthNumberInference.header(),
            findHeaderByTokens(headers, "mes numero", "month number", "numero mes", "month no")
        );
        String budgetHeader = firstNonNull(
            resolution.headerFor("BUDGET_AMOUNT"),
            findHeaderByTokens(headers, "plan amount", "budget amount", "presupuesto", "plan", "budget", "ppto")
        );
        String actualHeader = firstNonNull(
            resolution.headerFor("ACTUAL_AMOUNT"),
            findHeaderByTokens(headers, "importe real", "actual amount", "actual", "real", "ejecutado")
        );
        String forecastHeader = firstNonNull(
            resolution.headerFor("FORECAST_AMOUNT"),
            findHeaderByTokens(headers, "forecast cierre", "forecast", "prevision", "estimado", "estimate")
        );
        String varianceHeader = firstNonNull(
            resolution.headerFor("VARIANCE"),
            findHeaderByTokens(headers, "desviacion", "variance", "delta", "vs budget")
        );
        String labelHeader = firstNonNull(
            resolution.headerFor("CONCEPT_NAME"),
            findHeaderByTokens(headers, "concept label", "concepto", "partida", "label", "concept", "descripcion", "description"),
            detectLabelHeader(headers, sampleRows)
        );
        String codeHeader = selectCodeHeader(headers, resolution.headerFor("CONCEPT_CODE"));
        String costCenterHeader = resolution.headerFor("COST_CENTER");
        String departmentHeader = resolution.headerFor("DEPARTMENT");
        String currencyHeader = resolution.headerFor("CURRENCY");
        String natureHeader = natureInference == null ? null : natureInference.header();

        boolean hasPeriod = monthNameHeader != null || monthNumberHeader != null;
        boolean hasAmounts = budgetHeader != null || actualHeader != null || forecastHeader != null || varianceHeader != null;
        if (!hasPeriod || !hasAmounts || labelHeader == null) {
            return new Result(
                List.of(),
                labelHeader,
                0,
                List.of(),
                resolution.requiresConfirmation(),
                resolution.notes(),
                new byte[0]
            );
        }

        return normalizeLongSource(
            normalizedUniversalCsvBytes,
            delimiter,
            clientKey,
            maxSourceRows,
            maxSampleRows,
            monthNameHeader,
            monthNumberHeader,
            budgetHeader,
            actualHeader,
            forecastHeader,
            varianceHeader,
            labelHeader,
            codeHeader,
            natureHeader,
            costCenterHeader,
            departmentHeader,
            currencyHeader,
            resolution
        );
    }

    private static Result normalizeWideSource(byte[] bytes,
                                              char delimiter,
                                              List<String> headers,
                                              List<Map<String, String>> sampleRows,
                                              Map<String, String> monthHeader,
                                              String clientKey,
                                              int maxSourceRows,
                                              int maxSampleRows) {
        String labelHeader = firstNonNull(
            BudgetSemanticResolver.resolve(headers, sampleRows, clientKey).headerFor("CONCEPT_NAME"),
            detectLabelHeader(headers, sampleRows)
        );
        if (labelHeader == null) {
            return new Result(List.copyOf(monthHeader.keySet()), null, 0, List.of(), true, List.of("No se ha podido proponer una columna fiable para la partida."), new byte[0]);
        }

        List<LongRow> sample = new ArrayList<>();
        long produced = 0;
        int detailRows = 0;
        int unknownDetailRows = 0;
        int reviewRows = 0;
        int reviewDetailRows = 0;
        int inferredRows = 0;
        BudgetCanonicalClassifier.SectionContext sectionContext = BudgetCanonicalClassifier.emptyContext();
        Map<String, String> knownFinancialNatureBySignature = new LinkedHashMap<>();
        StringBuilder out = new StringBuilder(64 * 1024);
        out.append(CANONICAL_HEADER);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            CSVParser parser = csvParser(reader, delimiter);
            int rows = 0;
            for (CSVRecord record : parser) {
                rows++;
                if (rows > maxSourceRows) break;

                String labelRaw = clean(get(record, labelHeader));
                if (labelRaw == null) continue;

                ParsedLabel parsed = parsePartidaLabel(labelRaw);
                List<BigDecimal> rowValues = new ArrayList<>();
                for (String monthColumn : monthHeader.values()) {
                    rowValues.add(parseMoney(cleanAllowZero(get(record, monthColumn))));
                }
                BudgetCanonicalClassifier.Classification classification = BudgetCanonicalClassifier.classify(
                    clientKey,
                    labelRaw,
                    parsed.code(),
                    parsed.label(),
                    rowValues,
                    sectionContext
                );
                sectionContext = BudgetCanonicalClassifier.nextContext(sectionContext, classification);
                String mappingStatus = "UNKNOWN".equals(classification.semanticKind())
                    ? "REVIEW"
                    : classification.ambiguous() ? "INFERRED" : "CANONICAL";
                if (classification.rowType() == RowType.DETAIL) {
                    detailRows++;
                    if ("UNKNOWN".equals(classification.semanticKind())) unknownDetailRows++;
                    if ("REVIEW".equals(mappingStatus)) reviewDetailRows++;
                }
                if ("REVIEW".equals(mappingStatus)) reviewRows++;
                if ("INFERRED".equals(mappingStatus)) inferredRows++;
                String signature = rowSignature(parsed.code(), parsed.label());
                String financialNature = deriveFinancialNature(classification, signature, knownFinancialNatureBySignature);
                String cashflowNature = deriveCashflowNature(classification, financialNature);
                if (isFinancialNature(financialNature)) {
                    knownFinancialNatureBySignature.put(signature, financialNature);
                }

                for (String mk : MONTH_KEYS) {
                    String monthColumn = monthHeader.get(mk);
                    if (monthColumn == null) continue;
                    BigDecimal budgetAmount = parseMoney(cleanAllowZero(get(record, monthColumn)));
                    if (budgetAmount == null) continue;

                    LongRow row = new LongRow(
                        classification.rowType(),
                        parsed.code(),
                        parsed.label(),
                        canonicalSemanticKind(financialNature, cashflowNature, classification.semanticKind()),
                        classification.sectionKind(),
                        financialNature,
                        cashflowNature,
                        "ROW-" + rows,
                        rows,
                        mk,
                        MONTH_LABELS.getOrDefault(mk, mk),
                        budgetAmount,
                        budgetAmount,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        classification.confidence(),
                        mappingStatus
                    );
                    produced++;
                    if (sample.size() < maxSampleRows) sample.add(row);
                    appendCsvRow(out, row);
                }
            }
        } catch (Exception ex) {
            List<String> notes = mergeNotes(List.of(), detailRows, unknownDetailRows, reviewRows, inferredRows);
            boolean requiresConfirmation = requiresConfirmation(detailRows, unknownDetailRows, reviewDetailRows, false);
            return new Result(List.copyOf(monthHeader.keySet()), labelHeader, produced, sample, requiresConfirmation, notes, out.toString().getBytes(StandardCharsets.UTF_8));
        }

        List<String> notes = mergeNotes(List.of(), detailRows, unknownDetailRows, reviewRows, inferredRows);
        boolean requiresConfirmation = requiresConfirmation(detailRows, unknownDetailRows, reviewDetailRows, false);
        return new Result(List.copyOf(monthHeader.keySet()), labelHeader, produced, sample, requiresConfirmation, notes, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Result normalizeLongSource(byte[] bytes,
                                              char delimiter,
                                              String clientKey,
                                              int maxSourceRows,
                                              int maxSampleRows,
                                              String monthNameHeader,
                                              String monthNumberHeader,
                                              String budgetHeader,
                                              String actualHeader,
                                              String forecastHeader,
                                              String varianceHeader,
                                              String labelHeader,
                                              String codeHeader,
                                              String natureHeader,
                                              String costCenterHeader,
                                              String departmentHeader,
                                              String currencyHeader,
                                              BudgetSemanticResolver.Resolution resolution) {
        List<LongRow> sample = new ArrayList<>();
        Map<String, Boolean> monthSeen = new LinkedHashMap<>();
        long produced = 0;
        int detailRows = 0;
        int unknownDetailRows = 0;
        int reviewRows = 0;
        int reviewDetailRows = 0;
        int inferredRows = 0;
        BudgetCanonicalClassifier.SectionContext sectionContext = BudgetCanonicalClassifier.emptyContext();
        Map<String, String> knownFinancialNatureBySignature = new LinkedHashMap<>();
        StringBuilder out = new StringBuilder(64 * 1024);
        out.append(CANONICAL_HEADER);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            CSVParser parser = csvParser(reader, delimiter);
            int rows = 0;
            for (CSVRecord record : parser) {
                rows++;
                if (rows > maxSourceRows) break;

                String monthKey = detectMonthKey(clean(get(record, monthNameHeader)), clean(get(record, monthNumberHeader)));
                if (monthKey == null) continue;

                BigDecimal budgetAmount = parseMoney(cleanAllowZero(get(record, budgetHeader)));
                BigDecimal actualAmount = parseMoney(cleanAllowZero(get(record, actualHeader)));
                BigDecimal forecastAmount = parseMoney(cleanAllowZero(get(record, forecastHeader)));
                BigDecimal varianceAmount = parseMoney(cleanAllowZero(get(record, varianceHeader)));
                BigDecimal amount = firstNonNullAmount(budgetAmount, actualAmount, forecastAmount, varianceAmount);
                if (amount == null) continue;

                String label = clean(get(record, labelHeader));
                if (label == null) continue;
                String code = clean(get(record, codeHeader));
                String natureValue = clean(get(record, natureHeader));
                String costCenter = clean(get(record, costCenterHeader));
                String department = clean(get(record, departmentHeader));
                String currency = clean(get(record, currencyHeader));

                BudgetSemanticResolver.NatureInference nature = BudgetSemanticResolver.classifyBusinessNature(clientKey, natureValue, label, code);
                if ("UNKNOWN".equals(nature.concept()) && budgetAmount != null) {
                    if (budgetAmount.signum() < 0) {
                        nature = new BudgetSemanticResolver.NatureInference("OPEX", 0.55d, "LOW", true, List.of("inferido por signo al faltar tipología explícita"));
                    } else if (budgetAmount.signum() > 0) {
                        nature = new BudgetSemanticResolver.NatureInference("REVENUE", 0.52d, "LOW", true, List.of("inferido por signo al faltar tipología explícita"));
                    }
                }

                BudgetCanonicalClassifier.Classification classification = BudgetCanonicalClassifier.classify(
                    clientKey,
                    label,
                    code,
                    firstNonNull(natureValue, costCenter, department),
                    Arrays.asList(budgetAmount, actualAmount, forecastAmount, varianceAmount),
                    sectionContext
                );
                sectionContext = BudgetCanonicalClassifier.nextContext(sectionContext, classification);
                String mappingStatus = "UNKNOWN".equals(classification.semanticKind())
                    ? "REVIEW"
                    : classification.ambiguous() ? "INFERRED" : "CANONICAL";
                if (classification.rowType() == RowType.DETAIL) {
                    detailRows++;
                    if ("UNKNOWN".equals(classification.semanticKind())) unknownDetailRows++;
                    if ("REVIEW".equals(mappingStatus)) reviewDetailRows++;
                }
                if ("REVIEW".equals(mappingStatus)) reviewRows++;
                if ("INFERRED".equals(mappingStatus)) inferredRows++;
                String signature = rowSignature(code, label);
                String financialNature = deriveFinancialNature(classification, signature, knownFinancialNatureBySignature);
                String cashflowNature = deriveCashflowNature(classification, financialNature);
                if (isFinancialNature(financialNature)) {
                    knownFinancialNatureBySignature.put(signature, financialNature);
                }
                LongRow row = new LongRow(
                    classification.rowType(),
                    code,
                    label,
                    canonicalSemanticKind(financialNature, cashflowNature, classification.semanticKind()),
                    classification.sectionKind(),
                    financialNature,
                    cashflowNature,
                    "ROW-" + rows,
                    rows,
                    monthKey,
                    MONTH_LABELS.getOrDefault(monthKey, monthKey),
                    amount,
                    budgetAmount,
                    actualAmount,
                    forecastAmount,
                    varianceAmount,
                    currency,
                    costCenter,
                    department,
                    classification.confidence(),
                    mappingStatus
                );
                produced++;
                monthSeen.put(monthKey, Boolean.TRUE);
                if (sample.size() < maxSampleRows) sample.add(row);
                appendCsvRow(out, row);
            }
        } catch (Exception ex) {
            List<String> notes = mergeNotes(resolution.notes(), detailRows, unknownDetailRows, reviewRows, inferredRows);
            boolean requiresConfirmation = requiresConfirmation(detailRows, unknownDetailRows, reviewDetailRows, resolution.requiresConfirmation());
            return new Result(List.copyOf(monthSeen.keySet()), labelHeader, produced, sample, requiresConfirmation, notes, out.toString().getBytes(StandardCharsets.UTF_8));
        }

        List<String> notes = mergeNotes(resolution.notes(), detailRows, unknownDetailRows, reviewRows, inferredRows);
        boolean requiresConfirmation = requiresConfirmation(detailRows, unknownDetailRows, reviewDetailRows, resolution.requiresConfirmation());
        return new Result(List.copyOf(monthSeen.keySet()), labelHeader, produced, sample, requiresConfirmation, notes, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void appendCsvRow(StringBuilder out, LongRow row) {
        out.append(row.rowType().name()).append(',');
        out.append(csvEscape(row.code())).append(',');
        out.append(csvEscape(row.label())).append(',');
        out.append(csvEscape(row.semanticKind())).append(',');
        out.append(csvEscape(row.sectionKind())).append(',');
        out.append(csvEscape(row.financialNature())).append(',');
        out.append(csvEscape(row.cashflowNature())).append(',');
        out.append(csvEscape(row.blockId())).append(',');
        out.append(row.sourceRow() == null ? "" : row.sourceRow()).append(',');
        out.append(csvEscape(row.monthKey())).append(',');
        out.append(csvEscape(row.monthLabel())).append(',');
        out.append(numberOrBlank(row.amount())).append(',');
        out.append(numberOrBlank(row.budgetAmount())).append(',');
        out.append(numberOrBlank(row.actualAmount())).append(',');
        out.append(numberOrBlank(row.forecastAmount())).append(',');
        out.append(numberOrBlank(row.varianceAmount())).append(',');
        out.append(csvEscape(row.currency())).append(',');
        out.append(csvEscape(row.costCenter())).append(',');
        out.append(csvEscape(row.department())).append(',');
        out.append(csvEscape(row.confidence())).append(',');
        out.append(csvEscape(row.mappingStatus()));
        out.append('\n');
    }

    private static String numberOrBlank(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private static String rowSignature(String code, String label) {
        return (BudgetSemanticResolver.normalize(code) + "|" + BudgetSemanticResolver.normalize(label)).trim();
    }

    private static String deriveFinancialNature(BudgetCanonicalClassifier.Classification classification,
                                                String signature,
                                                Map<String, String> knownFinancialNatureBySignature) {
        String semanticKind = upper(classification.semanticKind());
        if ("INVENTORY_VARIATION".equals(semanticKind)) {
            return "OPERATING_ADJUSTMENT";
        }
        if (isFinancialNature(semanticKind)) {
            return semanticKind;
        }
        String known = knownFinancialNatureBySignature == null ? null : knownFinancialNatureBySignature.get(signature);
        if (isFinancialNature(known)) {
            return known;
        }
        return "";
    }

    private static String deriveCashflowNature(BudgetCanonicalClassifier.Classification classification, String financialNature) {
        String semanticKind = upper(classification.semanticKind());
        String sectionKind = upper(classification.sectionKind());
        if (!"CASHFLOW".equals(sectionKind)) {
            return "";
        }
        if (Set.of("CASH_INFLOW", "CASH_OUTFLOW", "OPENING_BALANCE", "CLOSING_BALANCE", "FINANCING").contains(semanticKind)) {
            return semanticKind;
        }
        return switch (upper(financialNature)) {
            case "REVENUE" -> "CASH_INFLOW";
            case "OPEX", "CAPEX", "TAX" -> "CASH_OUTFLOW";
            case "DEPRECIATION_AMORTIZATION" -> "";
            case "FINANCING" -> "FINANCING";
            default -> "";
        };
    }

    private static boolean isFinancialNature(String semanticKind) {
        return Set.of("REVENUE", "OPEX", "CAPEX", "DEPRECIATION_AMORTIZATION", "TAX", "FINANCING", "OPERATING_ADJUSTMENT").contains(upper(semanticKind));
    }

    private static String canonicalSemanticKind(String financialNature, String cashflowNature, String fallback) {
        if (fallback != null && !fallback.isBlank() && !"UNKNOWN".equalsIgnoreCase(fallback)) return upper(fallback);
        if (financialNature != null && !financialNature.isBlank()) return upper(financialNature);
        if (cashflowNature != null && !cashflowNature.isBlank()) return upper(cashflowNature);
        return upper(fallback);
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static Map<String, String> detectWideMonthHeaders(List<String> headers) {
        Map<String, String> monthHeader = new LinkedHashMap<>();
        for (String header : headers) {
            if (header == null) continue;
            String monthKey = normalizeMonthName(header);
            if (monthKey != null) {
                monthHeader.put(monthKey, header);
            }
        }
        return monthHeader;
    }

    private static String detectLabelHeader(List<String> headers, List<Map<String, String>> sampleRows) {
        List<String> candidates = headers.stream()
            .filter(Objects::nonNull)
            .limit(6)
            .toList();
        String best = null;
        int bestScore = -1;
        for (String candidate : candidates) {
            int score = 0;
            for (Map<String, String> row : sampleRows) {
                if (row == null) continue;
                String value = clean(row.get(candidate));
                if (value == null) continue;
                String up = value.toUpperCase(Locale.ROOT);
                if (up.contains("TOTAL") || looksLikePartida(up)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return bestScore < 1 ? null : best;
    }

    private static String selectCodeHeader(List<String> headers, String resolvedHeader) {
        if (looksLikeCodeHeader(resolvedHeader)) {
            return resolvedHeader;
        }
        if (headers == null) return resolvedHeader;
        for (String header : headers) {
            if (looksLikeCodeHeader(header)) return header;
        }
        return resolvedHeader;
    }

    private static boolean looksLikeCodeHeader(String header) {
        if (header == null) return false;
        String normalized = BudgetSemanticResolver.normalize(header);
        return normalized.contains("code")
            || normalized.contains("codigo")
            || normalized.contains("cuenta")
            || normalized.endsWith(" id")
            || normalized.startsWith("id ")
            || normalized.contains("plan item")
            || normalized.contains("account");
    }

    private static String findHeaderByTokens(List<String> headers, String... tokens) {
        if (headers == null || tokens == null || tokens.length == 0) return null;
        for (String header : headers) {
            if (header == null) continue;
            String normalized = BudgetSemanticResolver.normalize(header);
            for (String token : tokens) {
                String normalizedToken = BudgetSemanticResolver.normalize(token);
                if (!normalizedToken.isBlank() && normalized.contains(normalizedToken)) {
                    return header;
                }
            }
        }
        return null;
    }

    private record ParsedLabel(String code, String label) {}

    private static ParsedLabel parsePartidaLabel(String rawLabel) {
        if (rawLabel == null) return new ParsedLabel(null, null);
        String s = rawLabel.trim().replaceAll("\\s+", " ");
        if (s.isEmpty()) return new ParsedLabel(null, null);

        Matcher matcher = LEADING_ACCOUNTING_CODE.matcher(s);
        if (matcher.matches()) {
            String code = matcher.group("code");
            String label = cleanParsedLabel(matcher.group("label"));
            return new ParsedLabel(code, label == null ? code : label);
        }

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

    private static String cleanParsedLabel(String rawLabel) {
        if (rawLabel == null) return null;
        String cleaned = rawLabel.trim().replaceAll("^[._-]+", "").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static boolean isPartidaCode(String token) {
        if (token == null) return false;
        String t = token.trim();
        if (t.length() < 2 || t.length() > 30) return false;
        boolean numericStructured = t.matches("^[0-9]{1,4}([\\-.][0-9]{1,4})*$");
        boolean alphaNumericStructured = t.matches("^[A-Za-z0-9]{1,8}([\\-_.][A-Za-z0-9]{1,8})*$")
            && (t.matches(".*\\d.*") || t.contains("-") || t.contains(".") || t.contains("_"));
        return numericStructured || alphaNumericStructured;
    }

    private static boolean looksLikePartida(String upper) {
        if (upper == null) return false;
        String s = upper.trim();
        if (s.length() < 2) return false;
        int space = s.indexOf(' ');
        String first = space > 0 ? s.substring(0, space) : s;
        return isPartidaCode(first);
    }

    private static boolean isCanonicalBudgetLongHeaders(List<String> headers) {
        if (headers == null || headers.isEmpty()) return false;
        Set<String> normalized = new LinkedHashSet<>();
        for (String header : headers) {
            normalized.add(BudgetSemanticResolver.normalize(header));
        }
        return normalized.contains("row type")
            && normalized.contains("semantic kind")
            && normalized.contains("month key")
            && normalized.contains("label");
    }

    private static Result previewCanonicalLongSource(byte[] bytes, char delimiter, int maxSourceRows, int maxSampleRows) {
        List<LongRow> sample = new ArrayList<>();
        Map<String, Boolean> monthSeen = new LinkedHashMap<>();
        long produced = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            CSVParser parser = csvParser(reader, delimiter);
            int rows = 0;
            for (CSVRecord record : parser) {
                rows++;
                if (rows > maxSourceRows) break;

                String rowTypeRaw = clean(get(record, "row_type"));
                String label = clean(get(record, "label"));
                String monthKey = clean(get(record, "month_key"));
                if (rowTypeRaw == null || label == null || monthKey == null) continue;

                RowType rowType;
                try {
                    rowType = RowType.valueOf(rowTypeRaw.trim().toUpperCase(Locale.ROOT));
                } catch (Exception ignored) {
                    continue;
                }

                LongRow row = new LongRow(
                    rowType,
                    clean(get(record, "code")),
                    label,
                    clean(get(record, "semantic_kind")),
                    clean(get(record, "section_kind")),
                    clean(get(record, "financial_nature")),
                    clean(get(record, "cashflow_nature")),
                    firstNonNull(clean(get(record, "block_id")), "ROW-" + rows),
                    parseInteger(clean(get(record, "source_row"))),
                    monthKey,
                    firstNonNull(clean(get(record, "month_label")), MONTH_LABELS.getOrDefault(monthKey, monthKey)),
                    parseMoney(cleanAllowZero(get(record, "amount"))),
                    parseMoney(cleanAllowZero(get(record, "budget_amount"))),
                    parseMoney(cleanAllowZero(get(record, "actual_amount"))),
                    parseMoney(cleanAllowZero(get(record, "forecast_amount"))),
                    parseMoney(cleanAllowZero(get(record, "variance_amount"))),
                    clean(get(record, "currency")),
                    clean(get(record, "cost_center")),
                    clean(get(record, "department")),
                    clean(get(record, "inference_confidence")),
                    firstNonNull(clean(get(record, "mapping_status")), "CANONICAL")
                );
                produced++;
                monthSeen.put(monthKey, Boolean.TRUE);
                if (sample.size() < maxSampleRows) sample.add(row);
            }
        } catch (Exception ex) {
            return new Result(List.of(), "label", 0, List.of(), false, List.of("No se pudo releer el CSV anual canonico."), bytes);
        }

        return new Result(List.copyOf(monthSeen.keySet()), "label", produced, sample, false, List.of(), bytes);
    }

    private static List<String> mergeNotes(List<String> baseNotes, int detailRows, int unknownDetailRows, int reviewRows, int inferredRows) {
        List<String> out = new ArrayList<>();
        if (baseNotes != null) out.addAll(baseNotes);
        if (detailRows == 0) {
            out.add("No hay filas DETAIL fiables en la lectura anual.");
        }
        if (inferredRows > 0) {
            out.add("Hay " + inferredRows + " filas DETAIL inferidas por contexto.");
        }
        if (unknownDetailRows > 0) {
            out.add("Hay " + unknownDetailRows + " filas DETAIL sin semantica suficiente.");
        }
        if (reviewRows > 0) {
            out.add("Hay " + reviewRows + " filas marcadas para revision semantica.");
        }
        return out;
    }

    private static boolean requiresConfirmation(int detailRows, int unknownDetailRows, int reviewDetailRows, boolean headerConfirmation) {
        if (headerConfirmation) return true;
        if (detailRows <= 0) return reviewDetailRows > 0 || unknownDetailRows > 0;
        int unknownThreshold = Math.max(2, (int) Math.ceil(detailRows * 0.12d));
        int reviewThreshold = Math.max(2, (int) Math.ceil(detailRows * 0.18d));
        return unknownDetailRows >= unknownThreshold || reviewDetailRows >= reviewThreshold;
    }

    private static String detectMonthKey(String monthNameRaw, String monthNumberRaw) {
        String byName = normalizeMonthName(monthNameRaw);
        if (byName != null) return byName;
        if (BudgetSemanticResolver.looksLikeMonthNumber(monthNumberRaw)) {
            int number = Integer.parseInt(monthNumberRaw.trim());
            return MONTH_KEYS.get(number - 1);
        }
        return null;
    }

    private static String normalizeMonthName(String raw) {
        if (raw == null) return null;
        String normalized = BudgetSemanticResolver.normalize(raw);
        if (normalized.isBlank()) return null;
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
        if (normalized.matches("^(19|20)\\d{2}\\s?[\\-/]?\\s?(0?[1-9]|1[0-2])$")) {
            int monthNumber = Integer.parseInt(normalized.replaceAll("^(19|20)\\d{2}\\s?[\\-/]?\\s?", ""));
            return MONTH_KEYS.get(monthNumber - 1);
        }
        if (normalized.matches("^(0?[1-9]|1[0-2])\\s?[\\-/]?\\s?(19|20)\\d{2}$")) {
            int monthNumber = Integer.parseInt(normalized.replaceAll("\\s?[\\-/]?\\s?(19|20)\\d{2}$", ""));
            return MONTH_KEYS.get(monthNumber - 1);
        }
        return MONTH_LABELS.containsKey(normalized.toUpperCase(Locale.ROOT)) ? normalized.toUpperCase(Locale.ROOT) : null;
    }

    private static List<String> readHeaders(byte[] bytes, char delimiter) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            CSVParser parser = csvParser(reader, delimiter);
            return new ArrayList<>(parser.getHeaderMap().keySet());
        }
    }

    private static List<Map<String, String>> readSampleRows(byte[] bytes, char delimiter, int maxRows) throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            CSVParser parser = csvParser(reader, delimiter);
            int count = 0;
            for (CSVRecord record : parser) {
                if (count >= maxRows) break;
                Map<String, String> row = new LinkedHashMap<>();
                for (String header : parser.getHeaderMap().keySet()) {
                    row.put(header, get(record, header));
                }
                rows.add(row);
                count++;
            }
        }
        return rows;
    }

    private static CSVParser csvParser(BufferedReader reader, char delimiter) throws Exception {
        return CSVFormat.DEFAULT.builder()
            .setDelimiter(delimiter)
            .setHeader()
            .setSkipHeaderRecord(true)
            .setAllowMissingColumnNames(true)
            .setIgnoreEmptyLines(true)
            .setIgnoreSurroundingSpaces(true)
            .setTrim(true)
            .build()
            .parse(reader);
    }

    private static BigDecimal firstNonNullAmount(BigDecimal... values) {
        if (values == null) return null;
        for (BigDecimal value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private static Integer parseInteger(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String firstNonNull(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
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
        if (s.isBlank() || "-".equals(s)) return null;

        int comma = s.lastIndexOf(',');
        int dot = s.lastIndexOf('.');
        if (comma > dot) {
            s = s.replace(".", "").replace(",", ".");
        } else {
            s = s.replace(",", "");
        }
        if (s.isBlank() || "-".equals(s)) return null;
        try {
            BigDecimal parsed = new BigDecimal(s);
            return negative ? parsed.negate() : parsed;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String csvEscape(String value) {
        if (value == null) return "";
        String s = value;
        boolean needsQuote = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (!needsQuote) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
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
        if (pipes > max) { best = '|'; }
        return best;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) n++;
        }
        return n;
    }
}

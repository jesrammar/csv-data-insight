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
                         byte[] longCsvBytes,
                         String analysisStatus,
                         Integer headerRow1Based,
                         Double headerScore) {}

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
    private static final Set<String> SEMANTIC_HEADER_ALIASES = Set.of(
        "naturaleza", "tipo", "tipo partida", "tipo registro", "categoria", "clasificacion",
        "financial nature", "row type", "nature", "classification", "line type", "category"
    );
    private static final Set<String> CODE_HEADER_ALIASES = Set.of(
        "codigo", "cuenta", "account", "code", "concept code", "plan item", "account code"
    );
    private static final Set<String> DESCRIPTIVE_HEADER_ALIASES = Set.of(
        "concepto", "descripcion", "description", "partida", "label", "concept", "detalle", "nombre"
    );
    private static final Set<String> SECTION_HEADER_ALIASES = Set.of(
        "seccion", "section", "bloque", "area financiera", "financial block"
    );
    private static final Set<String> ROW_ROLE_HEADER_ALIASES = Set.of(
        "clase de registro", "tipo de fila", "tipo fila", "rol de fila", "row role", "row type", "line type", "clase", "registro"
    );
    private static final Set<String> FINANCIAL_GROUP_HEADER_ALIASES = Set.of(
        "grupo financiero", "financial group", "grupo", "family", "financial family", "nature group"
    );
    private static final Set<String> DIRECTION_HEADER_ALIASES = Set.of(
        "sentido", "direction", "sign", "nature sign", "flow direction"
    );
    private static final Set<String> AGGREGATION_POLICY_HEADER_ALIASES = Set.of(
        "criterio de agregacion", "criterio de agregación", "politica de agregacion", "politica de agregación",
        "aggregation policy", "aggregation rule", "rollup policy", "aggregation"
    );
    private static final Set<String> GENERIC_AMOUNT_HEADER_ALIASES = Set.of(
        "importe", "importe eur", "amount", "amount eur", "valor", "value", "importe euro", "monthly amount"
    );

    public static Result normalizeToLongCsv(byte[] normalizedUniversalCsvBytes, int maxSourceRows, int maxSampleRows) {
        return normalizeToLongCsv(normalizedUniversalCsvBytes, null, maxSourceRows, maxSampleRows);
    }

    public static Result normalizeToLongCsv(byte[] normalizedUniversalCsvBytes,
                                            String clientKey,
                                            int maxSourceRows,
                                            int maxSampleRows) {
        if (normalizedUniversalCsvBytes == null || normalizedUniversalCsvBytes.length == 0) {
            return emptyResult();
        }
        if (maxSourceRows < 1) maxSourceRows = 1_000;
        if (maxSourceRows > 50_000) maxSourceRows = 50_000;
        if (maxSampleRows < 0) maxSampleRows = 0;
        if (maxSampleRows > 500) maxSampleRows = 500;

        String head = new String(normalizedUniversalCsvBytes, 0, Math.min(normalizedUniversalCsvBytes.length, 4096), StandardCharsets.UTF_8);
        int eol = head.indexOf('\n');
        if (eol >= 0) head = head.substring(0, eol);
        char delimiter = detectDelimiter(head);

        StructuredInput structured;
        try {
            structured = detectStructure(normalizedUniversalCsvBytes, delimiter, 120);
        } catch (Exception ex) {
            return emptyResult();
        }
        List<String> headers = structured.headers();
        List<Map<String, String>> sampleRows = structured.sampleRows();
        if (headers.isEmpty()) {
            return emptyResult();
        }

        if (isCanonicalBudgetLongHeaders(headers)) {
            return previewCanonicalLongSource(normalizedUniversalCsvBytes, delimiter, maxSourceRows, maxSampleRows, structured.headerRowIndex() + 1, structured.headerScore());
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
                maxSampleRows,
                structured
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
        String sectionHeader = findHeaderByTokens(headers, SECTION_HEADER_ALIASES.toArray(String[]::new));
        String rowRoleHeader = findHeaderByTokens(headers, ROW_ROLE_HEADER_ALIASES.toArray(String[]::new));
        String financialGroupHeader = findHeaderByTokens(headers, FINANCIAL_GROUP_HEADER_ALIASES.toArray(String[]::new));
        String directionHeader = findHeaderByTokens(headers, DIRECTION_HEADER_ALIASES.toArray(String[]::new));
        String aggregationPolicyHeader = findHeaderByTokens(headers, AGGREGATION_POLICY_HEADER_ALIASES.toArray(String[]::new));
        Set<String> excludedHeaders = new LinkedHashSet<>();
        for (String header : Arrays.asList(
            monthNameHeader,
            monthNumberHeader,
            budgetHeader,
            actualHeader,
            forecastHeader,
            varianceHeader,
            labelHeader,
            codeHeader,
            costCenterHeader,
            departmentHeader,
            currencyHeader,
            natureHeader,
            sectionHeader,
            rowRoleHeader,
            financialGroupHeader,
            directionHeader,
            aggregationPolicyHeader
        )) {
            if (header != null) excludedHeaders.add(header);
        }
        String genericAmountHeader = detectGenericAmountHeader(headers, sampleRows, excludedHeaders);

        boolean hasPeriod = monthNameHeader != null || monthNumberHeader != null;
        boolean longSemanticSupport = countNonNull(sectionHeader, rowRoleHeader, financialGroupHeader, directionHeader, aggregationPolicyHeader) >= 2;
        boolean hasAmounts = budgetHeader != null || actualHeader != null || forecastHeader != null || varianceHeader != null
            || (genericAmountHeader != null && longSemanticSupport);
        if (!hasPeriod || !hasAmounts || labelHeader == null) {
            return new Result(
                List.of(),
                labelHeader,
                0,
                List.of(),
                resolution.requiresConfirmation(),
                resolution.notes(),
                new byte[0],
                "INCOMPATIBLE",
                structured.headerRowIndex() + 1,
                structured.headerScore()
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
            genericAmountHeader,
            labelHeader,
            codeHeader,
            natureHeader,
            sectionHeader,
            rowRoleHeader,
            financialGroupHeader,
            directionHeader,
            aggregationPolicyHeader,
            costCenterHeader,
            departmentHeader,
            currencyHeader,
            resolution,
            structured
        );
    }

    private static Result emptyResult() {
        return new Result(List.of(), null, 0, List.of(), false, List.of(), new byte[0], "INCOMPATIBLE", null, null);
    }

    private static Result normalizeWideSource(byte[] bytes,
                                              char delimiter,
                                              List<String> headers,
                                              List<Map<String, String>> sampleRows,
                                              Map<String, String> monthHeader,
                                              String clientKey,
                                              int maxSourceRows,
                                              int maxSampleRows,
                                              StructuredInput structured) {
        BudgetSemanticResolver.Resolution resolution = BudgetSemanticResolver.resolve(headers, sampleRows, clientKey);
        BudgetSemanticResolver.HeaderInference natureInference = BudgetSemanticResolver.detectNatureHeader(headers, sampleRows);
        String labelHeader = firstNonNull(
            resolution.headerFor("CONCEPT_NAME"),
            detectLabelHeader(headers, sampleRows)
        );
        String codeHeader = selectCodeHeader(headers, resolution.headerFor("CONCEPT_CODE"));
        String natureHeader = natureInference == null ? null : natureInference.header();
        String costCenterHeader = resolution.headerFor("COST_CENTER");
        String departmentHeader = resolution.headerFor("DEPARTMENT");
        String currencyHeader = resolution.headerFor("CURRENCY");
        if (labelHeader == null) {
            return new Result(List.copyOf(monthHeader.keySet()), null, 0, List.of(), true, List.of("No se ha podido proponer una columna fiable para la partida."), new byte[0], "INCOMPATIBLE", structured.headerRowIndex() + 1, structured.headerScore());
        }

        List<LongRow> sample = new ArrayList<>();
        long produced = 0;
        int detailRows = 0;
        int unknownDetailRows = 0;
        int reviewRows = 0;
        int reviewDetailRows = 0;
        int inferredRows = 0;
        boolean headerConfirmation = resolution.requiresConfirmation() && natureHeader == null;
        NormalizationContext normalizationContext = NormalizationContext.initial();
        Map<String, String> knownFinancialNatureBySignature = new LinkedHashMap<>();
        StringBuilder out = new StringBuilder(64 * 1024);
        out.append(CANONICAL_HEADER);

        try {
            int rows = 0;
            for (Map<String, String> record : structured.dataRows()) {
                rows++;
                if (rows > maxSourceRows) break;

                String labelRaw = clean(record.get(labelHeader));
                if (labelRaw == null) continue;

                ParsedLabel parsed = parsePartidaLabel(labelRaw);
                String code = firstNonNull(clean(record.get(codeHeader)), parsed.code());
                String natureValue = clean(record.get(natureHeader));
                String costCenter = clean(record.get(costCenterHeader));
                String department = clean(record.get(departmentHeader));
                String currency = clean(record.get(currencyHeader));
                List<BigDecimal> rowValues = new ArrayList<>();
                for (String monthColumn : monthHeader.values()) {
                    rowValues.add(parseMoney(cleanAllowZero(record.get(monthColumn))));
                }
                BudgetCanonicalClassifier.Classification classification = BudgetCanonicalClassifier.classify(
                    clientKey,
                    labelRaw,
                    code,
                    firstNonNull(natureValue, parsed.label()),
                    rowValues,
                    normalizationContext.sectionContext()
                );
                BudgetSemanticResolver.NatureInference explicitNature = BudgetSemanticResolver.classifyBusinessNature(clientKey, natureValue, labelRaw, code);
                classification = applyExplicitNature(classification, explicitNature);
                normalizationContext = normalizationContext.next(classification, explicitNature, labelRaw, rowValues);
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
                String signature = rowSignature(code, parsed.label());
                String financialNature = deriveFinancialNature(classification, signature, knownFinancialNatureBySignature);
                String cashflowNature = deriveCashflowNature(classification, financialNature);
                if (isFinancialNature(financialNature)) {
                    knownFinancialNatureBySignature.put(signature, financialNature);
                }

                for (String mk : MONTH_KEYS) {
                    String monthColumn = monthHeader.get(mk);
                    if (monthColumn == null) continue;
                    BigDecimal budgetAmount = parseMoney(cleanAllowZero(record.get(monthColumn)));
                    if (budgetAmount == null) continue;

                    LongRow row = new LongRow(
                        classification.rowType(),
                        code,
                        parsed.label(),
                        canonicalSemanticKind(financialNature, cashflowNature, classification.semanticKind()),
                        classification.sectionKind(),
                        financialNature,
                        cashflowNature,
                        normalizationContext.currentBlockId(),
                        rows,
                        mk,
                        MONTH_LABELS.getOrDefault(mk, mk),
                        budgetAmount,
                        budgetAmount,
                        null,
                        null,
                        null,
                        currency,
                        costCenter,
                        department,
                        classification.confidence(),
                        mappingStatus
                    );
                    produced++;
                    if (sample.size() < maxSampleRows) sample.add(row);
                    appendCsvRow(out, row);
                }
            }
        } catch (Exception ex) {
            List<String> notes = mergeNotes(resolution.notes(), detailRows, unknownDetailRows, reviewRows, inferredRows);
            boolean requiresConfirmation = requiresConfirmation(detailRows, unknownDetailRows, reviewDetailRows, false);
            return new Result(List.copyOf(monthHeader.keySet()), labelHeader, produced, sample, requiresConfirmation, notes, out.toString().getBytes(StandardCharsets.UTF_8), resultStatus(monthHeader.size(), requiresConfirmation, detailRows), structured.headerRowIndex() + 1, structured.headerScore());
        }

        List<String> notes = mergeNotes(resolution.notes(), detailRows, unknownDetailRows, reviewRows, inferredRows);
        boolean requiresConfirmation = requiresConfirmation(detailRows, unknownDetailRows, reviewDetailRows, false);
        return new Result(List.copyOf(monthHeader.keySet()), labelHeader, produced, sample, requiresConfirmation, notes, out.toString().getBytes(StandardCharsets.UTF_8), resultStatus(monthHeader.size(), requiresConfirmation, detailRows), structured.headerRowIndex() + 1, structured.headerScore());
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
                                              String genericAmountHeader,
                                              String labelHeader,
                                              String codeHeader,
                                              String natureHeader,
                                              String sectionHeader,
                                              String rowRoleHeader,
                                              String financialGroupHeader,
                                              String directionHeader,
                                              String aggregationPolicyHeader,
                                              String costCenterHeader,
                                              String departmentHeader,
                                              String currencyHeader,
                                              BudgetSemanticResolver.Resolution resolution,
                                              StructuredInput structured) {
        List<LongRow> sample = new ArrayList<>();
        Map<String, Boolean> monthSeen = new LinkedHashMap<>();
        long produced = 0;
        int detailRows = 0;
        int unknownDetailRows = 0;
        int reviewRows = 0;
        int reviewDetailRows = 0;
        int inferredRows = 0;
        boolean headerConfirmation = resolution.requiresConfirmation() && natureHeader == null;
        NormalizationContext normalizationContext = NormalizationContext.initial();
        Map<String, String> knownFinancialNatureBySignature = new LinkedHashMap<>();
        StringBuilder out = new StringBuilder(64 * 1024);
        out.append(CANONICAL_HEADER);

        try {
            int rows = 0;
            for (Map<String, String> record : structured.dataRows()) {
                rows++;
                if (rows > maxSourceRows) break;

                String monthKey = detectMonthKey(clean(record.get(monthNameHeader)), clean(record.get(monthNumberHeader)));
                if (monthKey == null) continue;

                BigDecimal genericAmount = parseMoney(cleanAllowZero(record.get(genericAmountHeader)));
                BigDecimal budgetAmount = parseMoney(cleanAllowZero(record.get(budgetHeader)));
                BigDecimal actualAmount = parseMoney(cleanAllowZero(record.get(actualHeader)));
                BigDecimal forecastAmount = parseMoney(cleanAllowZero(record.get(forecastHeader)));
                BigDecimal varianceAmount = parseMoney(cleanAllowZero(record.get(varianceHeader)));
                if (budgetAmount == null && actualAmount == null && forecastAmount == null && varianceAmount == null) {
                    budgetAmount = genericAmount;
                }
                boolean singleAmountLongMode = genericAmountHeader != null
                    || (budgetHeader == null && countNonNull(actualHeader, forecastHeader, varianceHeader) == 1);
                if (singleAmountLongMode && budgetAmount == null) {
                    budgetAmount = firstNonNullAmount(actualAmount, forecastAmount, varianceAmount);
                    actualAmount = null;
                    forecastAmount = null;
                    varianceAmount = null;
                }
                BigDecimal amount = firstNonNullAmount(budgetAmount, actualAmount, forecastAmount, varianceAmount);
                if (amount == null) continue;

                String label = clean(record.get(labelHeader));
                if (label == null) continue;
                String code = clean(record.get(codeHeader));
                String natureValue = clean(record.get(natureHeader));
                String sectionValue = clean(record.get(sectionHeader));
                String rowRoleValue = clean(record.get(rowRoleHeader));
                String financialGroupValue = clean(record.get(financialGroupHeader));
                String directionValue = clean(record.get(directionHeader));
                String aggregationPolicyValue = clean(record.get(aggregationPolicyHeader));
                String costCenter = clean(record.get(costCenterHeader));
                String department = clean(record.get(departmentHeader));
                String currency = clean(record.get(currencyHeader));
                String semanticHints = joinNonBlank(natureValue, financialGroupValue, directionValue, aggregationPolicyValue, sectionValue, rowRoleValue);

                BudgetSemanticResolver.NatureInference nature = BudgetSemanticResolver.classifyBusinessNature(clientKey, semanticHints, label, code);
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
                    firstNonNull(semanticHints, costCenter, department),
                    Arrays.asList(budgetAmount, actualAmount, forecastAmount, varianceAmount),
                    normalizationContext.sectionContext()
                );
                classification = applyExplicitNature(classification, nature);
                classification = applyStructuralHints(classification, sectionValue, rowRoleValue, financialGroupValue, directionValue, aggregationPolicyValue);
                normalizationContext = normalizationContext.next(classification, nature, label, Arrays.asList(budgetAmount, actualAmount, forecastAmount, varianceAmount));
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
                    normalizationContext.currentBlockId(),
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
            boolean requiresConfirmation = requiresConfirmation(detailRows, unknownDetailRows, reviewDetailRows, headerConfirmation);
            return new Result(List.copyOf(monthSeen.keySet()), labelHeader, produced, sample, requiresConfirmation, notes, out.toString().getBytes(StandardCharsets.UTF_8), resultStatus(monthSeen.size(), requiresConfirmation, detailRows), structured.headerRowIndex() + 1, structured.headerScore());
        }

        List<String> notes = mergeNotes(resolution.notes(), detailRows, unknownDetailRows, reviewRows, inferredRows);
        boolean requiresConfirmation = requiresConfirmation(detailRows, unknownDetailRows, reviewDetailRows, headerConfirmation);
        return new Result(List.copyOf(monthSeen.keySet()), labelHeader, produced, sample, requiresConfirmation, notes, out.toString().getBytes(StandardCharsets.UTF_8), resultStatus(monthSeen.size(), requiresConfirmation, detailRows), structured.headerRowIndex() + 1, structured.headerScore());
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

    private record StructuredInput(List<String> headers,
                                   List<Map<String, String>> sampleRows,
                                   List<Map<String, String>> dataRows,
                                   int headerRowIndex,
                                   double headerScore) {}

    private record CandidateHeader(int rowIndex,
                                   List<String> headers,
                                   double score) {}

    private record NormalizationContext(BudgetCanonicalClassifier.SectionContext sectionContext,
                                        String currentBlockId,
                                        String currentSectionKind,
                                        int blockSequence) {
        static NormalizationContext initial() {
            return new NormalizationContext(BudgetCanonicalClassifier.emptyContext(), "BLOCK-1", "UNKNOWN", 1);
        }

        NormalizationContext next(BudgetCanonicalClassifier.Classification classification,
                                  BudgetSemanticResolver.NatureInference explicitNature,
                                  String label,
                                  List<BigDecimal> values) {
            BudgetCanonicalClassifier.SectionContext nextSectionContext = BudgetCanonicalClassifier.nextContext(sectionContext, classification);
            String inferredSection = normalizeSectionKind(classification == null ? null : classification.sectionKind(), explicitNature == null ? null : explicitNature.concept(), label, values);
            String effectiveSection = "UNKNOWN".equals(inferredSection) ? currentSectionKind : inferredSection;
            boolean explicitBoundary = isExplicitBlockBoundary(classification, explicitNature, label, values);
            boolean sectionChanged = !"UNKNOWN".equals(effectiveSection) && !effectiveSection.equals(currentSectionKind);
            if (explicitBoundary || sectionChanged) {
                int nextSequence = blockSequence + 1;
                return new NormalizationContext(nextSectionContext, effectiveSection + "-" + nextSequence, effectiveSection, nextSequence);
            }
            return new NormalizationContext(nextSectionContext, currentBlockId, effectiveSection, blockSequence);
        }
    }

    private static StructuredInput detectStructure(byte[] bytes, char delimiter, int maxSampleRows) throws Exception {
        List<List<String>> rawRows = readRawRows(bytes, delimiter, 160);
        if (rawRows.isEmpty()) {
            return new StructuredInput(List.of(), List.of(), List.of(), 0, 0d);
        }

        CandidateHeader candidate = detectHeaderCandidate(rawRows);
        if (candidate == null || candidate.headers().isEmpty()) {
            return new StructuredInput(List.of(), List.of(), List.of(), 0, 0d);
        }

        List<Map<String, String>> dataRows = new ArrayList<>();
        List<Map<String, String>> sampleRows = new ArrayList<>();
        for (int i = candidate.rowIndex() + 1; i < rawRows.size(); i++) {
            List<String> row = rawRows.get(i);
            if (row == null || row.isEmpty()) continue;
            Map<String, String> mapped = mapRow(candidate.headers(), row);
            if (mapped.values().stream().allMatch(v -> v == null || v.isBlank())) continue;
            if (looksLikeRepeatedHeaderRow(candidate.headers(), row)) continue;
            dataRows.add(mapped);
            if (sampleRows.size() < maxSampleRows) {
                sampleRows.add(mapped);
            }
        }
        return new StructuredInput(candidate.headers(), sampleRows, dataRows, candidate.rowIndex(), candidate.score());
    }

    private static CandidateHeader detectHeaderCandidate(List<List<String>> rawRows) {
        CandidateHeader best = null;
        int limit = Math.min(rawRows.size(), 40);
        for (int rowIndex = 0; rowIndex < limit; rowIndex++) {
            List<String> rawHeader = rawRows.get(rowIndex);
            if (rawHeader == null || rawHeader.isEmpty()) continue;
            List<String> headers = dedupeHeaders(rawHeader);
            double score = headerScore(headers, rawRows, rowIndex);
            if (score <= 0d) continue;
            if (best == null || score > best.score()) {
                best = new CandidateHeader(rowIndex, headers, score);
            }
        }
        return best;
    }

    private static double headerScore(List<String> headers, List<List<String>> rawRows, int rowIndex) {
        int nonBlank = 0;
        int descriptive = 0;
        int codeCols = 0;
        int semanticCols = 0;
        int structuralCols = 0;
        int monthlyCols = 0;
        int periodCols = 0;
        int amountCols = 0;
        Set<String> unique = new LinkedHashSet<>();

        for (String header : headers) {
            String clean = clean(header);
            if (clean == null) continue;
            nonBlank++;
            unique.add(BudgetSemanticResolver.normalize(clean));
            if (normalizeMonthName(clean) != null) monthlyCols++;
            String normalized = BudgetSemanticResolver.normalize(clean);
            if (containsAlias(normalized, DESCRIPTIVE_HEADER_ALIASES)) descriptive++;
            if (containsAlias(normalized, CODE_HEADER_ALIASES)) codeCols++;
            if (containsAlias(normalized, SEMANTIC_HEADER_ALIASES)) semanticCols++;
            if (containsAlias(normalized, SECTION_HEADER_ALIASES)
                || containsAlias(normalized, ROW_ROLE_HEADER_ALIASES)
                || containsAlias(normalized, FINANCIAL_GROUP_HEADER_ALIASES)
                || containsAlias(normalized, DIRECTION_HEADER_ALIASES)
                || containsAlias(normalized, AGGREGATION_POLICY_HEADER_ALIASES)) {
                structuralCols++;
            }
            if (normalized.contains("mes") || normalized.contains("month") || normalized.contains("period")) periodCols++;
            if (normalized.contains("budget") || normalized.contains("presupuesto") || normalized.contains("plan")
                || normalized.contains("actual") || normalized.contains("real") || normalized.contains("forecast")
                || normalized.contains("prevision") || normalized.contains("variance") || normalized.contains("desviacion")
                || normalized.contains("importe") || normalized.contains("amount")) {
                amountCols++;
            }
        }
        if (nonBlank < 2) return 0d;

        double uniqueness = unique.size() / (double) nonBlank;
        double monthlyScore = Math.min(1d, monthlyCols / 12d);
        double descriptiveScore = Math.min(1d, descriptive / 3d);
        double semanticScore = Math.min(1d, (codeCols + semanticCols + structuralCols) / 4d);
        double longScore = Math.min(1d, (periodCols + descriptive + amountCols + codeCols + semanticCols + structuralCols) / 6d);
        double density = downstreamNumericDensity(rawRows, rowIndex, headers, monthlyCols, descriptive > 0 ? 0 : -1);

        double score = Math.max(monthlyScore * 0.42d, longScore * 0.30d)
            + descriptiveScore * 0.16d
            + semanticScore * 0.16d
            + density * 0.18d
            + uniqueness * 0.08d;

        if (monthlyCols >= 6 && descriptive > 0) score += 0.10d;
        if (periodCols > 0 && amountCols > 0 && descriptive > 0) score += 0.08d;
        if (periodCols > 0 && amountCols > 0 && structuralCols >= 2) score += 0.14d;
        if (rowIndex > 0) score -= Math.min(0.12d, rowIndex * 0.01d);
        return Math.max(0d, Math.min(1d, score));
    }

    private static double downstreamNumericDensity(List<List<String>> rawRows, int headerRowIndex, List<String> headers, int monthlyCols, int labelHint) {
        if (rawRows == null || rawRows.isEmpty()) return 0d;
        List<Integer> monthIndexes = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            if (normalizeMonthName(headers.get(i)) != null) monthIndexes.add(i);
        }
        int inspectedRows = 0;
        int numericHits = 0;
        int numericSlots = 0;
        int totalWindow = Math.min(rawRows.size(), headerRowIndex + 13);
        for (int r = headerRowIndex + 1; r < totalWindow; r++) {
            List<String> row = rawRows.get(r);
            if (row == null || row.isEmpty()) continue;
            inspectedRows++;
            if (!monthIndexes.isEmpty()) {
                for (Integer idx : monthIndexes) {
                    if (idx == null || idx < 0 || idx >= row.size()) continue;
                    numericSlots++;
                    if (BudgetSemanticResolver.looksLikeNumericAmount(cleanAllowZero(row.get(idx)))) numericHits++;
                }
            } else {
                for (int c = 0; c < Math.min(headers.size(), row.size()); c++) {
                    if (c == labelHint) continue;
                    String value = cleanAllowZero(row.get(c));
                    if (value == null) continue;
                    numericSlots++;
                    if (BudgetSemanticResolver.looksLikeNumericAmount(value) || BudgetSemanticResolver.looksLikeMonthLikeValue(value) || BudgetSemanticResolver.looksLikeMonthNumber(value)) {
                        numericHits++;
                    }
                }
            }
        }
        if (inspectedRows == 0 || numericSlots == 0) return 0d;
        return numericHits / (double) numericSlots;
    }

    private static boolean looksLikeRepeatedHeaderRow(List<String> headers, List<String> row) {
        if (headers == null || row == null || headers.isEmpty() || row.isEmpty()) return false;
        int comparable = Math.min(headers.size(), row.size());
        int equal = 0;
        for (int i = 0; i < comparable; i++) {
            String left = BudgetSemanticResolver.normalize(headers.get(i));
            String right = BudgetSemanticResolver.normalize(row.get(i));
            if (!left.isBlank() && left.equals(right)) equal++;
        }
        return comparable >= 3 && equal >= Math.max(2, comparable - 1);
    }

    private static List<List<String>> readRawRows(byte[] bytes, char delimiter, int maxRows) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            CSVParser parser = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setIgnoreEmptyLines(false)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .build()
                .parse(reader);
            int count = 0;
            for (CSVRecord record : parser) {
                if (count >= maxRows) break;
                List<String> row = new ArrayList<>();
                for (int i = 0; i < record.size(); i++) {
                    row.add(record.get(i));
                }
                rows.add(row);
                count++;
            }
        }
        return rows;
    }

    private static List<String> dedupeHeaders(List<String> rawHeader) {
        List<String> headers = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        int width = Math.min(rawHeader.size(), 200);
        for (int i = 0; i < width; i++) {
            String header = cleanAllowZero(rawHeader.get(i));
            if (header == null) header = "col_" + (i + 1);
            Integer count = seen.get(header);
            if (count == null) {
                seen.put(header, 1);
                headers.add(header);
            } else {
                int next = count + 1;
                seen.put(header, next);
                headers.add(header + "_" + next);
            }
        }
        return headers;
    }

    private static Map<String, String> mapRow(List<String> headers, List<String> rawRow) {
        Map<String, String> mapped = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            mapped.put(headers.get(i), i < rawRow.size() ? rawRow.get(i) : "");
        }
        return mapped;
    }

    private static boolean containsAlias(String normalizedHeader, Set<String> aliases) {
        if (normalizedHeader == null || normalizedHeader.isBlank() || aliases == null) return false;
        for (String alias : aliases) {
            String normalizedAlias = BudgetSemanticResolver.normalize(alias);
            if (!normalizedAlias.isBlank() && normalizedHeader.contains(normalizedAlias)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> sampleValues(List<Map<String, String>> sampleRows, String header, int limit) {
        if (header == null || sampleRows == null || sampleRows.isEmpty()) return List.of();
        List<String> values = new ArrayList<>();
        for (Map<String, String> row : sampleRows) {
            if (row == null) continue;
            values.add(cleanAllowZero(row.get(header)));
            if (values.size() >= limit) break;
        }
        return values;
    }

    private static int countNonNull(String... values) {
        if (values == null) return 0;
        int count = 0;
        for (String value : values) {
            if (value != null && !value.isBlank()) count++;
        }
        return count;
    }

    private static String joinNonBlank(String... values) {
        if (values == null || values.length == 0) return null;
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            if (!out.isEmpty()) out.append(" | ");
            out.append(value.trim());
        }
        return out.isEmpty() ? null : out.toString();
    }

    private static String detectGenericAmountHeader(List<String> headers,
                                                    List<Map<String, String>> sampleRows,
                                                    Set<String> excludedHeaders) {
        if (headers == null || headers.isEmpty()) return null;
        String best = null;
        double bestScore = 0d;
        for (String header : headers) {
            if (header == null || (excludedHeaders != null && excludedHeaders.contains(header))) continue;
            String normalized = BudgetSemanticResolver.normalize(header);
            if (normalized.isBlank()) continue;
            List<String> values = sampleValues(sampleRows, header, 120);
            long nonBlank = values.stream().filter(Objects::nonNull).map(String::trim).filter(v -> !v.isBlank()).count();
            if (nonBlank == 0) continue;
            long numeric = values.stream().filter(BudgetSemanticResolver::looksLikeNumericAmount).count();
            double numericRatio = numeric / (double) nonBlank;
            double aliasScore = containsAlias(normalized, GENERIC_AMOUNT_HEADER_ALIASES) ? 0.72d : 0d;
            double score = aliasScore + (numericRatio * 0.45d);
            if ((normalized.contains("importe") || normalized.contains("amount") || normalized.contains("valor")) && numericRatio >= 0.75d) {
                score += 0.18d;
            }
            if (score >= 0.70d && score > bestScore) {
                best = header;
                bestScore = score;
            }
        }
        return best;
    }

    private static BudgetCanonicalClassifier.Classification applyExplicitNature(BudgetCanonicalClassifier.Classification base,
                                                                               BudgetSemanticResolver.NatureInference explicitNature) {
        if (base == null) return null;
        if (explicitNature == null || "UNKNOWN".equalsIgnoreCase(explicitNature.concept()) || explicitNature.score() < 0.58d) {
            return base;
        }
        String concept = mapExplicitNature(explicitNature.concept());
        String sectionKind = normalizeSectionKind(base.sectionKind(), concept, null, null);
        return new BudgetCanonicalClassifier.Classification(
            base.rowType(),
            concept,
            sectionKind,
            strongerConfidence(base.confidence(), explicitNature.confidence()),
            explicitNature.ambiguous() && base.ambiguous()
        );
    }

    private static BudgetCanonicalClassifier.Classification applyStructuralHints(BudgetCanonicalClassifier.Classification base,
                                                                                 String sectionValue,
                                                                                 String rowRoleValue,
                                                                                 String financialGroupValue,
                                                                                 String directionValue,
                                                                                 String aggregationPolicyValue) {
        if (base == null) return null;
        RowType hintedRowType = normalizeRowRole(rowRoleValue, aggregationPolicyValue, base.rowType());
        String hintedSemantic = normalizeStructuralSemantic(financialGroupValue, directionValue, aggregationPolicyValue, base.semanticKind());
        String hintedSection = normalizeStructuralSection(sectionValue, financialGroupValue, hintedSemantic, base.sectionKind());
        String confidence = hintedRowType != base.rowType()
            || !upper(hintedSemantic).equals(upper(base.semanticKind()))
            || !upper(hintedSection).equals(upper(base.sectionKind()))
            ? strongerConfidence(base.confidence(), "HIGH")
            : base.confidence();
        return new BudgetCanonicalClassifier.Classification(
            hintedRowType,
            hintedSemantic,
            hintedSection,
            confidence,
            base.ambiguous()
        );
    }

    private static String mapExplicitNature(String concept) {
        return switch (upper(concept)) {
            case "OTHER_OPERATING_INCOME" -> "REVENUE";
            case "FINANCIAL_RESULT" -> "FINANCING";
            default -> upper(concept);
        };
    }

    private static RowType normalizeRowRole(String rowRoleValue, String aggregationPolicyValue, RowType fallback) {
        String normalizedRole = BudgetSemanticResolver.normalize(rowRoleValue);
        String normalizedPolicy = BudgetSemanticResolver.normalize(aggregationPolicyValue);
        if (normalizedRole.contains("detail") || normalizedRole.contains("detalle") || normalizedRole.contains("linea")) return RowType.DETAIL;
        if (normalizedRole.contains("subtotal")) return RowType.SUBTOTAL;
        if (normalizedRole.equals("total") || normalizedRole.contains(" total")) return RowType.TOTAL;
        if (normalizedRole.contains("indicador") || normalizedRole.contains("kpi") || normalizedRole.contains("ratio")) return RowType.DERIVED_KPI;
        if (normalizedRole.contains("saldo")) return RowType.DERIVED_KPI;
        if (normalizedRole.contains("nota") || normalizedRole.contains("note")) return RowType.ASSUMPTION;
        if (normalizedPolicy.contains("derived") || normalizedPolicy.contains("last value") || normalizedPolicy.contains("lastvalue")) return RowType.DERIVED_KPI;
        if ("none".equals(normalizedPolicy)) return RowType.ASSUMPTION;
        return fallback;
    }

    private static String normalizeStructuralSemantic(String financialGroupValue,
                                                      String directionValue,
                                                      String aggregationPolicyValue,
                                                      String fallback) {
        String combined = joinNonBlank(financialGroupValue, directionValue, aggregationPolicyValue);
        BudgetSemanticResolver.NatureInference inference = BudgetSemanticResolver.classifyBusinessNature(null, combined);
        if (inference != null && inference.concept() != null && !"UNKNOWN".equalsIgnoreCase(inference.concept()) && inference.score() >= 0.58d) {
            return mapExplicitNature(inference.concept());
        }
        return upper(fallback);
    }

    private static String normalizeStructuralSection(String sectionValue,
                                                     String financialGroupValue,
                                                     String semanticKind,
                                                     String fallback) {
        String normalizedSection = BudgetSemanticResolver.normalize(sectionValue);
        if (normalizedSection.contains("explotacion") || normalizedSection.contains("resultado") || normalizedSection.contains("profit and loss")) {
            return "P_AND_L";
        }
        if (normalizedSection.contains("caja") || normalizedSection.contains("tesoreria") || normalizedSection.contains("cash") || normalizedSection.contains("banco")) {
            return "CASHFLOW";
        }
        if (normalizedSection.contains("balance")) {
            return "BALANCE";
        }
        if (normalizedSection.contains("nota") || normalizedSection.contains("informacion") || normalizedSection.contains("notes")) {
            return "NOTES";
        }
        String normalizedGroup = BudgetSemanticResolver.normalize(financialGroupValue);
        if (normalizedGroup.contains("cash") || normalizedGroup.contains("saldo")) {
            return "CASHFLOW";
        }
        return normalizeSectionKind(fallback, semanticKind, financialGroupValue, null);
    }

    private static String normalizeSectionKind(String currentSection, String semanticKind, String label, List<BigDecimal> values) {
        String explicit = upper(currentSection);
        if (!explicit.isBlank() && !"UNKNOWN".equals(explicit)) {
            return explicit;
        }
        String semantic = upper(mapExplicitNature(semanticKind));
        if (Set.of("CASH_INFLOW", "CASH_OUTFLOW", "OPENING_BALANCE", "CLOSING_BALANCE").contains(semantic)) {
            return "CASHFLOW";
        }
        if ("ASSUMPTION".equals(semantic)) {
            return "NOTES";
        }
        if (Set.of("REVENUE", "OPEX", "OPERATING_ADJUSTMENT", "DEPRECIATION_AMORTIZATION", "FINANCING", "TAX", "CAPEX").contains(semantic)) {
            return "P_AND_L";
        }
        String normalizedLabel = BudgetSemanticResolver.normalize(label);
        if (normalizedLabel.contains("saldo inicial") || normalizedLabel.contains("saldo final") || normalizedLabel.contains("tesoreria") || normalizedLabel.contains("cash flow")) {
            return "CASHFLOW";
        }
        if (looksStructurallyNote(values, normalizedLabel)) {
            return "NOTES";
        }
        return "UNKNOWN";
    }

    private static boolean isExplicitBlockBoundary(BudgetCanonicalClassifier.Classification classification,
                                                   BudgetSemanticResolver.NatureInference explicitNature,
                                                   String label,
                                                   List<BigDecimal> values) {
        if (classification == null) return false;
        if (classification.rowType() == RowType.TEXT || classification.rowType() == RowType.ASSUMPTION) return true;
        if (classification.rowType() != RowType.DETAIL && !classification.sectionKind().equals("UNKNOWN")) return true;
        String explicitConcept = explicitNature == null ? null : explicitNature.concept();
        return !"UNKNOWN".equals(normalizeSectionKind(classification.sectionKind(), explicitConcept, label, values))
            && !normalizeSectionKind(classification.sectionKind(), explicitConcept, label, values).equals("P_AND_L")
            && classification.rowType() != RowType.DETAIL;
    }

    private static boolean looksStructurallyNote(List<BigDecimal> values, String normalizedLabel) {
        boolean noNumbers = values == null || values.stream().allMatch(Objects::isNull);
        return noNumbers && (normalizedLabel.contains("nota") || normalizedLabel.contains("coment") || normalizedLabel.contains("observ"));
    }

    private static String strongerConfidence(String left, String right) {
        List<String> order = List.of("LOW", "MEDIUM", "HIGH");
        int leftIdx = order.indexOf(upper(left));
        int rightIdx = order.indexOf(upper(right));
        return leftIdx >= rightIdx ? upper(left) : upper(right);
    }

    private static String resultStatus(int monthsDetected, boolean requiresConfirmation, int detailRows) {
        if (monthsDetected <= 0 || detailRows <= 0) return "INCOMPATIBLE";
        return requiresConfirmation ? "GUIDED_REVIEW_REQUIRED" : "AUTOMATIC_ACCEPTED";
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

    private static Result previewCanonicalLongSource(byte[] bytes,
                                                     char delimiter,
                                                     int maxSourceRows,
                                                     int maxSampleRows,
                                                     Integer headerRow1Based,
                                                     Double headerScore) {
        List<LongRow> sample = new ArrayList<>();
        Map<String, Boolean> monthSeen = new LinkedHashMap<>();
        long produced = 0;
        int detailRows = 0;
        int reviewRows = 0;

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
                if (rowType == RowType.DETAIL) {
                    detailRows++;
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
                if ("REVIEW".equals(row.mappingStatus())) {
                    reviewRows++;
                }
                produced++;
                monthSeen.put(monthKey, Boolean.TRUE);
                if (sample.size() < maxSampleRows) sample.add(row);
            }
        } catch (Exception ex) {
            return new Result(List.of(), "label", 0, List.of(), false, List.of("No se pudo releer el CSV anual canonico."), bytes, "INCOMPATIBLE", headerRow1Based, headerScore);
        }

        boolean requiresConfirmation = reviewRows > 0;
        return new Result(
            List.copyOf(monthSeen.keySet()),
            "label",
            produced,
            sample,
            requiresConfirmation,
            List.of(),
            bytes,
            resultStatus(monthSeen.size(), requiresConfirmation, detailRows),
            headerRow1Based,
            headerScore
        );
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
        if (normalized.matches("^(19|20)\\d{2}[\\-/](0?[1-9]|1[0-2])[\\-/](0?[1-9]|[12]\\d|3[01])$")) {
            Matcher matcher = Pattern.compile("^(19|20)\\d{2}[\\-/](0?[1-9]|1[0-2])[\\-/](0?[1-9]|[12]\\d|3[01])$").matcher(normalized);
            if (matcher.matches()) {
                return MONTH_KEYS.get(Integer.parseInt(matcher.group(2)) - 1);
            }
        }
        if (normalized.matches("^(19|20)\\d{2}\\s+(0?[1-9]|1[0-2])\\s+(0?[1-9]|[12]\\d|3[01])$")) {
            Matcher matcher = Pattern.compile("^(19|20)\\d{2}\\s+(0?[1-9]|1[0-2])\\s+(0?[1-9]|[12]\\d|3[01])$").matcher(normalized);
            if (matcher.matches()) {
                return MONTH_KEYS.get(Integer.parseInt(matcher.group(2)) - 1);
            }
        }
        if (normalized.matches("^(0?[1-9]|1[0-2])\\s?[\\-/]?\\s?(19|20)\\d{2}$")) {
            int monthNumber = Integer.parseInt(normalized.replaceAll("\\s?[\\-/]?\\s?(19|20)\\d{2}$", ""));
            return MONTH_KEYS.get(monthNumber - 1);
        }
        if (normalized.matches("^(0?[1-9]|[12]\\d|3[01])[\\-/](0?[1-9]|1[0-2])[\\-/](19|20)\\d{2}$")) {
            Matcher matcher = Pattern.compile("^(0?[1-9]|[12]\\d|3[01])[\\-/](0?[1-9]|1[0-2])[\\-/](19|20)\\d{2}$").matcher(normalized);
            if (matcher.matches()) {
                return MONTH_KEYS.get(Integer.parseInt(matcher.group(2)) - 1);
            }
        }
        if (normalized.matches("^(0?[1-9]|[12]\\d|3[01])\\s+(0?[1-9]|1[0-2])\\s+(19|20)\\d{2}$")) {
            Matcher matcher = Pattern.compile("^(0?[1-9]|[12]\\d|3[01])\\s+(0?[1-9]|1[0-2])\\s+(19|20)\\d{2}$").matcher(normalized);
            if (matcher.matches()) {
                return MONTH_KEYS.get(Integer.parseInt(matcher.group(2)) - 1);
            }
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

        s = s.replaceAll("\\[\\$[^\\]]*\\]", "");
        s = s.replace("\u00A0", "").replace(" ", "");
        s = s.replaceAll("[^0-9,\\.]", "");
        if (s.isBlank()) return null;

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

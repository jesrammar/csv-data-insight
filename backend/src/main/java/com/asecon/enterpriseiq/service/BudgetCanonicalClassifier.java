package com.asecon.enterpriseiq.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class BudgetCanonicalClassifier {
    private static final Set<String> AGGREGATE_NOISE_TOKENS = Set.of("de", "del", "la", "el", "los", "las", "y", "and");
    private static final Set<String> SUBTOTAL_MARKERS = normalizedSet(Set.of(
        "ingreso de explotacion",
        "ingreso explotacion",
        "gasto variable",
        "gasto de explotacion",
        "otro gasto de explotacion",
        "coste de explotacion",
        "cash inflow",
        "cash outflow",
        "cobro",
        "pago",
        "tesoreria",
        "liquidez"
    ));
    private static final Set<String> DERIVED_MARKERS = normalizedSet(Set.of(
        "ebitda",
        "ebit",
        "margen",
        "resultado",
        "beneficio",
        "cash flow",
        "cashflow",
        "saldo final",
        "saldo inicial",
        "tesoreria final",
        "tesoreria inicial",
        "liquidez final",
        "liquidez inicial"
    ));
    private static final Set<String> GENERIC_REVENUE_AGGREGATES = normalizedSet(Set.of(
        "ingreso",
        "ingresos",
        "ingreso por servicios",
        "ingresos por servicios",
        "ingreso de explotacion",
        "ingresos de explotacion",
        "facturacion",
        "revenue",
        "sales",
        "turnover"
    ));
    private static final Set<String> GENERIC_OPEX_AGGREGATES = normalizedSet(Set.of(
        "gasto operativo",
        "gastos operativos",
        "coste operativo",
        "costes operativos",
        "coste operacional",
        "costes operacionales",
        "gastos generales",
        "gastos de explotacion",
        "costes de explotacion",
        "operating expense",
        "operating expenses",
        "operating cost",
        "operating costs",
        "total operating costs",
        "total de costes"
    ));

    private BudgetCanonicalClassifier() {}

    public static SectionContext emptyContext() {
        return new SectionContext("UNKNOWN", "UNKNOWN");
    }

    public static Classification classify(String clientKey,
                                          String label,
                                          String code,
                                          String categoryRaw,
                                          List<BigDecimal> numericValues,
                                          SectionContext context) {
        String normalizedLabel = BudgetSemanticResolver.normalize(label);
        String normalizedCategory = BudgetSemanticResolver.normalize(categoryRaw);
        boolean hasAmounts = numericValues != null && numericValues.stream().anyMatch(Objects::nonNull);
        boolean hasCode = code != null && !code.isBlank();

        BudgetSemanticResolver.NatureInference semantic = BudgetSemanticResolver.classifyRowSemantic(clientKey, categoryRaw, label, code);
        String semanticKind = semantic.concept();
        String sectionKind = sectionKindFor(semanticKind);
        BudgetLongNormalizer.RowType rowType = inferRowType(normalizedLabel, code, hasCode, hasAmounts, semanticKind);
        boolean ambiguous = semantic.ambiguous();
        String confidence = semantic.confidence();

        Classification explicit = explicitSemantic(normalizedLabel, normalizedCategory, rowType);
        if (explicit != null) {
            rowType = explicit.rowType();
            semanticKind = explicit.semanticKind();
            sectionKind = explicit.sectionKind();
            confidence = explicit.confidence();
            ambiguous = explicit.ambiguous();
        }

        if (rowType == BudgetLongNormalizer.RowType.DETAIL && looksLikeDepreciationCharge(normalizedLabel, code)) {
            semanticKind = "DEPRECIATION_AMORTIZATION";
            sectionKind = "P_AND_L";
            confidence = "HIGH";
            ambiguous = false;
        }

        if (looksLikeFinancingAggregate(normalizedLabel, code)) {
            rowType = BudgetLongNormalizer.RowType.SUBTOTAL;
            semanticKind = "FINANCING";
            sectionKind = "P_AND_L";
            confidence = "HIGH";
            ambiguous = false;
        }

        boolean explicitSectionChange = explicit != null && !"UNKNOWN".equals(upper(explicit.sectionKind()));

        if (!explicitSectionChange
            && context != null
            && "CASHFLOW".equals(upper(context.sectionKind()))
            && rowType != BudgetLongNormalizer.RowType.ASSUMPTION) {
            sectionKind = "CASHFLOW";
            if ("UNKNOWN".equals(confidence)) {
                confidence = "LOW";
            }
        }

        if ("UNKNOWN".equals(semanticKind) && rowType == BudgetLongNormalizer.RowType.DETAIL && context != null && !"UNKNOWN".equals(context.semanticKind())) {
            sectionKind = context.sectionKind();
            if (isCarryableContextSemantic(context.semanticKind())) {
                if ("REVENUE".equals(upper(context.semanticKind())) && hasCode && !looksLikeRevenueEvidence(normalizedLabel, code)) {
                    semanticKind = "OPEX";
                } else {
                    semanticKind = context.semanticKind();
                }
            }
            confidence = "LOW";
            ambiguous = true;
        }

        if (rowType == BudgetLongNormalizer.RowType.DETAIL
            && "FINANCING".equals(upper(semanticKind))
            && context != null
            && "P_AND_L".equals(upper(context.sectionKind()))
            && "OPEX".equals(upper(context.semanticKind()))
            && looksLikeFinancingExpense(normalizedLabel, code)) {
            semanticKind = "OPEX";
            sectionKind = "P_AND_L";
            confidence = "MEDIUM";
            ambiguous = true;
        }

        if ("UNKNOWN".equals(sectionKind)) {
            if (rowType == BudgetLongNormalizer.RowType.ASSUMPTION) {
                sectionKind = "ASSUMPTION";
            } else if (context != null && rowType == BudgetLongNormalizer.RowType.DETAIL) {
                sectionKind = context.sectionKind();
            }
        }

        if ("UNKNOWN".equals(semanticKind) && rowType == BudgetLongNormalizer.RowType.ASSUMPTION) {
            semanticKind = "ASSUMPTION";
            sectionKind = "ASSUMPTION";
            confidence = "MEDIUM";
            ambiguous = false;
        }

        if ("UNKNOWN".equals(semanticKind) && rowType == BudgetLongNormalizer.RowType.DERIVED_KPI && sectionKind.equals("UNKNOWN")) {
            sectionKind = "P_AND_L";
        }

        if (rowType == BudgetLongNormalizer.RowType.TEXT && !hasAmounts) {
            confidence = "LOW";
            ambiguous = true;
        }

        return new Classification(rowType, semanticKind, sectionKind, confidence, ambiguous);
    }

    public static SectionContext nextContext(SectionContext current, Classification classification) {
        if (classification == null) return current == null ? emptyContext() : current;
        if (classification.rowType() == BudgetLongNormalizer.RowType.DETAIL) {
            return current == null ? emptyContext() : current;
        }
        if ("UNKNOWN".equals(classification.semanticKind()) && "UNKNOWN".equals(classification.sectionKind())) {
            return current == null ? emptyContext() : current;
        }
        if (Set.of("OPENING_BALANCE", "CLOSING_BALANCE").contains(upper(classification.semanticKind()))) {
            return new SectionContext(current == null ? "UNKNOWN" : current.semanticKind(), classification.sectionKind());
        }
        return new SectionContext(
            "UNKNOWN".equals(classification.semanticKind()) ? (current == null ? "UNKNOWN" : current.semanticKind()) : classification.semanticKind(),
            "UNKNOWN".equals(classification.sectionKind()) ? (current == null ? "UNKNOWN" : current.sectionKind()) : classification.sectionKind()
        );
    }

    public static String classifyZeroInterpretation(String semanticKind, String label, List<BigDecimal> values) {
        if (values == null || values.isEmpty()) return "NOT_APPLICABLE";
        if ("INVENTORY_VARIATION".equals(upper(semanticKind))) return "ACCOUNTING_ADJUSTMENT";
        if (Set.of("CAPEX", "DEPRECIATION_AMORTIZATION", "TAX", "FINANCING", "OPENING_BALANCE", "CLOSING_BALANCE", "ASSUMPTION").contains(upper(semanticKind))) {
            return "NOT_APPLICABLE";
        }

        int zeroMonths = 0;
        int nonZeroMonths = 0;
        BigDecimal maxAbs = BigDecimal.ZERO;
        BigDecimal annualAbs = BigDecimal.ZERO;
        int firstNonZero = -1;
        int lastNonZero = -1;
        for (int i = 0; i < values.size(); i++) {
            BigDecimal value = values.get(i);
            if (value == null || value.compareTo(BigDecimal.ZERO) == 0) {
                zeroMonths++;
                continue;
            }
            nonZeroMonths++;
            BigDecimal abs = value.abs();
            annualAbs = annualAbs.add(abs);
            if (abs.compareTo(maxAbs) > 0) maxAbs = abs;
            if (firstNonZero < 0) firstNonZero = i;
            lastNonZero = i;
        }

        if (annualAbs.compareTo(BigDecimal.ZERO) == 0) return "NO_ACTIVITY";
        if (nonZeroMonths <= 1) return "SEASONAL";

        BigDecimal peakShare = maxAbs.multiply(BigDecimal.valueOf(100)).divide(annualAbs, 4, java.math.RoundingMode.HALF_UP);
        String normalizedLabel = BudgetSemanticResolver.normalize(label);
        boolean recurring = normalizedLabel.contains("nomina")
            || normalizedLabel.contains("salario")
            || normalizedLabel.contains("sueldo")
            || normalizedLabel.contains("alquiler")
            || normalizedLabel.contains("seguridad social")
            || normalizedLabel.contains("rent")
            || normalizedLabel.contains("payroll")
            || normalizedLabel.contains("subscription");

        if (zeroMonths >= 8 && peakShare.compareTo(new BigDecimal("55")) >= 0) {
            return "SEASONAL";
        }
        if (recurring && zeroMonths >= 1) {
            return "POSSIBLE_MISSING_DATA";
        }
        if (firstNonZero >= 0 && lastNonZero >= 0 && (lastNonZero - firstNonZero) <= Math.max(1, nonZeroMonths)) {
            return "SEASONAL";
        }
        return zeroMonths >= 4 ? "POSSIBLE_MISSING_DATA" : "NOT_APPLICABLE";
    }

    private static BudgetLongNormalizer.RowType inferRowType(String normalizedLabel,
                                                             String code,
                                                             boolean hasCode,
                                                             boolean hasAmounts,
                                                             String semanticKind) {
        if (normalizedLabel == null || normalizedLabel.isBlank()) {
            return hasAmounts ? BudgetLongNormalizer.RowType.DETAIL : BudgetLongNormalizer.RowType.TEXT;
        }
        if (looksLikeTotal(normalizedLabel)) return BudgetLongNormalizer.RowType.TOTAL;
        if (looksLikeDerived(normalizedLabel, semanticKind)) return BudgetLongNormalizer.RowType.DERIVED_KPI;
        if (looksLikeAssumption(normalizedLabel) && !isFinancialSemantic(semanticKind)) return BudgetLongNormalizer.RowType.ASSUMPTION;
        if (looksLikeOrdinalHeading(normalizedLabel, hasCode)) return BudgetLongNormalizer.RowType.SUBTOTAL;
        if (looksLikeGroupedCode(code)) return BudgetLongNormalizer.RowType.SUBTOTAL;
        if (looksLikeSubtotal(normalizedLabel, semanticKind, hasCode)) return BudgetLongNormalizer.RowType.SUBTOTAL;
        if (hasCode) return BudgetLongNormalizer.RowType.DETAIL;
        if (!hasAmounts) return BudgetLongNormalizer.RowType.TEXT;
        if (isFinancialSemantic(semanticKind)) {
            return genericAggregateLabel(normalizedLabel, semanticKind)
                ? BudgetLongNormalizer.RowType.SUBTOTAL
                : BudgetLongNormalizer.RowType.DETAIL;
        }
        return genericAggregateLabel(normalizedLabel, semanticKind)
            ? BudgetLongNormalizer.RowType.SUBTOTAL
            : BudgetLongNormalizer.RowType.DETAIL;
    }

    private static Classification explicitSemantic(String normalizedLabel, String normalizedCategory, BudgetLongNormalizer.RowType rowType) {
        String merged = (normalizedLabel + " " + normalizedCategory).trim();
        if (merged.isBlank()) return null;

        if (looksLikeCashflowSectionHeader(merged)) {
            return new Classification(BudgetLongNormalizer.RowType.SUBTOTAL, "UNKNOWN", "CASHFLOW", "HIGH", false);
        }
        if (looksLikeInventoryVariation(merged, normalizedLabel)) {
            return new Classification(rowType, "INVENTORY_VARIATION", "P_AND_L", "HIGH", false);
        }

        if (merged.contains("saldo inicial") || merged.contains("opening balance") || merged.contains("initial balance")) {
            return new Classification(rowType == BudgetLongNormalizer.RowType.DETAIL ? BudgetLongNormalizer.RowType.TOTAL : rowType, "OPENING_BALANCE", "CASHFLOW", "HIGH", false);
        }
        if (merged.contains("saldo final") || merged.contains("closing balance") || merged.contains("ending balance")) {
            return new Classification(rowType == BudgetLongNormalizer.RowType.DETAIL ? BudgetLongNormalizer.RowType.TOTAL : rowType, "CLOSING_BALANCE", "CASHFLOW", "HIGH", false);
        }
        if (merged.contains("cobro") || merged.contains("cash inflow") || merged.contains("collection") || merged.contains("receipt")) {
            return new Classification(rowType, "CASH_INFLOW", "CASHFLOW", "MEDIUM", false);
        }
        if (merged.contains("pago") || merged.contains("cash outflow") || merged.contains("payment") || merged.contains("disbursement")) {
            return new Classification(rowType, "CASH_OUTFLOW", "CASHFLOW", "MEDIUM", false);
        }
        if (looksLikePriorYearCustomerCarry(merged)) {
            return new Classification(rowType, "CASH_INFLOW", "CASHFLOW", "HIGH", false);
        }
        if (looksLikePriorYearSupplierCarry(merged)) {
            return new Classification(rowType, "CASH_OUTFLOW", "CASHFLOW", "HIGH", false);
        }
        if (merged.equals("gastos financieros") || merged.equals("financial expenses")
            || merged.equals("ingresos financieros") || merged.equals("financial income")) {
            return new Classification(BudgetLongNormalizer.RowType.SUBTOTAL, "FINANCING", "P_AND_L", "HIGH", false);
        }
        if (merged.contains("resultado financiero")
            || merged.contains("financial result")
            || merged.contains("resultado fin")
            || merged.contains("net finance")) {
            return new Classification(BudgetLongNormalizer.RowType.TOTAL, "FINANCING", "P_AND_L", "HIGH", false);
        }
        if (merged.contains("impuesto") || merged.contains("tax") || merged.contains("iva") || merged.contains("vat")) {
            return new Classification(rowType, "TAX", "P_AND_L", "MEDIUM", false);
        }
        if (looksLikeAssumption(merged)) {
            return new Classification(BudgetLongNormalizer.RowType.ASSUMPTION, "ASSUMPTION", "ASSUMPTION", "MEDIUM", false);
        }
        return null;
    }

    private static boolean looksLikeCashflowSectionHeader(String merged) {
        return containsAll(merged, "cash", "flow")
            || containsAll(merged, "cuenta", "tesoreria")
            || containsAll(merged, "cashflow", "financiero")
            || containsAll(merged, "cash", "treasury")
            || merged.equals("tesoreria")
            || merged.equals("liquidez");
    }

    private static boolean looksLikeInventoryVariation(String merged, String normalizedLabel) {
        String sample = (merged + " " + normalizedLabel).trim();
        return containsAll(sample, "variacion", "existencias")
            || containsAll(sample, "variacion", "stock")
            || containsAll(sample, "variation", "inventory")
            || containsAll(sample, "change", "inventory")
            || containsAll(sample, "stock", "variation");
    }

    private static boolean looksLikePriorYearCustomerCarry(String merged) {
        return looksLikePriorYearCarry(merged)
            && (containsToken(merged, "cliente")
                || containsToken(merged, "clientes")
                || containsToken(merged, "deudor")
                || containsToken(merged, "deudores")
                || containsAll(merged, "accounts", "receivable")
                || containsAll(merged, "trade", "receivable"));
    }

    private static boolean looksLikePriorYearSupplierCarry(String merged) {
        return looksLikePriorYearCarry(merged)
            && (containsToken(merged, "proveedor")
                || containsToken(merged, "proveedores")
                || containsToken(merged, "acreedor")
                || containsToken(merged, "acreedores")
                || containsAll(merged, "accounts", "payable")
                || containsAll(merged, "trade", "payable")
                || containsToken(merged, "supplier")
                || containsToken(merged, "suppliers")
                || containsToken(merged, "vendor")
                || containsToken(merged, "vendors"));
    }

    private static boolean looksLikePriorYearCarry(String merged) {
        return containsAll(merged, "ano", "anterior")
            || containsAll(merged, "ejercicio", "anterior")
            || containsAll(merged, "previous", "year")
            || containsAll(merged, "prior", "year");
    }

    private static boolean containsAll(String value, String first, String second) {
        return value.contains(first) && value.contains(second);
    }

    private static boolean looksLikeAssumption(String normalizedLabel) {
        return containsToken(normalizedLabel, "hipotesis")
            || containsToken(normalizedLabel, "supuesto")
            || containsToken(normalizedLabel, "assumption")
            || containsToken(normalizedLabel, "inflacion")
            || containsToken(normalizedLabel, "ipc")
            || containsToken(normalizedLabel, "euribor")
            || containsToken(normalizedLabel, "precio")
            || containsToken(normalizedLabel, "tarifa");
    }

    private static boolean looksLikeFinancingExpense(String normalizedLabel, String code) {
        String normalizedCode = BudgetSemanticResolver.normalize(code);
        if (!normalizedCode.startsWith("66") && !normalizedCode.startsWith("67")) {
            return false;
        }
        return containsToken(normalizedLabel, "operativo")
            || containsToken(normalizedLabel, "operativos")
            || containsToken(normalizedLabel, "explotacion")
            || containsToken(normalizedLabel, "operating");
    }

    private static boolean looksLikeDepreciationCharge(String normalizedLabel, String code) {
        String normalizedCode = BudgetSemanticResolver.normalize(code);
        return normalizedLabel.contains("amortizacion")
            || normalizedLabel.contains("depreciation")
            || normalizedLabel.contains("depreciacion")
            || normalizedCode.startsWith("68");
    }

    private static boolean looksLikeFinancingAggregate(String normalizedLabel, String code) {
        if (code != null && !code.isBlank()) return false;
        return normalizedLabel.contains("financier")
            && (containsToken(normalizedLabel, "gasto")
                || containsToken(normalizedLabel, "ingreso"));
    }

    private static boolean containsToken(String normalizedLabel, String token) {
        if (normalizedLabel == null || normalizedLabel.isBlank()) return false;
        for (String part : normalizedLabel.split("\\s+")) {
            if (token.equals(part)) return true;
        }
        return false;
    }

    private static boolean looksLikeDerived(String normalizedLabel, String semanticKind) {
        if (Set.of("OPENING_BALANCE", "CLOSING_BALANCE").contains(upper(semanticKind))) return true;
        for (String marker : DERIVED_MARKERS) {
            if (normalizedLabel.contains(marker)) return true;
        }
        return normalizedLabel.contains("%") || normalizedLabel.contains("ratio");
    }

    private static boolean looksLikeTotal(String normalizedLabel) {
        return normalizedLabel.contains("total")
            || normalizedLabel.contains("acumulado")
            || normalizedLabel.contains("grand total")
            || normalizedLabel.contains("sumatorio");
    }

    private static boolean looksLikeSubtotal(String normalizedLabel, String semanticKind, boolean hasCode) {
        if (hasCode) return false;
        if (looksLikeTotal(normalizedLabel) || looksLikeAssumption(normalizedLabel) || looksLikeDerived(normalizedLabel, semanticKind)) {
            return false;
        }
        return genericAggregateLabel(normalizedLabel, semanticKind);
    }

    private static boolean genericAggregateLabel(String normalizedLabel, String semanticKind) {
        if (normalizedLabel.startsWith("total ")) return true;
        if (normalizedLabel.startsWith("subtotal ")) return true;
        if (matchesAggregateAlias(normalizedLabel, GENERIC_REVENUE_AGGREGATES)
            || matchesAggregateAlias(normalizedLabel, GENERIC_OPEX_AGGREGATES)
            || matchesAggregateAlias(normalizedLabel, SUBTOTAL_MARKERS)) {
            return true;
        }
        return false;
    }

    private static boolean looksLikeOrdinalHeading(String normalizedLabel, boolean hasCode) {
        if (hasCode || normalizedLabel == null) return false;
        return normalizedLabel.matches("^\\d+\\s+.*");
    }

    private static boolean looksLikeGroupedCode(String code) {
        if (code == null || code.isBlank()) return false;
        String compact = code.trim().replace(" ", "");
        if (!(compact.contains("-") || compact.contains("/"))) {
            return false;
        }
        String[] parts = compact.split("[-/]");
        if (parts.length < 2) {
            return false;
        }
        int width = -1;
        for (String part : parts) {
            if (!part.matches("\\d{2,4}")) {
                return false;
            }
            if (width < 0) {
                width = part.length();
            } else if (part.length() != width) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesAggregateAlias(String normalizedLabel, Set<String> aggregateAliases) {
        if (normalizedLabel == null || normalizedLabel.isBlank() || aggregateAliases == null || aggregateAliases.isEmpty()) {
            return false;
        }
        Set<String> labelTokens = normalizedMeaningfulTokens(normalizedLabel);
        for (String alias : aggregateAliases) {
            Set<String> aliasTokens = normalizedMeaningfulTokens(alias);
            if (aliasTokens.isEmpty() || labelTokens.isEmpty()) {
                continue;
            }
            long common = labelTokens.stream().filter(aliasTokens::contains).count();
            if (common == 0) {
                continue;
            }
            double labelCoverage = common / (double) labelTokens.size();
            double aliasCoverage = common / (double) aliasTokens.size();
            if (labelCoverage >= 0.75d && aliasCoverage >= 0.75d) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> normalizedMeaningfulTokens(String normalizedLabel) {
        return List.of(BudgetSemanticResolver.normalize(normalizedLabel).split("\\s+")).stream()
            .map(String::trim)
            .filter(token -> !token.isBlank())
            .map(BudgetCanonicalClassifier::normalizeAggregateToken)
            .filter(token -> !AGGREGATE_NOISE_TOKENS.contains(token))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String normalizeAggregateToken(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        String normalized = token.trim();
        if (normalized.length() > 4 && normalized.endsWith("es")) {
            return normalized.substring(0, normalized.length() - 2);
        }
        if (normalized.length() > 3 && normalized.endsWith("s")) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static Set<String> normalizedSet(Set<String> rawValues) {
        return rawValues.stream()
            .map(BudgetSemanticResolver::normalize)
            .filter(value -> !value.isBlank())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String sectionKindFor(String semanticKind) {
        return switch (upper(semanticKind)) {
            case "REVENUE", "OPEX", "CAPEX", "DEPRECIATION_AMORTIZATION", "FINANCING", "TAX", "INVENTORY_VARIATION" -> "P_AND_L";
            case "CASH_INFLOW", "CASH_OUTFLOW", "OPENING_BALANCE", "CLOSING_BALANCE" -> "CASHFLOW";
            case "ASSUMPTION" -> "ASSUMPTION";
            default -> "UNKNOWN";
        };
    }

    private static String upper(String value) {
        return value == null ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isFinancialSemantic(String semanticKind) {
        return Set.of("REVENUE", "OPEX", "CAPEX", "DEPRECIATION_AMORTIZATION", "FINANCING", "TAX", "INVENTORY_VARIATION").contains(upper(semanticKind));
    }

    private static boolean isCarryableContextSemantic(String semanticKind) {
        return Set.of("REVENUE", "OPEX", "CAPEX", "DEPRECIATION_AMORTIZATION", "TAX", "CASH_INFLOW", "CASH_OUTFLOW", "FINANCING", "INVENTORY_VARIATION").contains(upper(semanticKind));
    }

    private static boolean looksLikeRevenueEvidence(String normalizedLabel, String code) {
        String normalizedCode = BudgetSemanticResolver.normalize(code);
        if (normalizedLabel.contains("ingreso")
            || normalizedLabel.contains("revenue")
            || normalizedLabel.contains("sale")
            || normalizedLabel.contains("venta")
            || normalizedLabel.contains("facturacion")
            || normalizedLabel.contains("billing")
            || normalizedLabel.contains("turnover")) {
            return true;
        }
        String compactCode = normalizedCode.replace(" ", "");
        return compactCode.startsWith("7")
            || compactCode.startsWith("rev")
            || compactCode.startsWith("sale")
            || compactCode.startsWith("ing");
    }

    public record SectionContext(String semanticKind, String sectionKind) {}

    public record Classification(BudgetLongNormalizer.RowType rowType,
                                 String semanticKind,
                                 String sectionKind,
                                 String confidence,
                                 boolean ambiguous) {}
}

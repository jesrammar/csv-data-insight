package com.asecon.enterpriseiq.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BudgetCanonicalClassifier {
    private static final Logger log = LoggerFactory.getLogger(BudgetCanonicalClassifier.class);
    private static final Set<String> AGGREGATE_NOISE_TOKENS = Set.of("de", "del", "la", "el", "los", "las", "y", "and");
    private static final Set<String> SUBTOTAL_MARKERS = normalizedSet(Set.of(
        "ingreso de explotacion",
        "ingresos de explotacion",
        "ingreso explotacion",
        "gasto variable",
        "gasto de explotacion",
        "gastos de explotacion",
        "otro gasto de explotacion",
        "otros gastos de explotacion",
        "coste de explotacion",
        "costes de explotacion",
        "coste de ventas",
        "cost of goods sold",
        "gastos generales",
        "cash inflow",
        "cash outflow",
        "cobro",
        "pago",
        "tesoreria",
        "liquidez",
        "flujo de caja",
        "cash flow"
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
        "ingresos ordinarios",
        "ingresos operativos",
        "cifra de negocio",
        "volumen de negocio",
        "facturacion",
        "ventas netas",
        "revenue",
        "sales",
        "turnover",
        "operating revenue",
        "operating income"
    ));
    private static final Set<String> GENERIC_OPEX_AGGREGATES = normalizedSet(Set.of(
        "gasto",
        "gastos",
        "coste",
        "costes",
        "expense",
        "expenses",
        "gasto operativo",
        "gastos operativos",
        "coste operativo",
        "costes operativos",
        "coste operacional",
        "costes operacionales",
        "gastos generales",
        "gastos de explotacion",
        "costes de explotacion",
        "coste de ventas",
        "servicios exteriores",
        "aprovisionamientos",
        "operating expense",
        "operating expenses",
        "operating cost",
        "operating costs",
        "total operating costs",
        "total de costes",
        "cost of goods sold",
        "selling general administrative"
    ));
    private static final Set<String> REVENUE_BLOCK_HEADINGS = normalizedSet(Set.of(
        "ingreso", "ingresos", "revenue", "revenues", "sales", "ventas",
        "cifra de negocio", "volumen de negocio", "ingresos de explotacion", "ventas netas"
    ));
    private static final Set<String> OPEX_BLOCK_HEADINGS = normalizedSet(Set.of(
        "gasto", "gastos", "coste", "costes", "expense", "expenses", "opex",
        "gastos de explotacion", "costes operativos", "gastos generales", "cost of goods sold", "aprovisionamientos"
    ));
    private static final Set<String> CASHFLOW_BLOCK_HEADINGS = normalizedSet(Set.of(
        "tesoreria", "caja", "cashflow", "cash flow", "liquidez",
        "flujo de caja", "movimientos de tesoreria", "cobros y pagos", "movimientos de caja"
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
        boolean zeroOnlyHeading = !hasCode && looksLikeZeroOnlyRow(numericValues);

        if (zeroOnlyHeading && isBlockHeading(normalizedLabel, REVENUE_BLOCK_HEADINGS)) {
            return new Classification(BudgetLongNormalizer.RowType.SUBTOTAL, "REVENUE", "P_AND_L", "HIGH", false);
        }
        if (zeroOnlyHeading && isBlockHeading(normalizedLabel, OPEX_BLOCK_HEADINGS)) {
            return new Classification(BudgetLongNormalizer.RowType.SUBTOTAL, "OPEX", "P_AND_L", "HIGH", false);
        }
        if (zeroOnlyHeading && looksLikeStandaloneCashflowHeading(normalizedLabel)) {
            return new Classification(BudgetLongNormalizer.RowType.SUBTOTAL, "UNKNOWN", "CASHFLOW", "HIGH", false);
        }

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

        if (rowType == BudgetLongNormalizer.RowType.DETAIL
            && context != null
            && "P_AND_L".equals(upper(context.sectionKind()))
            && "OPEX".equals(upper(context.semanticKind()))
            && "REVENUE".equals(upper(semanticKind))
            && !looksLikeRevenueEvidence(normalizedLabel, code)
            && (ambiguous || !"HIGH".equalsIgnoreCase(confidence) || looksLikeExpenseEvidence(normalizedLabel, code))) {
            semanticKind = "OPEX";
            sectionKind = "P_AND_L";
            confidence = looksLikeExpenseEvidence(normalizedLabel, code) ? "HIGH" : "MEDIUM";
            ambiguous = !looksLikeExpenseEvidence(normalizedLabel, code);
        }

        boolean explicitSectionChange = explicit != null && !"UNKNOWN".equals(upper(explicit.sectionKind()));

        if (!explicitSectionChange
            && context != null
            && "CASHFLOW".equals(upper(context.sectionKind()))
            && rowType != BudgetLongNormalizer.RowType.ASSUMPTION
            && shouldInheritCashflowContext(normalizedLabel, code, semanticKind, sectionKind, rowType)) {
            sectionKind = "CASHFLOW";
            if ("FINANCING".equals(upper(semanticKind)) && looksLikeCashflowFinancing(normalizedLabel, code)) {
                semanticKind = inferCashflowFinancingSemantic(normalizedLabel, numericValues);
            } else if ("TAX".equals(upper(semanticKind))) {
                semanticKind = "CASHFLOW_TAX";
            }
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

        Classification result = new Classification(rowType, semanticKind, sectionKind, confidence, ambiguous);
        traceClassification(clientKey, label, code, result);
        return result;
    }

    private static void traceClassification(String clientKey, String label, String code, Classification classification) {
        String raw = (label == null ? "" : label) + " | " + (code == null ? "" : code);
        if (!BudgetTraceLogger.shouldTraceLabel(raw)) return;
        BudgetTraceLogger.log(log, "canonical-classification", BudgetTraceLogger.fields(
            "companyId", clientKey,
            "processingRoute", "BudgetCanonicalClassifier.classify",
            "rawLabel", label,
            "section", classification == null ? null : classification.sectionKind(),
            "rowRole", classification == null ? null : classification.rowType(),
            "financialNature", classification == null ? null : classification.semanticKind(),
            "confidence", classification == null ? null : classification.confidence()
        ));
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

    public static Classification alignToSectionContext(Classification classification,
                                                       String currentSectionKind,
                                                       String label,
                                                       String code,
                                                       List<BigDecimal> numericValues) {
        if (classification == null) return null;
        if (!"CASHFLOW".equals(upper(currentSectionKind))) return classification;
        if ("CASHFLOW".equals(upper(classification.sectionKind()))) return classification;
        if (canReopenProfitAndLossBlock(classification)) return classification;
        String normalizedLabel = BudgetSemanticResolver.normalize(label);
        if (shouldKeepAggregateInsideCashflow(classification, normalizedLabel, code)) {
            String confidence = "UNKNOWN".equalsIgnoreCase(classification.confidence()) ? "LOW" : classification.confidence();
            String semanticKind = upper(classification.semanticKind());
            if ("FINANCING".equals(semanticKind) && looksLikeCashflowFinancing(normalizedLabel, code)) {
                semanticKind = inferCashflowFinancingSemantic(normalizedLabel, numericValues);
            } else if ("TAX".equals(semanticKind)) {
                semanticKind = "CASHFLOW_TAX";
            }
            return new Classification(
                classification.rowType(),
                semanticKind,
                "CASHFLOW",
                confidence,
                classification.ambiguous()
            );
        }
        if (classification.rowType() == BudgetLongNormalizer.RowType.TEXT
            || classification.rowType() == BudgetLongNormalizer.RowType.ASSUMPTION) {
            return classification;
        }

        String semanticKind = upper(classification.semanticKind());
        String confidence = "UNKNOWN".equalsIgnoreCase(classification.confidence()) ? "LOW" : classification.confidence();

        if (isCashflowSemantic(semanticKind)) {
            return new Classification(
                classification.rowType(),
                semanticKind,
                "CASHFLOW",
                confidence,
                classification.ambiguous()
            );
        }
        if (Set.of(
            "REVENUE",
            "OTHER_OPERATING_INCOME",
            "OPEX",
            "OPERATING_ADJUSTMENT",
            "DEPRECIATION_AMORTIZATION",
            "CAPEX",
            "FINANCING"
        ).contains(semanticKind)) {
            return new Classification(
                classification.rowType(),
                semanticKind,
                "CASHFLOW",
                confidence,
                classification.ambiguous()
            );
        }
        if ("TAX".equals(semanticKind)) {
            return new Classification(classification.rowType(), "CASHFLOW_TAX", "CASHFLOW", confidence, classification.ambiguous());
        }
        if ("FINANCING".equals(semanticKind) && looksLikeCashflowFinancing(normalizedLabel, code)) {
            return new Classification(
                classification.rowType(),
                inferCashflowFinancingSemantic(normalizedLabel, numericValues),
                "CASHFLOW",
                confidence,
                classification.ambiguous()
            );
        }
        return classification;
    }

    private static boolean shouldKeepAggregateInsideCashflow(Classification classification,
                                                             String normalizedLabel,
                                                             String code) {
        if (classification == null) return false;
        if (classification.rowType() == BudgetLongNormalizer.RowType.DETAIL) return false;
        String semanticKind = upper(classification.semanticKind());
        if (!Set.of("REVENUE", "OTHER_OPERATING_INCOME", "OPEX", "TAX", "FINANCING").contains(semanticKind)) {
            return false;
        }
        if ("FINANCING".equals(semanticKind) && looksLikeCashflowFinancing(normalizedLabel, code)) {
            return true;
        }
        if ("TAX".equals(semanticKind)) {
            return true;
        }
        return genericAggregateLabel(normalizedLabel, semanticKind)
            || matchesAggregateAlias(stripAggregatePrefix(normalizedLabel), SUBTOTAL_MARKERS)
            || matchesAggregateAlias(stripAggregatePrefix(normalizedLabel), GENERIC_OPEX_AGGREGATES)
            || matchesAggregateAlias(stripAggregatePrefix(normalizedLabel), GENERIC_REVENUE_AGGREGATES);
    }

    private static boolean canReopenProfitAndLossBlock(Classification classification) {
        if (classification == null) return false;
        if (!"P_AND_L".equals(upper(classification.sectionKind()))) return false;
        if (classification.rowType() == BudgetLongNormalizer.RowType.DETAIL) return false;
        return Set.of(
            "REVENUE",
            "OTHER_OPERATING_INCOME",
            "OPEX",
            "OPERATING_ADJUSTMENT",
            "INVENTORY_VARIATION",
            "DEPRECIATION_AMORTIZATION",
            "CAPEX"
        ).contains(upper(classification.semanticKind()));
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
        if (looksLikeTaxCharge(normalizedLabel) && hasAmounts && !looksLikeTotal(normalizedLabel)) {
            return hasCode ? BudgetLongNormalizer.RowType.DETAIL : BudgetLongNormalizer.RowType.DETAIL;
        }
        if (looksLikeDerived(normalizedLabel, semanticKind)) return BudgetLongNormalizer.RowType.DERIVED_KPI;
        if (looksLikeAssumption(normalizedLabel) && !isFinancialSemantic(semanticKind)) return BudgetLongNormalizer.RowType.ASSUMPTION;
        if (looksLikeOrdinalHeading(normalizedLabel, hasCode)) return BudgetLongNormalizer.RowType.SUBTOTAL;
        if (hasCode && hasAmounts && isDetailGroupedAdjustmentCode(code, semanticKind)) {
            return BudgetLongNormalizer.RowType.DETAIL;
        }
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
        Classification explicitCategory = explicitCategorySemantic(normalizedLabel, normalizedCategory, rowType);
        if (explicitCategory != null) {
            return explicitCategory;
        }
        String merged = (normalizedLabel + " " + normalizedCategory).trim();
        String aggregateBody = stripAggregatePrefix(normalizedLabel);
        boolean aggregateCandidate = rowType != BudgetLongNormalizer.RowType.DETAIL || hasAggregatePrefix(normalizedLabel);
        if (merged.isBlank()) return null;

        if (isBlockHeading(normalizedLabel, REVENUE_BLOCK_HEADINGS)
            || (aggregateCandidate && (isBlockHeading(aggregateBody, REVENUE_BLOCK_HEADINGS)
            || matchesAggregateAlias(aggregateBody, GENERIC_REVENUE_AGGREGATES)))) {
            return new Classification(preserveAggregateRowType(rowType), "REVENUE", "P_AND_L", "HIGH", false);
        }
        if (isBlockHeading(normalizedLabel, OPEX_BLOCK_HEADINGS)
            || (aggregateCandidate && (isBlockHeading(aggregateBody, OPEX_BLOCK_HEADINGS)
            || matchesAggregateAlias(aggregateBody, GENERIC_OPEX_AGGREGATES)
            || matchesAggregateAlias(aggregateBody, SUBTOTAL_MARKERS)))) {
            return new Classification(preserveAggregateRowType(rowType), "OPEX", "P_AND_L", "HIGH", false);
        }
        if (isBlockHeading(normalizedLabel, CASHFLOW_BLOCK_HEADINGS)) {
            return new Classification(preserveAggregateRowType(rowType), "UNKNOWN", "CASHFLOW", "HIGH", false);
        }

        if (looksLikeCashflowSectionHeader(merged)) {
            return new Classification(BudgetLongNormalizer.RowType.SUBTOTAL, "UNKNOWN", "CASHFLOW", "HIGH", false);
        }
        if (looksLikeInventoryVariation(merged, normalizedLabel)) {
            return new Classification(rowType, "INVENTORY_VARIATION", "P_AND_L", "HIGH", false);
        }
        if (looksLikeEbitdaLabel(merged)) {
            return new Classification(BudgetLongNormalizer.RowType.DERIVED_KPI, "UNKNOWN", "P_AND_L", "HIGH", false);
        }
        if (looksLikeEbitLabel(merged)) {
            return new Classification(BudgetLongNormalizer.RowType.DERIVED_KPI, "UNKNOWN", "P_AND_L", "HIGH", false);
        }
        if (looksLikePreTaxLabel(merged)) {
            return new Classification(BudgetLongNormalizer.RowType.DERIVED_KPI, "UNKNOWN", "P_AND_L", "HIGH", false);
        }
        if (looksLikeNetResultLabel(merged)) {
            return new Classification(BudgetLongNormalizer.RowType.TOTAL, "UNKNOWN", "P_AND_L", "HIGH", false);
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
            return new Classification(preserveTaxRowType(rowType), "TAX", "P_AND_L", "MEDIUM", false);
        }
        if (looksLikeAssumption(merged)) {
            return new Classification(BudgetLongNormalizer.RowType.ASSUMPTION, "ASSUMPTION", "ASSUMPTION", "MEDIUM", false);
        }
        return null;
    }

    private static Classification explicitCategorySemantic(String normalizedLabel,
                                                           String normalizedCategory,
                                                           BudgetLongNormalizer.RowType rowType) {
        if (normalizedCategory == null || normalizedCategory.isBlank()) return null;
        BudgetLongNormalizer.RowType resolvedRowType = rowType;
        String semanticKind = null;
        String sectionKind = "UNKNOWN";

        if (normalizedCategory.startsWith("subtotal ")) {
            resolvedRowType = BudgetLongNormalizer.RowType.SUBTOTAL;
        } else if (normalizedCategory.startsWith("total ")) {
            resolvedRowType = BudgetLongNormalizer.RowType.TOTAL;
        } else if ("derived kpi".equals(normalizedCategory)) {
            resolvedRowType = BudgetLongNormalizer.RowType.DERIVED_KPI;
        }

        switch (normalizedCategory) {
            case "derived kpi" -> {
                semanticKind = "UNKNOWN";
                sectionKind = sectionKindForDerivedKpi(normalizedLabel);
            }
            case "revenue", "other operating income", "subtotal revenue" -> {
                semanticKind = "REVENUE";
                sectionKind = "P_AND_L";
            }
            case "opex", "subtotal opex" -> {
                semanticKind = "OPEX";
                sectionKind = "P_AND_L";
            }
            case "operating adjustment", "inventory variation" -> {
                semanticKind = "OPERATING_ADJUSTMENT";
                sectionKind = "P_AND_L";
            }
            case "depreciation amortization", "depreciation" -> {
                semanticKind = "DEPRECIATION_AMORTIZATION";
                sectionKind = "P_AND_L";
            }
            case "financial result" -> {
                semanticKind = "FINANCIAL_RESULT";
                sectionKind = "P_AND_L";
                resolvedRowType = BudgetLongNormalizer.RowType.TOTAL;
            }
            case "financial expense" -> {
                semanticKind = "FINANCIAL_EXPENSE";
                sectionKind = "P_AND_L";
            }
            case "financial income" -> {
                semanticKind = "FINANCIAL_INCOME";
                sectionKind = "P_AND_L";
            }
            case "total net result" -> {
                semanticKind = "TOTAL_NET_RESULT";
                sectionKind = "P_AND_L";
                resolvedRowType = BudgetLongNormalizer.RowType.TOTAL;
            }
            case "financing inflow" -> {
                semanticKind = "FINANCING_INFLOW";
                sectionKind = "CASHFLOW";
            }
            case "financing outflow" -> {
                semanticKind = "FINANCING_OUTFLOW";
                sectionKind = "CASHFLOW";
            }
            case "cashflow inflow" -> {
                semanticKind = "CASH_INFLOW";
                sectionKind = "CASHFLOW";
            }
            case "cashflow outflow" -> {
                semanticKind = "CASH_OUTFLOW";
                sectionKind = "CASHFLOW";
            }
            case "cashflow tax" -> {
                semanticKind = "CASHFLOW_TAX";
                sectionKind = "CASHFLOW";
            }
            case "opening balance" -> {
                semanticKind = "OPENING_BALANCE";
                sectionKind = "CASHFLOW";
                resolvedRowType = BudgetLongNormalizer.RowType.TOTAL;
            }
            case "closing balance" -> {
                semanticKind = "CLOSING_BALANCE";
                sectionKind = "CASHFLOW";
                resolvedRowType = BudgetLongNormalizer.RowType.TOTAL;
            }
            case "capex" -> {
                semanticKind = "CAPEX";
                sectionKind = "UNKNOWN";
            }
            default -> {
                return null;
            }
        }
        return new Classification(resolvedRowType, semanticKind, sectionKind, "HIGH", false);
    }

    private static String sectionKindForDerivedKpi(String normalizedLabel) {
        if (normalizedLabel == null || normalizedLabel.isBlank()) {
            return "UNKNOWN";
        }
        if (normalizedLabel.contains("cash")
            || normalizedLabel.contains("caja")
            || normalizedLabel.contains("tesoreria")
            || normalizedLabel.contains("liquidez")
            || normalizedLabel.contains("saldo")) {
            return "CASHFLOW";
        }
        return "P_AND_L";
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

    private static boolean looksLikeEbitdaLabel(String merged) {
        return merged.contains("ebitda")
            || containsAll(merged, "beneficio", "amortizacion")
            || containsAll(merged, "resultado", "amortizacion")
            || containsAll(merged, "profit", "depreciation");
    }

    private static boolean looksLikeEbitLabel(String merged) {
        return merged.contains("ebit")
            || containsAll(merged, "beneficio", "explotacion")
            || containsAll(merged, "resultado", "explotacion")
            || containsAll(merged, "operating", "profit")
            || containsAll(merged, "operating", "result");
    }

    private static boolean looksLikePreTaxLabel(String merged) {
        return containsAll(merged, "antes", "impuesto")
            || containsAll(merged, "before", "tax")
            || containsAll(merged, "pre", "tax");
    }

    private static boolean looksLikeNetResultLabel(String merged) {
        return containsAll(merged, "beneficio", "neto")
            || containsAll(merged, "resultado", "neto")
            || containsAll(merged, "net", "result")
            || containsAll(merged, "net", "profit");
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
        if (looksLikeTaxCharge(normalizedLabel)) return false;
        for (String marker : DERIVED_MARKERS) {
            if (normalizedLabel.contains(marker)) return true;
        }
        return normalizedLabel.contains("%") || normalizedLabel.contains("ratio");
    }

    private static boolean looksLikeTaxCharge(String normalizedLabel) {
        if (normalizedLabel == null || normalizedLabel.isBlank()) return false;
        return normalizedLabel.contains("impuesto")
            || normalizedLabel.contains("tax")
            || normalizedLabel.contains("iva")
            || normalizedLabel.contains("vat");
    }

    private static BudgetLongNormalizer.RowType preserveTaxRowType(BudgetLongNormalizer.RowType rowType) {
        if (rowType == BudgetLongNormalizer.RowType.TOTAL || rowType == BudgetLongNormalizer.RowType.SUBTOTAL) {
            return rowType;
        }
        return BudgetLongNormalizer.RowType.DETAIL;
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
        if (isBlockHeading(normalizedLabel, REVENUE_BLOCK_HEADINGS)
            || isBlockHeading(normalizedLabel, OPEX_BLOCK_HEADINGS)
            || isBlockHeading(normalizedLabel, CASHFLOW_BLOCK_HEADINGS)) {
            return true;
        }
        if (matchesAggregateAlias(normalizedLabel, GENERIC_REVENUE_AGGREGATES)
            || matchesAggregateAlias(normalizedLabel, GENERIC_OPEX_AGGREGATES)
            || matchesAggregateAlias(normalizedLabel, SUBTOTAL_MARKERS)) {
            return true;
        }
        return false;
    }

    private static boolean isBlockHeading(String normalizedLabel, Set<String> headings) {
        if (normalizedLabel == null || normalizedLabel.isBlank() || headings == null || headings.isEmpty()) {
            return false;
        }
        String normalized = BudgetSemanticResolver.normalize(normalizedLabel);
        if (headings.contains(normalized)) {
            return true;
        }
        Set<String> tokens = normalizedMeaningfulTokens(normalized);
        return tokens.size() == 1 && tokens.stream().anyMatch(headings::contains);
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

    private static boolean isDetailGroupedAdjustmentCode(String code, String semanticKind) {
        if (!Set.of("OPERATING_ADJUSTMENT", "INVENTORY_VARIATION").contains(upper(semanticKind))) {
            return false;
        }
        return looksLikeGroupedCode(code);
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

    private static String stripAggregatePrefix(String normalizedLabel) {
        if (normalizedLabel == null || normalizedLabel.isBlank()) {
            return "";
        }
        String normalized = BudgetSemanticResolver.normalize(normalizedLabel);
        if (normalized.startsWith("total ")) {
            return normalized.substring("total ".length()).trim();
        }
        if (normalized.startsWith("subtotal ")) {
            return normalized.substring("subtotal ".length()).trim();
        }
        return normalized;
    }

    private static boolean hasAggregatePrefix(String normalizedLabel) {
        String normalized = BudgetSemanticResolver.normalize(normalizedLabel);
        return normalized.startsWith("total ") || normalized.startsWith("subtotal ");
    }

    private static BudgetLongNormalizer.RowType preserveAggregateRowType(BudgetLongNormalizer.RowType rowType) {
        return rowType == BudgetLongNormalizer.RowType.TOTAL
            ? BudgetLongNormalizer.RowType.TOTAL
            : BudgetLongNormalizer.RowType.SUBTOTAL;
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
            case "REVENUE", "OPEX", "OPERATING_ADJUSTMENT", "CAPEX", "DEPRECIATION_AMORTIZATION", "FINANCING", "FINANCIAL_RESULT", "FINANCIAL_EXPENSE", "FINANCIAL_INCOME", "TAX", "INVENTORY_VARIATION" -> "P_AND_L";
            case "CASH_INFLOW", "CASH_OUTFLOW", "OPENING_BALANCE", "CLOSING_BALANCE", "FINANCING_INFLOW", "FINANCING_OUTFLOW", "CASHFLOW_TAX" -> "CASHFLOW";
            case "ASSUMPTION" -> "ASSUMPTION";
            default -> "UNKNOWN";
        };
    }

    private static String upper(String value) {
        return value == null ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isFinancialSemantic(String semanticKind) {
        return Set.of("REVENUE", "OPEX", "OPERATING_ADJUSTMENT", "CAPEX", "DEPRECIATION_AMORTIZATION", "FINANCING", "FINANCIAL_RESULT", "FINANCIAL_EXPENSE", "FINANCIAL_INCOME", "TAX", "INVENTORY_VARIATION").contains(upper(semanticKind));
    }

    private static boolean isCashflowSemantic(String semanticKind) {
        return Set.of("CASH_INFLOW", "CASH_OUTFLOW", "OPENING_BALANCE", "CLOSING_BALANCE", "FINANCING_INFLOW", "FINANCING_OUTFLOW", "CASHFLOW_TAX").contains(upper(semanticKind));
    }

    private static boolean shouldInheritCashflowContext(String normalizedLabel,
                                                        String code,
                                                        String semanticKind,
                                                        String sectionKind,
                                                        BudgetLongNormalizer.RowType rowType) {
        if ("UNKNOWN".equals(upper(sectionKind)) || rowType == BudgetLongNormalizer.RowType.TEXT) {
            return true;
        }
        if (isCashflowSemantic(semanticKind)) {
            return true;
        }
        if ("CAPEX".equals(upper(semanticKind)) || "TAX".equals(upper(semanticKind))) {
            return true;
        }
        if (Set.of("FINANCIAL_EXPENSE", "FINANCIAL_INCOME", "FINANCIAL_RESULT").contains(upper(semanticKind))) {
            return true;
        }
        return "FINANCING".equals(upper(semanticKind)) && looksLikeCashflowFinancing(normalizedLabel, code);
    }

    private static boolean looksLikeCashflowFinancing(String normalizedLabel, String code) {
        String normalizedCode = BudgetSemanticResolver.normalize(code);
        if (normalizedCode.startsWith("66") || normalizedCode.startsWith("67")) {
            return false;
        }
        if (containsToken(normalizedLabel, "interes") || containsToken(normalizedLabel, "intereses")) {
            return false;
        }
        if (normalizedLabel.contains("resultado financier") || normalizedLabel.contains("financial result")) {
            return false;
        }
        return containsToken(normalizedLabel, "prestamo")
            || containsToken(normalizedLabel, "prestamos")
            || containsToken(normalizedLabel, "deuda")
            || containsToken(normalizedLabel, "deudas")
            || containsToken(normalizedLabel, "leasing")
            || normalizedLabel.contains("financiacion")
            || normalizedLabel.contains("financing")
            || normalizedLabel.contains("loan");
    }

    private static boolean looksLikeStandaloneCashflowHeading(String normalizedLabel) {
        if (normalizedLabel == null || normalizedLabel.isBlank()) {
            return false;
        }
        if (isBlockHeading(normalizedLabel, CASHFLOW_BLOCK_HEADINGS)) {
            return true;
        }
        return normalizedLabel.startsWith("tesorer")
            || normalizedLabel.equals("caja")
            || normalizedLabel.equals("liquidez")
            || normalizedLabel.equals("cashflow")
            || normalizedLabel.equals("cash flow");
    }

    private static boolean looksLikeZeroOnlyRow(List<BigDecimal> numericValues) {
        if (numericValues == null || numericValues.isEmpty()) {
            return false;
        }
        boolean sawAmount = false;
        for (BigDecimal value : numericValues) {
            if (value == null) {
                continue;
            }
            sawAmount = true;
            if (value.signum() != 0) {
                return false;
            }
        }
        return sawAmount;
    }

    private static String inferCashflowFinancingSemantic(String normalizedLabel, List<BigDecimal> numericValues) {
        if (normalizedLabel.contains("cuota")
            || normalizedLabel.contains("devolucion")
            || normalizedLabel.contains("devolver")
            || normalizedLabel.contains("repago")
            || normalizedLabel.contains("repayment")
            || normalizedLabel.contains("installment")
            || normalizedLabel.contains("amortizacion prestam")
            || normalizedLabel.contains("amortizacion deuda")
            || normalizedLabel.contains("loan repayment")) {
            return "FINANCING_OUTFLOW";
        }
        boolean hasPositive = false;
        boolean hasNegative = false;
        if (numericValues != null) {
            for (BigDecimal value : numericValues) {
                if (value == null || value.signum() == 0) continue;
                if (value.signum() > 0) {
                    hasPositive = true;
                } else {
                    hasNegative = true;
                }
            }
        }
        if (hasPositive && !hasNegative) {
            return "FINANCING_INFLOW";
        }
        if (hasNegative && !hasPositive) {
            return "FINANCING_OUTFLOW";
        }
        return "FINANCING";
    }

    private static boolean isCarryableContextSemantic(String semanticKind) {
        return Set.of("REVENUE", "OPEX", "OPERATING_ADJUSTMENT", "CAPEX", "DEPRECIATION_AMORTIZATION", "TAX", "CASH_INFLOW", "CASH_OUTFLOW", "FINANCING", "FINANCIAL_RESULT", "INVENTORY_VARIATION").contains(upper(semanticKind));
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

    private static boolean looksLikeExpenseEvidence(String normalizedLabel, String code) {
        String normalizedCode = BudgetSemanticResolver.normalize(code).replace(" ", "");
        if (normalizedLabel.contains("gasto")
            || normalizedLabel.contains("coste")
            || normalizedLabel.contains("expense")
            || normalizedLabel.contains("compra")
            || normalizedLabel.contains("purchase")
            || normalizedLabel.contains("sueldo")
            || normalizedLabel.contains("salario")
            || normalizedLabel.contains("nomina")
            || normalizedLabel.contains("payroll")
            || normalizedLabel.contains("alquiler")
            || normalizedLabel.contains("rent")
            || normalizedLabel.contains("seguro")
            || normalizedLabel.contains("insurance")
            || normalizedLabel.contains("gestoria")
            || normalizedLabel.contains("mantenimiento")
            || normalizedLabel.contains("maintenance")
            || normalizedLabel.contains("reparacion")
            || normalizedLabel.contains("repair")
            || normalizedLabel.contains("limpieza")
            || normalizedLabel.contains("cleaning")
            || normalizedLabel.contains("consumible")
            || normalizedLabel.contains("suministro")
            || normalizedLabel.contains("utility")
            || normalizedLabel.contains("luz")
            || normalizedLabel.contains("agua")) {
            return true;
        }
        return normalizedCode.startsWith("6");
    }

    public record SectionContext(String semanticKind, String sectionKind) {}

    public record Classification(BudgetLongNormalizer.RowType rowType,
                                 String semanticKind,
                                 String sectionKind,
                                 String confidence,
                                 boolean ambiguous) {}
}

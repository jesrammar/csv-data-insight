package com.asecon.enterpriseiq.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BudgetSemanticResolver {
    private static final Logger log = LoggerFactory.getLogger(BudgetSemanticResolver.class);
    private static final Set<String> ESSENTIAL_HEADER_CONCEPTS = Set.of(
        "BUDGET_AMOUNT",
        "PERIOD",
        "CONCEPT_NAME"
    );
    private static final Set<String> HEADER_CONCEPTS = Set.of(
        "BUDGET_AMOUNT",
        "ACTUAL_AMOUNT",
        "FORECAST_AMOUNT",
        "VARIANCE",
        "PERIOD",
        "CONCEPT_CODE",
        "CONCEPT_NAME",
        "COST_CENTER",
        "DEPARTMENT",
        "CURRENCY"
    );
    private static final Set<String> NUMERIC_CONCEPTS = Set.of("BUDGET_AMOUNT", "ACTUAL_AMOUNT", "FORECAST_AMOUNT", "VARIANCE");
    private static final Set<String> TEXT_CONCEPTS = Set.of("CONCEPT_CODE", "CONCEPT_NAME", "COST_CENTER", "DEPARTMENT", "CURRENCY");
    private static final List<String> ROW_SEMANTIC_CONCEPTS = List.of(
        "REVENUE",
        "OTHER_OPERATING_INCOME",
        "OPEX",
        "OPERATING_ADJUSTMENT",
        "CAPEX",
        "DEPRECIATION_AMORTIZATION",
        "CASH_INFLOW",
        "CASH_OUTFLOW",
        "FINANCING",
        "FINANCIAL_EXPENSE",
        "FINANCIAL_INCOME",
        "FINANCIAL_RESULT",
        "FINANCING_INFLOW",
        "FINANCING_OUTFLOW",
        "OPENING_BALANCE",
        "CLOSING_BALANCE",
        "TAX",
        "CASHFLOW_TAX",
        "ASSUMPTION"
    );
    private static final Map<String, String> EXPLICIT_NATURE_VALUES = Map.ofEntries(
        Map.entry("revenue", "REVENUE"),
        Map.entry("other operating income", "OTHER_OPERATING_INCOME"),
        Map.entry("subtotal revenue", "REVENUE"),
        Map.entry("opex", "OPEX"),
        Map.entry("subtotal opex", "OPEX"),
        Map.entry("operating adjustment", "OPERATING_ADJUSTMENT"),
        Map.entry("inventory variation", "OPERATING_ADJUSTMENT"),
        Map.entry("depreciation amortization", "DEPRECIATION_AMORTIZATION"),
        Map.entry("depreciation", "DEPRECIATION_AMORTIZATION"),
        Map.entry("financial result", "FINANCIAL_RESULT"),
        Map.entry("financial expense", "FINANCIAL_EXPENSE"),
        Map.entry("financial income", "FINANCIAL_INCOME"),
        Map.entry("capex", "CAPEX"),
        Map.entry("cashflow inflow", "CASH_INFLOW"),
        Map.entry("cashflow outflow", "CASH_OUTFLOW"),
        Map.entry("cashflow tax", "CASHFLOW_TAX"),
        Map.entry("opening balance", "OPENING_BALANCE"),
        Map.entry("closing balance", "CLOSING_BALANCE"),
        Map.entry("financing inflow", "FINANCING_INFLOW"),
        Map.entry("financing outflow", "FINANCING_OUTFLOW")
    );
    private static final Set<String> CATEGORY_HEADER_ALIASES = Set.of(
        "tipo", "tipo partida", "tipo registro", "categoria", "subcategoria", "clasificacion", "classification", "naturaleza", "line type", "category", "record type", "nature"
    );
    private static final Pattern NON_ASCII_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern CAMEL_BREAK = Pattern.compile("(?<=[a-z])(?=[A-Z])");

    private BudgetSemanticResolver() {}

    public static Resolution resolve(List<String> headers, List<Map<String, String>> sampleRows, String clientKey) {
        List<String> safeHeaders = headers == null ? List.of() : headers.stream().filter(Objects::nonNull).toList();
        Map<String, List<String>> valuesByHeader = new LinkedHashMap<>();
        for (String header : safeHeaders) {
            valuesByHeader.put(header, sampleValues(sampleRows, header, 120));
        }

        int domainHits = 0;
        for (String header : safeHeaders) {
            String normalized = normalize(header);
            if (normalized.contains("budget") || normalized.contains("presupuesto")
                || normalized.contains("forecast") || normalized.contains("prevision")
                || normalized.contains("period") || normalized.contains("mes")) {
                domainHits++;
            }
        }
        boolean budgetDomain = domainHits >= 2;

        Map<String, HeaderInference> matches = new LinkedHashMap<>();
        for (String concept : HEADER_CONCEPTS) {
            List<HeaderCandidate> ranked = new ArrayList<>();
            for (String header : safeHeaders) {
                ranked.add(scoreHeader(concept, header, valuesByHeader.getOrDefault(header, List.of()), budgetDomain, clientKey));
            }
            ranked.sort((a, b) -> Double.compare(b.score(), a.score()));
            if (ranked.isEmpty() || ranked.get(0).score() < 0.42d) {
                continue;
            }
            HeaderCandidate best = ranked.get(0);
            HeaderCandidate second = ranked.size() > 1 ? ranked.get(1) : null;
            boolean ambiguous = best.score() < 0.60d || (second != null && best.header().equals(second.header()) ? false : second != null && (best.score() - second.score()) < 0.08d);
            matches.put(concept, new HeaderInference(
                concept,
                best.header(),
                round(best.score()),
                confidenceOf(best.score()),
                ambiguous,
                best.reasons()
            ));
        }

        Map<String, HeaderInference> bestByHeader = new LinkedHashMap<>();
        for (HeaderInference inference : matches.values()) {
            HeaderInference current = bestByHeader.get(inference.header());
            if (current == null || inference.score() > current.score()) {
                bestByHeader.put(inference.header(), inference);
            }
        }

        Map<String, HeaderInference> deduped = new LinkedHashMap<>();
        for (String concept : HEADER_CONCEPTS) {
            HeaderInference inference = matches.get(concept);
            if (inference == null) continue;
            HeaderInference bestForHeader = bestByHeader.get(inference.header());
            if (bestForHeader != null && bestForHeader.concept().equals(inference.concept())) {
                deduped.put(concept, inference);
            }
        }

        List<String> notes = deduped.values().stream()
            .map(inference -> "%s -> %s (%.2f, %s)".formatted(
                inference.header(),
                inference.concept(),
                inference.score(),
                inference.confidence().toLowerCase(Locale.ROOT)
            ))
            .toList();
        boolean requiresConfirmation = deduped.values().stream()
            .anyMatch(inference -> ESSENTIAL_HEADER_CONCEPTS.contains(inference.concept()) && inference.ambiguous());

        return new Resolution(deduped, notes, requiresConfirmation);
    }

    public static HeaderInference bestPeriodHeader(List<String> headers, List<Map<String, String>> sampleRows, String clientKey, boolean preferNumeric) {
        List<String> safeHeaders = headers == null ? List.of() : headers.stream().filter(Objects::nonNull).toList();
        HeaderCandidate best = null;
        for (String header : safeHeaders) {
            List<String> values = sampleValues(sampleRows, header, 120);
            boolean numericMonth = values.stream().filter(Objects::nonNull).map(String::trim).filter(v -> !v.isBlank()).allMatch(BudgetSemanticResolver::looksLikeMonthNumber);
            boolean textualMonth = values.stream().filter(Objects::nonNull).map(String::trim).filter(v -> !v.isBlank()).anyMatch(BudgetSemanticResolver::looksLikeMonthLikeValue);
            if (preferNumeric && !numericMonth) continue;
            if (!preferNumeric && !textualMonth) continue;
            HeaderCandidate candidate = scoreHeader("PERIOD", header, values, true, clientKey);
            if (best == null || candidate.score() > best.score()) {
                best = candidate;
            }
        }
        if (best == null || best.score() < 0.45d) return null;
        return new HeaderInference("PERIOD", best.header(), round(best.score()), confidenceOf(best.score()), best.score() < 0.68d, best.reasons());
    }

    public static HeaderInference detectNatureHeader(List<String> headers, List<Map<String, String>> sampleRows) {
        HeaderCandidate best = null;
        for (String header : headers == null ? List.<String>of() : headers) {
            if (header == null) continue;
            String normalized = normalize(header);
            List<String> values = sampleValues(sampleRows, header, 120);
            double aliasScore = tokenOverlapScore(normalized, CATEGORY_HEADER_ALIASES);
            if (aliasScore < 0.34d) continue;
            double valueScore = natureValueScore(values);
            double score = Math.min(1d, aliasScore * 0.55d + valueScore * 0.45d);
            if (score < 0.35d) continue;
            List<String> reasons = new ArrayList<>();
            if (aliasScore > 0.3d) reasons.add("cabecera compatible con tipología de partida");
            if (valueScore > 0.3d) reasons.add("los valores parecen clasificar ingreso, gasto o inversión");
            HeaderCandidate candidate = new HeaderCandidate(header, score, reasons);
            if (best == null || candidate.score() > best.score()) best = candidate;
        }
        if (best == null) return null;
        return new HeaderInference("NATURE", best.header(), round(best.score()), confidenceOf(best.score()), best.score() < 0.65d, best.reasons());
    }

    public static NatureInference classifyBusinessNature(String clientKey, String... values) {
        return classifyRowSemantic(clientKey, values);
    }

    public static NatureInference classifyRowSemantic(String clientKey, String... values) {
        NatureInference explicit = detectExplicitNature(values);
        if (explicit != null) {
            traceResolution(clientKey, values, explicit, "explicit");
            return explicit;
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        List<String> reasons = new ArrayList<>();
        for (String concept : ROW_SEMANTIC_CONCEPTS) {
            double score = 0d;
            Set<String> aliases = normalizedAliases(concept, clientKey);
            Set<String> exclusions = normalizedExclusions(concept, clientKey);
            for (String value : values) {
                String normalized = normalize(value);
                if (normalized.isBlank()) continue;
                double aliasScore = aliasMatchScore(normalized, aliases);
                double exclusionPenalty = aliasMatchScore(normalized, exclusions) * 0.85d;
                score = Math.max(score, Math.max(0d, aliasScore - exclusionPenalty));
                score = Math.max(score, semanticRuleBoost(concept, normalized));
                score = Math.max(score, codeRuleBoost(concept, normalized));
            }
            scores.put(concept, score);
        }
        String bestConcept = null;
        double bestScore = 0d;
        double secondScore = 0d;
        for (var entry : scores.entrySet()) {
            if (entry.getValue() > bestScore) {
                secondScore = bestScore;
                bestScore = entry.getValue();
                bestConcept = entry.getKey();
            } else if (entry.getValue() > secondScore) {
                secondScore = entry.getValue();
            }
        }
        boolean ambiguous = bestScore < 0.58d || (bestScore - secondScore) < 0.12d;
        if (bestConcept != null && bestScore > 0d) {
            reasons.add("clasificado por alias semánticos en valores/categorías");
        }
        NatureInference inference = new NatureInference(bestConcept == null ? "UNKNOWN" : bestConcept, round(bestScore), confidenceOf(bestScore), ambiguous, reasons);
        traceResolution(clientKey, values, inference, "scored");
        return inference;
    }

    private static void traceResolution(String clientKey, String[] values, NatureInference inference, String route) {
        String joined = values == null ? "" : String.join(" | ", values);
        if (!BudgetTraceLogger.shouldTraceLabel(joined)) return;
        BudgetTraceLogger.log(log, "semantic-resolution", BudgetTraceLogger.fields(
            "companyId", clientKey,
            "processingRoute", "BudgetSemanticResolver." + route,
            "rawLabel", joined,
            "financialNature", inference == null ? null : inference.concept(),
            "confidence", inference == null ? null : inference.confidence(),
            "score", inference == null ? null : inference.score()
        ));
    }

    public static String normalize(String raw) {
        if (raw == null) return "";
        String expanded = repairCommonMojibake(raw);
        expanded = repairVisibleUtf8Mojibake(expanded);
        expanded = CAMEL_BREAK.matcher(expanded).replaceAll(" ");
        expanded = expanded.replace('&', ' ')
            .replace('/', ' ')
            .replace('\\', ' ')
            .replace('-', ' ')
            .replace('_', ' ')
            .replace('.', ' ')
            .replace(',', ' ')
            .replace(':', ' ')
            .replace(';', ' ')
            .replace("€", " ")
            .replace("$", " ")
            .replace("%", " ");
        expanded = stripAccents(expanded.toLowerCase(Locale.ROOT));
        expanded = expanded.replaceAll("\\bvs\\b", " versus ");
        expanded = expanded.replaceAll("\\bppto\\b", " presupuesto ");
        expanded = expanded.replaceAll("\\bdesvio\\b", " desviacion ");
        expanded = expanded.replaceAll("\\bdto\\b", " descuento ");
        expanded = expanded.replaceAll("\\bdpto\\b", " departamento ");
        expanded = expanded.replaceAll("\\bdepto\\b", " departamento ");
        expanded = expanded.replaceAll("\\bceco\\b", " centro coste ");
        expanded = expanded.replaceAll("\\bcta\\b", " cuenta ");
        expanded = expanded.replaceAll("\\bejerc\\b", " ejercicio ");
        expanded = expanded.replaceAll("\\bprev\\b", " prevision ");
        expanded = expanded.replaceAll("\\breal\\b", " real ");
        expanded = expanded.replaceAll("\\bmeses\\b", " mes ");
        expanded = expanded.replaceAll("\\bingresos\\b", " ingreso ");
        expanded = expanded.replaceAll("\\bgastos\\b", " gasto ");
        expanded = expanded.replaceAll("\\bcostes\\b", " coste ");
        expanded = expanded.replaceAll("\\bventas\\b", " venta ");
        expanded = expanded.replaceAll("\\bactuals\\b", " actual ");
        expanded = expanded.replaceAll("\\brevenues\\b", " revenue ");
        expanded = expanded.replaceAll("\\bexpenses\\b", " expense ");
        expanded = expanded.replaceAll("\\bcosts\\b", " cost ");
        expanded = expanded.replaceAll("\\s+", " ").trim();
        return expanded;
    }

    private static String repairVisibleUtf8Mojibake(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        return raw
            .replace("\u00C3\u00A1", "á")
            .replace("\u00C3\u00A9", "é")
            .replace("\u00C3\u00AD", "í")
            .replace("\u00C3\u00B3", "ó")
            .replace("\u00C3\u00BA", "ú")
            .replace("\u00C3\u0081", "Á")
            .replace("\u00C3\u0089", "É")
            .replace("\u00C3\u008D", "Í")
            .replace("\u00C3\u0093", "Ó")
            .replace("\u00C3\u009A", "Ú")
            .replace("\u00C3\u00B1", "ñ")
            .replace("\u00C3\u0091", "Ñ")
            .replace("\u00C3\u00BC", "ü")
            .replace("\u00C3\u009C", "Ü")
            .replace("\u00C3\u00A7", "ç")
            .replace("\u00C3\u0087", "Ç")
            .replace("\u00C2\u00A0", " ")
            .replace("\u00C2", "")
            .replace("\u00E2\u0082\u00AC\u0099", "'")
            .replace("\u00E2\u0080\u0099", "'")
            .replace("\u00E2\u0080\u009C", "\"")
            .replace("\u00E2\u0080\u009D", "\"")
            .replace("\u00E2\u0080\u0093", "-")
            .replace("\u00E2\u0080\u0094", "-");
    }

    private static String repairCommonMojibake(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        if (!looksLikeMojibake(raw)) return raw;
        String repaired = new String(raw.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        return mojibakeScore(repaired) < mojibakeScore(raw) ? repaired : raw;
    }

    private static boolean looksLikeMojibake(String value) {
        return value.indexOf('Ã') >= 0
            || value.indexOf('Â') >= 0
            || value.indexOf('â') >= 0
            || value.contains("\\u00");
    }

    private static int mojibakeScore(String value) {
        if (value == null || value.isBlank()) return 0;
        int score = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == 'Ã' || ch == 'Â' || ch == 'â') {
                score += 2;
            } else if (ch == '\\' && i + 3 < value.length() && value.charAt(i + 1) == 'u') {
                score += 1;
            }
        }
        return score;
    }

    public static boolean looksLikeMonthLikeValue(String raw) {
        String normalized = normalize(raw);
        return normalized.startsWith("ene") || normalized.startsWith("feb")
            || normalized.startsWith("mar") || normalized.startsWith("abr")
            || normalized.startsWith("may") || normalized.startsWith("jun")
            || normalized.startsWith("jul") || normalized.startsWith("ago")
            || normalized.startsWith("sep") || normalized.startsWith("oct")
            || normalized.startsWith("nov") || normalized.startsWith("dic")
            || normalized.startsWith("jan") || normalized.startsWith("apr")
            || normalized.startsWith("aug") || normalized.startsWith("dec")
            || normalized.matches("^(19|20)\\d{2}\\s?[\\-/]?\\s?(0?[1-9]|1[0-2])$")
            || normalized.matches("^(0?[1-9]|1[0-2])\\s?[\\-/]?\\s?(19|20)\\d{2}$")
            || normalized.matches("^(19|20)\\d{2}[\\-/](0?[1-9]|1[0-2])[\\-/](0?[1-9]|[12]\\d|3[01])$")
            || normalized.matches("^(0?[1-9]|[12]\\d|3[01])[\\-/](0?[1-9]|1[0-2])[\\-/](19|20)\\d{2}$")
            || normalized.matches("^(19|20)\\d{2}\\s+(0?[1-9]|1[0-2])\\s+(0?[1-9]|[12]\\d|3[01])$")
            || normalized.matches("^(0?[1-9]|[12]\\d|3[01])\\s+(0?[1-9]|1[0-2])\\s+(19|20)\\d{2}$");
    }

    public static boolean looksLikeMonthNumber(String raw) {
        if (raw == null) return false;
        String trimmed = raw.trim();
        if (!trimmed.matches("^[0-9]{1,2}$")) return false;
        int number = Integer.parseInt(trimmed);
        return number >= 1 && number <= 12;
    }

    public static boolean looksLikeNumericAmount(String raw) {
        if (raw == null) return false;
        String cleaned = raw.replace("\u00A0", "").replace(" ", "").replace("€", "").replace("$", "");
        cleaned = cleaned.replaceAll("[^0-9,().\\-]", "");
        if (cleaned.isBlank()) return false;
        return cleaned.matches("^\\(?-?[0-9]{1,3}([.,][0-9]{3})*([.,][0-9]+)?\\)?-?$")
            || cleaned.matches("^-?[0-9]+([.,][0-9]+)?$");
    }

    public static boolean looksLikeCurrency(String raw) {
        String normalized = normalize(raw);
        return Set.of("eur", "usd", "gbp", "mxn", "ars", "clp", "cop", "pen").contains(normalized);
    }

    private static HeaderCandidate scoreHeader(String concept,
                                               String header,
                                               List<String> sampleValues,
                                               boolean budgetDomain,
                                               String clientKey) {
        String normalizedHeader = normalize(header);
        Set<String> aliases = normalizedAliases(concept, clientKey);
        Set<String> exclusions = normalizedExclusions(concept, clientKey);
        double headerScore = aliasMatchScore(normalizedHeader, aliases);
        double exclusionPenalty = aliasMatchScore(normalizedHeader, exclusions) * 0.85d;
        double physicalScore = physicalScore(concept, sampleValues);
        double valueScore = valueScore(concept, sampleValues, clientKey);
        double relatedScore = relatedScore(concept, normalizedHeader);
        double domainBoost = budgetDomain ? 0.05d : 0d;
        double score = Math.max(0d, Math.min(1d, headerScore * 0.52d + physicalScore * 0.18d + valueScore * 0.18d + relatedScore * 0.07d + domainBoost - exclusionPenalty));

        List<String> reasons = new ArrayList<>();
        if (headerScore > 0.55d) reasons.add("alias de cabecera reconocido");
        if (physicalScore > 0.18d) reasons.add("tipo físico compatible");
        if (valueScore > 0.18d) reasons.add("valores observados coherentes");
        if (relatedScore > 0.10d) reasons.add("relación natural con otras columnas");
        if (budgetDomain) reasons.add("dominio anual/presupuestario detectado");
        if (exclusionPenalty > 0.30d) reasons.add("se aplicaron exclusiones semánticas");
        return new HeaderCandidate(header, score, reasons);
    }

    private static double physicalScore(String concept, List<String> sampleValues) {
        List<String> nonBlank = sampleValues.stream().filter(Objects::nonNull).map(String::trim).filter(v -> !v.isBlank()).toList();
        if (nonBlank.isEmpty()) return 0d;
        long numeric = nonBlank.stream().filter(BudgetSemanticResolver::looksLikeNumericAmount).count();
        long currency = nonBlank.stream().filter(BudgetSemanticResolver::looksLikeCurrency).count();
        long monthLike = nonBlank.stream().filter(BudgetSemanticResolver::looksLikeMonthLikeValue).count();
        long monthNumber = nonBlank.stream().filter(BudgetSemanticResolver::looksLikeMonthNumber).count();

        if (NUMERIC_CONCEPTS.contains(concept)) {
            return (numeric / (double) nonBlank.size()) * 0.40d;
        }
        if ("PERIOD".equals(concept)) {
            return ((monthLike + monthNumber) / (double) nonBlank.size()) * 0.40d;
        }
        if ("CURRENCY".equals(concept)) {
            return (currency / (double) nonBlank.size()) * 0.40d;
        }
        if (TEXT_CONCEPTS.contains(concept)) {
            return numeric == 0 ? 0.24d : 0.10d;
        }
        return 0d;
    }

    private static double valueScore(String concept, List<String> sampleValues, String clientKey) {
        List<String> nonBlank = sampleValues.stream().filter(Objects::nonNull).map(String::trim).filter(v -> !v.isBlank()).toList();
        if (nonBlank.isEmpty()) return 0d;
        if ("PERIOD".equals(concept)) {
            long hits = nonBlank.stream().filter(v -> looksLikeMonthLikeValue(v) || looksLikeMonthNumber(v)).count();
            return (hits / (double) nonBlank.size()) * 0.38d;
        }
        if ("CURRENCY".equals(concept)) {
            long hits = nonBlank.stream().filter(BudgetSemanticResolver::looksLikeCurrency).count();
            return (hits / (double) nonBlank.size()) * 0.38d;
        }
        if (NUMERIC_CONCEPTS.contains(concept)) {
            long hits = nonBlank.stream().filter(BudgetSemanticResolver::looksLikeNumericAmount).count();
            return (hits / (double) nonBlank.size()) * 0.20d;
        }
        Set<String> aliases = normalizedAliases(concept, clientKey);
        long hits = nonBlank.stream()
            .map(BudgetSemanticResolver::normalize)
            .filter(v -> aliasMatchScore(v, aliases) >= 0.75d)
            .count();
        return (hits / (double) nonBlank.size()) * 0.32d;
    }

    private static double relatedScore(String concept, String normalizedHeader) {
        return switch (concept) {
            case "BUDGET_AMOUNT" -> normalizedHeader.contains("budget") || normalizedHeader.contains("presupuesto") || normalizedHeader.contains("plan") ? 0.18d : 0d;
            case "ACTUAL_AMOUNT" -> normalizedHeader.contains("actual") || normalizedHeader.contains("real") || normalizedHeader.contains("ejecutado") ? 0.18d : 0d;
            case "FORECAST_AMOUNT" -> normalizedHeader.contains("forecast") || normalizedHeader.contains("prevision") || normalizedHeader.contains("estimate") ? 0.18d : 0d;
            case "VARIANCE" -> normalizedHeader.contains("desviacion") || normalizedHeader.contains("variance") || normalizedHeader.contains("delta") ? 0.18d : 0d;
            case "PERIOD" -> normalizedHeader.contains("periodo") || normalizedHeader.contains("month") || normalizedHeader.contains("mes") ? 0.18d : 0d;
            case "CONCEPT_CODE" -> normalizedHeader.contains("codigo") || normalizedHeader.contains("code") || normalizedHeader.contains("cuenta") ? 0.15d : 0d;
            case "CONCEPT_NAME" -> normalizedHeader.contains("concepto") || normalizedHeader.contains("descripcion") || normalizedHeader.contains("name") ? 0.15d : 0d;
            case "COST_CENTER" -> normalizedHeader.contains("centro") || normalizedHeader.contains("cost center") ? 0.15d : 0d;
            case "DEPARTMENT" -> normalizedHeader.contains("departamento") || normalizedHeader.contains("department") ? 0.15d : 0d;
            case "CURRENCY" -> normalizedHeader.contains("moneda") || normalizedHeader.contains("currency") ? 0.15d : 0d;
            default -> 0d;
        };
    }

    private static double semanticRuleBoost(String concept, String normalized) {
        if (normalized == null || normalized.isBlank()) return 0d;
        return switch (concept) {
            case "OTHER_OPERATING_INCOME" -> containsAll(normalized, "operating", "income")
                || containsAll(normalized, "ingreso", "operativo")
                || containsAll(normalized, "ingreso", "explotacion")
                    ? 0.95d
                    : 0d;
            case "OPENING_BALANCE" -> containsAll(normalized, "saldo", "inicial")
                || containsAll(normalized, "opening", "balance")
                || containsAll(normalized, "cash", "opening")
                    ? 0.96d
                    : 0d;
            case "CLOSING_BALANCE" -> containsAll(normalized, "saldo", "final")
                || containsAll(normalized, "ending", "balance")
                || containsAll(normalized, "closing", "balance")
                || containsAll(normalized, "cash", "ending")
                    ? 0.96d
                    : 0d;
            case "CASH_INFLOW" -> normalized.contains("cobro")
                || normalized.contains("entrada caja")
                || normalized.contains("cash in")
                || normalized.contains("collection")
                || normalized.contains("receipt")
                    ? 0.84d
                    : 0d;
            case "CASH_OUTFLOW" -> normalized.contains("pago")
                || normalized.contains("salida caja")
                || normalized.contains("cash out")
                || normalized.contains("disbursement")
                || normalized.contains("payment")
                    ? 0.84d
                    : 0d;
            case "FINANCING" -> normalized.contains("prestamo")
                || normalized.contains("financiacion")
                || normalized.contains("deuda")
                || normalized.contains("loan")
                || normalized.contains("financing")
                || normalized.contains("debt")
                || normalized.contains("leasing")
                || containsAll(normalized, "ingreso", "financ")
                || containsAll(normalized, "gasto", "financ")
                || containsAll(normalized, "interest", "income")
                || containsAll(normalized, "interest", "expense")
                    ? (containsAll(normalized, "ingreso", "financ")
                        || containsAll(normalized, "gasto", "financ")
                        || containsAll(normalized, "interest", "income")
                        || containsAll(normalized, "interest", "expense")
                        ? 0.98d
                        : 0.86d)
                    : 0d;
            case "FINANCIAL_RESULT" -> containsAll(normalized, "resultado", "financ")
                || containsAll(normalized, "financial", "result")
                || containsAll(normalized, "net", "finance")
                    ? 0.98d
                    : 0d;
            case "TAX" -> normalized.contains("impuesto")
                || normalized.contains("iva")
                || normalized.contains("tax")
                || normalized.contains("vat")
                    ? 0.9d
                    : 0d;
            case "OPEX" -> normalized.contains("compra")
                || normalized.contains("purchase")
                || normalized.contains("aprovisionamiento")
                || normalized.contains("mercaderia")
                || normalized.contains("materia prima")
                || normalized.contains("sueldo")
                || normalized.contains("salario")
                || normalized.contains("nomina")
                || normalized.contains("alquiler")
                || normalized.contains("rent")
                || normalized.contains("insurance")
                || normalized.contains("seguro")
                || normalized.contains("gestoria")
                || normalized.contains("advisory fee")
                || normalized.contains("maintenance")
                || normalized.contains("mantenimiento")
                || normalized.contains("reparacion")
                || normalized.contains("repair")
                || normalized.contains("limpieza")
                || normalized.contains("cleaning")
                || normalized.contains("consumible")
                || normalized.contains("suministro")
                || normalized.contains("utility")
                || normalized.contains("agua")
                || normalized.contains("luz")
                || normalized.contains("electric")
                    ? 0.94d
                    : 0d;
            case "OPERATING_ADJUSTMENT" -> containsAll(normalized, "variacion", "existencias")
                || containsAll(normalized, "variation", "inventory")
                || containsAll(normalized, "change", "inventory")
                    ? 0.96d
                    : 0d;
            case "CAPEX" -> normalized.contains("amortizacion")
                || normalized.contains("depreciation")
                || normalized.contains("depreciacion")
                    ? 0d
                    : normalized.contains("inmovilizado")
                || normalized.contains("capital expenditure")
                || normalized.contains("asset purchase")
                || normalized.contains("investment")
                    ? 0.95d
                    : 0d;
            case "DEPRECIATION_AMORTIZATION" -> normalized.contains("amortizacion")
                || normalized.contains("depreciation")
                    ? 0.95d
                    : 0d;
            case "ASSUMPTION" -> normalized.contains("hipotesis")
                || normalized.contains("supuesto")
                || normalized.contains("assumption")
                || normalized.contains("inflacion")
                || containsToken(normalized, "ipc")
                || containsToken(normalized, "euribor")
                || containsToken(normalized, "precio")
                || containsToken(normalized, "tarifa")
                    ? 0.88d
                    : 0d;
            default -> 0d;
        };
    }

    private static double codeRuleBoost(String concept, String normalized) {
        if (normalized == null || normalized.isBlank()) return 0d;
        String compact = normalized.replace(" ", "");
        if (!compact.matches("^[0-9]{2,4}$")) return 0d;
        if (compact.startsWith("76")) {
            return "FINANCING".equals(concept) ? 0.97d : ("REVENUE".equals(concept) ? 0.35d : 0d);
        }
        if (compact.startsWith("7")) {
            return "REVENUE".equals(concept) ? 0.92d : 0d;
        }
        if (compact.startsWith("68")) {
            return "DEPRECIATION_AMORTIZATION".equals(concept) ? 0.96d : 0d;
        }
        if (compact.startsWith("6")) {
            return Set.of("OPEX", "FINANCING").contains(concept)
                ? ("FINANCING".equals(concept) && (compact.startsWith("66") || compact.startsWith("67")) ? 0.91d : ("OPEX".equals(concept) ? 0.89d : 0d))
                : 0d;
        }
        if (compact.startsWith("2")) {
            return "CAPEX".equals(concept) ? 0.9d : 0d;
        }
        return 0d;
    }

    private static boolean containsAll(String normalized, String first, String second) {
        return normalized.contains(first) && normalized.contains(second);
    }

    private static boolean containsToken(String normalized, String token) {
        return tokenSet(normalized).contains(token);
    }

    private static double natureValueScore(List<String> values) {
        if (values == null || values.isEmpty()) return 0d;
        int hits = 0;
        int total = 0;
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized.isBlank()) continue;
            total++;
            NatureInference inference = classifyBusinessNature(null, value);
            if (!"UNKNOWN".equals(inference.concept()) && inference.score() >= 0.55d) hits++;
        }
        return total == 0 ? 0d : hits / (double) total;
    }

    private static NatureInference detectExplicitNature(String... values) {
        if (values == null) return null;
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized.isBlank()) continue;
            String concept = EXPLICIT_NATURE_VALUES.get(normalized);
            if (concept != null) {
                return new NatureInference(concept, 0.99d, "HIGH", false, List.of("clasificado por naturaleza explicita de origen"));
            }
        }
        return null;
    }

    private static double tokenOverlapScore(String normalizedHeader, Collection<String> aliases) {
        if (normalizedHeader == null || normalizedHeader.isBlank()) return 0d;
        return aliasMatchScore(normalizedHeader, aliases.stream().map(BudgetSemanticResolver::normalize).collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    private static double aliasMatchScore(String normalizedCandidate, Collection<String> normalizedAliases) {
        double best = 0d;
        Set<String> candidateTokens = tokenSet(normalizedCandidate);
        for (String alias : normalizedAliases) {
            if (alias == null || alias.isBlank()) continue;
            String normalizedAlias = normalize(alias);
            if (normalizedAlias.isBlank()) continue;
            if (normalizedCandidate.equals(normalizedAlias)) {
                return 1d;
            }
            if (normalizedCandidate.contains(normalizedAlias) || normalizedAlias.contains(normalizedCandidate)) {
                best = Math.max(best, 0.84d);
            }
            Set<String> aliasTokens = tokenSet(normalizedAlias);
            if (!candidateTokens.isEmpty() && !aliasTokens.isEmpty()) {
                long common = candidateTokens.stream().filter(aliasTokens::contains).count();
                double ratio = common / (double) Math.max(candidateTokens.size(), aliasTokens.size());
                if (ratio >= 0.5d) {
                    best = Math.max(best, 0.55d + (ratio * 0.25d));
                }
            }
        }
        return Math.min(1d, best);
    }

    private static Set<String> tokenSet(String normalized) {
        if (normalized == null || normalized.isBlank()) return Set.of();
        return List.of(normalized.split("\\s+")).stream()
            .map(String::trim)
            .filter(token -> !token.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> normalizedAliases(String concept, String clientKey) {
        return BudgetSemanticDictionary.aliasesFor(concept, clientKey).stream()
            .map(BudgetSemanticResolver::normalize)
            .filter(alias -> !alias.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> normalizedExclusions(String concept, String clientKey) {
        return BudgetSemanticDictionary.exclusionsFor(concept, clientKey).stream()
            .map(BudgetSemanticResolver::normalize)
            .filter(alias -> !alias.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<String> sampleValues(List<Map<String, String>> sampleRows, String header, int max) {
        if (sampleRows == null || header == null || max <= 0) return List.of();
        List<String> out = new ArrayList<>();
        for (Map<String, String> row : sampleRows) {
            if (row == null) continue;
            if (out.size() >= max) break;
            out.add(row.get(header));
        }
        return out;
    }

    private static String stripAccents(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD);
        return NON_ASCII_MARKS.matcher(normalized).replaceAll("");
    }

    private static String confidenceOf(double score) {
        if (score >= 0.88d) return "HIGH";
        if (score >= 0.72d) return "MEDIUM";
        return "LOW";
    }

    private static double round(double value) {
        return Math.round(value * 100d) / 100d;
    }

    public record HeaderInference(String concept,
                                  String header,
                                  double score,
                                  String confidence,
                                  boolean ambiguous,
                                  List<String> reasons) {}

    public record NatureInference(String concept,
                                  double score,
                                  String confidence,
                                  boolean ambiguous,
                                  List<String> reasons) {}

    public record Resolution(Map<String, HeaderInference> matches,
                             List<String> notes,
                             boolean requiresConfirmation) {
        public HeaderInference inferenceFor(String concept) {
            return matches == null ? null : matches.get(concept);
        }

        public String headerFor(String concept) {
            HeaderInference inference = inferenceFor(concept);
            return inference == null ? null : inference.header();
        }
    }

    private record HeaderCandidate(String header, double score, List<String> reasons) {}
}

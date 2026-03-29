package com.asecon.enterpriseiq.service;

import java.util.Locale;
import java.util.Set;

public final class RecommendationObjective {
    public static final String GENERAL = "GENERAL";
    public static final String CASH = "CASH";
    public static final String COST = "COST";
    public static final String MARGIN = "MARGIN";
    public static final String GROWTH = "GROWTH";
    public static final String RISK = "RISK";

    private static final Set<String> ALLOWED = Set.of(GENERAL, CASH, COST, MARGIN, GROWTH, RISK);

    private RecommendationObjective() {}

    public static String normalize(String raw) {
        if (raw == null) return GENERAL;
        String s = raw.trim();
        if (s.isBlank()) return GENERAL;
        s = s.toUpperCase(Locale.ROOT);

        // Spanish aliases (UI-friendly)
        if ("CAJA".equals(s) || "TESORERIA".equals(s) || "TESORERÍA".equals(s)) return CASH;
        if ("COSTES".equals(s) || "COSTO".equals(s) || "COSTOS".equals(s)) return COST;
        if ("MARGEN".equals(s) || "MÁRGEN".equals(s) || "MARGENES".equals(s) || "MÁRGENES".equals(s)) return MARGIN;
        if ("CRECIMIENTO".equals(s) || "VENTAS".equals(s) || "INGRESOS".equals(s)) return GROWTH;
        if ("RIESGO".equals(s) || "RISK".equals(s)) return RISK;

        if (ALLOWED.contains(s)) return s;
        return GENERAL;
    }

    public static String toSource(String objective) {
        String obj = normalize(objective);
        if (GENERAL.equals(obj)) return "RULES";
        return "RULES_" + obj;
    }
}


package com.asecon.enterpriseiq.service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;

public final class BudgetTraceLogger {
    private static final String TRACE_PROPERTY = "enterpriseiq.budget.trace";
    private static final String TRACE_ENV = "ENTERPRISEIQ_BUDGET_TRACE";
    private static final Set<String> TARGET_LABELS = Set.of(
        "compras de mercaderia",
        "sueldos y seguridad social",
        "ingresos de explotacion",
        "gastos explotacion",
        "otros gastos explotacion",
        "trabajos realizados otras empresas",
        "arrendamientos y canones",
        "suministros",
        "sueldos y salarios trabajadores",
        "alquiler del local",
        "luz",
        "resultado financiero",
        "cobros de ventas",
        "pagos de personal",
        "pagos de alquiler luz y agua",
        "compra de neveras y equipos",
        "prestamo recibido",
        "total gastos",
        "beneficio neto"
    );

    private BudgetTraceLogger() {}

    public static boolean enabled() {
        String property = System.getProperty(TRACE_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Boolean.parseBoolean(property);
        }
        String env = System.getenv(TRACE_ENV);
        if (env == null || env.isBlank()) return false;
        return "1".equals(env) || "true".equalsIgnoreCase(env) || "yes".equalsIgnoreCase(env);
    }

    public static boolean shouldTraceLabel(String rawLabel) {
        if (!enabled()) return false;
        String normalized = normalize(rawLabel);
        if (normalized.isBlank()) return false;
        return TARGET_LABELS.stream().anyMatch(normalized::contains);
    }

    public static String normalize(String value) {
        if (value == null) return "";
        String repaired = value
            .replace("\\u00f1", "ñ")
            .replace("\\u00e1", "á")
            .replace("\\u00e9", "é")
            .replace("\\u00ed", "í")
            .replace("\\u00f3", "ó")
            .replace("\\u00fa", "ú")
            .replace("\\u00d1", "Ñ");
        String ascii = Normalizer.normalize(repaired, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "");
        return ascii
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", " ")
            .trim();
    }

    public static String fmt(BigDecimal value) {
        return value == null ? "null" : value.toPlainString();
    }

    public static void log(Logger log, String event, Map<String, ?> fields) {
        if (!enabled() || !log.isInfoEnabled()) return;
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("event", event);
        ordered.putAll(fields);
        StringBuilder message = new StringBuilder("BUDGET_TRACE");
        ordered.forEach((key, value) -> message.append(' ').append(key).append('=').append(safe(value)));
        log.info(message.toString());
    }

    public static Map<String, Object> fields(Object... keyValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (keyValues == null) return out;
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            out.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return out;
    }

    private static String safe(Object value) {
        if (value == null) return "null";
        String raw = String.valueOf(value).replaceAll("\\s+", " ").trim();
        if (raw.isEmpty()) return "\"\"";
        return raw;
    }
}

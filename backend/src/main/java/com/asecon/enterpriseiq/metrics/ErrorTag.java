package com.asecon.enterpriseiq.metrics;

import org.springframework.web.server.ResponseStatusException;

public final class ErrorTag {
    private ErrorTag() {}

    public static String fromException(Throwable ex) {
        if (ex == null) return "unknown";

        if (ex instanceof ResponseStatusException rse) {
            try {
                return "http_" + rse.getStatusCode().value();
            } catch (Exception ignored) {}
        }

        String name = ex.getClass().getSimpleName();
        if (name == null || name.isBlank()) return "unknown";

        // Reduce cardinality for inner/proxy classes (e.g. Foo$Bar, Foo$$EnhancerBySpringCGLIB)
        int dollar = name.indexOf('$');
        if (dollar > 0) name = name.substring(0, dollar);

        name = name.trim();
        if (name.isEmpty()) return "unknown";
        if (name.length() > 60) name = name.substring(0, 60);
        return name;
    }
}


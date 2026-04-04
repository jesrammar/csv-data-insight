package com.asecon.enterpriseiq.metrics;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ErrorTagger {
    private final int maxUnique;
    private final String otherLabel;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    public ErrorTagger(@Value("${app.metrics.error-tag.max-unique:40}") int maxUnique,
                       @Value("${app.metrics.error-tag.other-label:other}") String otherLabel) {
        this.maxUnique = Math.max(5, maxUnique);
        this.otherLabel = (otherLabel == null || otherLabel.isBlank()) ? "other" : otherLabel.trim();
    }

    public String tag(Throwable ex) {
        if (ex == null) return "unknown";

        if (ex instanceof ResponseStatusException rse) {
            try {
                return "http_" + rse.getStatusCode().value();
            } catch (Exception ignored) {
                return "http_unknown";
            }
        }

        String base = ErrorTag.fromException(ex);
        if (base == null || base.isBlank()) base = "unknown";

        // Hard cap on distinct tag values to avoid cardinality explosions.
        if (!seen.contains(base) && seen.size() >= maxUnique) {
            return otherLabel;
        }
        seen.add(base);
        return base;
    }
}


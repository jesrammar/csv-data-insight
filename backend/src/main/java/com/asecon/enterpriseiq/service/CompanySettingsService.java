package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.CompanySettings;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.asecon.enterpriseiq.repo.CompanySettingsRepository;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompanySettingsService {
    private final CompanySettingsRepository settingsRepository;
    private final CompanyRepository companyRepository;

    public CompanySettingsService(CompanySettingsRepository settingsRepository, CompanyRepository companyRepository) {
        this.settingsRepository = settingsRepository;
        this.companyRepository = companyRepository;
    }

    @Transactional
    public CompanySettings getOrCreate(Long companyId) {
        if (companyId == null) throw new IllegalArgumentException("companyId required");
        return settingsRepository.findById(companyId).orElseGet(() -> {
            var company = companyRepository.findById(companyId).orElseThrow();
            CompanySettings s = new CompanySettings();
            s.setCompany(company);
            s.setWorkingPeriod(null);
            s.setAutoMonthlyReport(true);
            s.setCreatedAt(Instant.now());
            s.setUpdatedAt(s.getCreatedAt());
            return settingsRepository.save(s);
        });
    }

    @Transactional
    public CompanySettings update(Long companyId,
                                  String workingPeriod,
                                  Boolean autoMonthlyReport,
                                  String reportConsultancyName,
                                  String reportLogoUrl,
                                  String reportPrimaryColor,
                                  String reportFooterText) {
        CompanySettings s = getOrCreate(companyId);
        if (workingPeriod != null) {
            s.setWorkingPeriod(normalizePeriodOrThrow(workingPeriod));
        }
        if (autoMonthlyReport != null) {
            s.setAutoMonthlyReport(Boolean.TRUE.equals(autoMonthlyReport));
        }
        if (reportConsultancyName != null) {
            s.setReportConsultancyName(normalizeText(reportConsultancyName, 140));
        }
        if (reportLogoUrl != null) {
            s.setReportLogoUrl(normalizeReportLogoOrThrow(reportLogoUrl));
        }
        if (reportPrimaryColor != null) {
            s.setReportPrimaryColor(normalizeColorOrNull(reportPrimaryColor));
        }
        if (reportFooterText != null) {
            s.setReportFooterText(normalizeText(reportFooterText, 400));
        }
        s.setUpdatedAt(Instant.now());
        return settingsRepository.save(s);
    }

    public String resolveWorkingPeriod(Long companyId, String fallback) {
        if (companyId == null) return fallback;
        try {
            CompanySettings s = settingsRepository.findById(companyId).orElse(null);
            String v = s == null ? null : normalizePeriodOrNull(s.getWorkingPeriod());
            if (v != null) return v;
        } catch (Exception ignored) {}
        return fallback;
    }

    public boolean autoMonthlyReportEnabled(Long companyId) {
        if (companyId == null) return true;
        CompanySettings s = settingsRepository.findById(companyId).orElse(null);
        return s == null || s.isAutoMonthlyReport();
    }

    private static String normalizePeriodOrNull(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;
        try {
            return YearMonth.parse(v).toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private static String normalizePeriodOrThrow(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;
        try {
            return YearMonth.parse(v).toString();
        } catch (Exception ex) {
            throw new IllegalArgumentException("workingPeriod invalido (usa YYYY-MM)");
        }
    }

    private static String normalizeText(String raw, int maxLen) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;
        if (maxLen > 0 && v.length() > maxLen) {
            return v.substring(0, maxLen);
        }
        return v;
    }

    private static String normalizeReportLogoOrThrow(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;
        if (v.length() > 500) {
            throw new IllegalArgumentException("reportLogoUrl demasiado largo");
        }

        String lower = v.toLowerCase(Locale.ROOT);
        boolean allowedPrefix = lower.startsWith("data:image/png;base64,")
            || lower.startsWith("data:image/jpeg;base64,")
            || lower.startsWith("data:image/jpg;base64,")
            || lower.startsWith("data:image/webp;base64,")
            || lower.startsWith("data:image/gif;base64,");
        if (!allowedPrefix) {
            throw new IllegalArgumentException("reportLogoUrl invalido: usa solo data:image/* en base64");
        }

        int comma = v.indexOf(',');
        if (comma < 0 || comma == v.length() - 1) {
            throw new IllegalArgumentException("reportLogoUrl invalido");
        }

        String payload = v.substring(comma + 1).trim();
        if (!payload.matches("^[A-Za-z0-9+/=\\r\\n]+$")) {
            throw new IllegalArgumentException("reportLogoUrl invalido: base64 no valido");
        }
        return v;
    }

    private static String normalizeColorOrNull(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;
        // Accept #RRGGBB or #RGB.
        if (v.matches("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})$")) return v;
        // Allow "rgb(...)" / "rgba(...)" as-is for demos (but keep short to avoid abuse).
        if (v.toLowerCase().startsWith("rgb(") || v.toLowerCase().startsWith("rgba(")) {
            return v.length() > 32 ? v.substring(0, 32) : v;
        }
        return null;
    }
}

package com.asecon.enterpriseiq.security;

import com.asecon.enterpriseiq.model.AuditEvent;
import com.asecon.enterpriseiq.repo.AuditEventRepository;
import com.asecon.enterpriseiq.service.AccessService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;

public class AuditLogFilter extends OncePerRequestFilter {
    private static final Pattern COMPANY = Pattern.compile("^/api/companies/(\\d+)(/.*)?$");
    private static final Pattern REPORT = Pattern.compile("^/api/companies/(\\d+)/reports/(\\d+)/");
    private static final Pattern UNIVERSAL_VIEW = Pattern.compile("^/api/companies/(\\d+)/universal/views/(\\d+)(/.*)?$");
    private static final Pattern UNIVERSAL_IMPORT = Pattern.compile("^/api/companies/(\\d+)/universal/imports/(\\d+)(/.*)?$");

    private final AuditEventRepository auditEventRepository;
    private final AccessService accessService;
    private final boolean enabled;

    public AuditLogFilter(AuditEventRepository auditEventRepository,
                          AccessService accessService,
                          boolean enabled) {
        this.auditEventRepository = auditEventRepository;
        this.accessService = accessService;
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) return true;
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        long start = System.nanoTime();
        int status = 200;
        Exception error = null;

        try {
            filterChain.doFilter(request, response);
            status = response.getStatus();
        } catch (Exception ex) {
            error = ex;
            status = response.getStatus() > 0 ? response.getStatus() : 500;
            throw ex;
        } finally {
            try {
                String method = safeUpper(request.getMethod());
                String path = request.getRequestURI();
                ResolvedAction resolved = resolveAction(method, path);
                if (resolved == null) return;

                Long userId = null;
                var u = accessService.currentUser();
                if (u != null) userId = u.getId();

                AuditEvent e = new AuditEvent();
                e.setAt(Instant.now());
                e.setUserId(userId);
                e.setCompanyId(resolved.companyId);
                e.setAction(resolved.action);
                e.setMethod(method);
                e.setPath(path);
                e.setStatus(status);
                e.setDurationMs(Math.max(0L, (System.nanoTime() - start) / 1_000_000L));
                e.setIp(extractIp(request));
                e.setUserAgent(truncate(request.getHeader("User-Agent"), 400));
                e.setResourceType(resolved.resourceType);
                e.setResourceId(resolved.resourceId);
                if (error != null) {
                    e.setMetaJson("{\"error\":\"" + escapeJson(truncate(error.getClass().getSimpleName(), 80)) + "\"}");
                }

                auditEventRepository.save(e);
            } catch (Exception ignored) {
                // never block request because of audit logging
            }
        }
    }

    record ResolvedAction(String action, Long companyId, String resourceType, String resourceId) {}

    static ResolvedAction resolveAction(String method, String path) {
        if (path == null) return null;

        // Auth
        if ("POST".equals(method) && "/api/auth/login".equals(path)) return new ResolvedAction("AUTH_LOGIN", null, "AUTH", null);
        if ("POST".equals(method) && "/api/auth/refresh".equals(path)) return new ResolvedAction("AUTH_REFRESH", null, "AUTH", null);
        if ("POST".equals(method) && "/api/auth/logout".equals(path)) return new ResolvedAction("AUTH_LOGOUT", null, "AUTH", null);
        if ("POST".equals(method) && "/api/auth/password/change".equals(path)) return new ResolvedAction("AUTH_PASSWORD_CHANGE", null, "AUTH", null);

        // Admin storage cleanup
        if ("POST".equals(method) && "/api/admin/storage/cleanup/run".equals(path)) {
            return new ResolvedAction("STORAGE_CLEANUP_RUN", null, "STORAGE", null);
        }

        // Company-scoped actions
        Matcher m = COMPANY.matcher(path);
        if (!m.matches()) return null;
        Long companyId = parseLong(m.group(1));

        if ("POST".equals(method) && path.endsWith("/imports")) return new ResolvedAction("IMPORT_UPLOAD", companyId, "IMPORT", null);
        if ("POST".equals(method) && path.endsWith("/imports/smart")) return new ResolvedAction("IMPORT_UPLOAD_SMART", companyId, "IMPORT", null);
        if ("GET".equals(method) && path.endsWith("/transactions/export.csv")) return new ResolvedAction("TRANSACTIONS_EXPORT_CSV", companyId, "TRANSACTIONS", null);
        if ("GET".equals(method) && path.endsWith("/powerbi/export.zip")) return new ResolvedAction("POWERBI_EXPORT_ZIP", companyId, "POWERBI", null);
        if ("POST".equals(method) && path.endsWith("/reports")) return new ResolvedAction("REPORT_GENERATE", companyId, "REPORT", null);

        // Tribunal
        if ("POST".equals(method) && path.endsWith("/tribunal/imports")) return new ResolvedAction("TRIBUNAL_UPLOAD", companyId, "TRIBUNAL", null);
        if ("GET".equals(method) && path.endsWith("/tribunal/exports.csv")) return new ResolvedAction("TRIBUNAL_EXPORT_CSV", companyId, "TRIBUNAL", null);

        // Universal imports
        if ("POST".equals(method) && path.endsWith("/universal/imports")) return new ResolvedAction("UNIVERSAL_UPLOAD", companyId, "UNIVERSAL_IMPORT", null);
        if ("POST".equals(method) && path.endsWith("/universal/xlsx/preview")) return new ResolvedAction("UNIVERSAL_XLSX_PREVIEW", companyId, "UNIVERSAL", null);
        if ("GET".equals(method) && path.endsWith("/universal/imports/latest/normalized.csv")) return new ResolvedAction("UNIVERSAL_DOWNLOAD_NORMALIZED_CSV", companyId, "UNIVERSAL_IMPORT", "latest");
        if ("GET".equals(method) && path.endsWith("/universal/imports/latest/rows")) return new ResolvedAction("UNIVERSAL_ROWS_PREVIEW", companyId, "UNIVERSAL_IMPORT", "latest");

        Matcher ui = UNIVERSAL_IMPORT.matcher(path);
        if (ui.matches()) {
            String importId = ui.group(2);
            if ("GET".equals(method) && path.endsWith("/normalized.csv")) return new ResolvedAction("UNIVERSAL_DOWNLOAD_NORMALIZED_CSV", companyId, "UNIVERSAL_IMPORT", importId);
            if ("GET".equals(method) && path.endsWith("/rows")) return new ResolvedAction("UNIVERSAL_ROWS_PREVIEW", companyId, "UNIVERSAL_IMPORT", importId);
        }

        // Universal dashboards
        if ("POST".equals(method) && path.endsWith("/universal/builder/preview")) return new ResolvedAction("UNIVERSAL_BUILDER_PREVIEW", companyId, "UNIVERSAL", null);
        if ("POST".equals(method) && path.endsWith("/universal/builder/problems.csv")) return new ResolvedAction("UNIVERSAL_BUILDER_PROBLEMS_CSV", companyId, "UNIVERSAL", null);
        if ("POST".equals(method) && path.endsWith("/universal/views")) return new ResolvedAction("UNIVERSAL_VIEW_CREATE", companyId, "UNIVERSAL_VIEW", null);

        Matcher uv = UNIVERSAL_VIEW.matcher(path);
        if (uv.matches()) {
            String viewId = uv.group(2);
            if ("POST".equals(method) && path.endsWith("/data")) return new ResolvedAction("UNIVERSAL_VIEW_QUERY", companyId, "UNIVERSAL_VIEW", viewId);
        }

        Matcher r = REPORT.matcher(path);
        if (r.find()) {
            String reportId = r.group(2);
            if ("GET".equals(method) && path.endsWith("/content")) return new ResolvedAction("REPORT_VIEW_HTML", companyId, "REPORT", reportId);
            if ("GET".equals(method) && path.endsWith("/content.pdf")) return new ResolvedAction("REPORT_DOWNLOAD_PDF", companyId, "REPORT", reportId);
        }

        return null;
    }

    private static Long parseLong(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String safeUpper(String raw) {
        return raw == null ? "" : raw.toUpperCase(Locale.ROOT);
    }

    private static String extractIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            return truncate(first, 64);
        }
        return truncate(request.getRemoteAddr(), 64);
    }

    private static String truncate(String raw, int max) {
        if (raw == null) return null;
        String s = raw.trim();
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String escapeJson(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.PowerBiExportService;
import com.asecon.enterpriseiq.model.Plan;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipOutputStream;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/companies/{companyId}/powerbi")
public class PowerBiExportController {
    private final AccessService accessService;
    private final PowerBiExportService powerBiExportService;

    public PowerBiExportController(AccessService accessService, PowerBiExportService powerBiExportService) {
        this.accessService = accessService;
        this.powerBiExportService = powerBiExportService;
    }

    @GetMapping(value = "/export.zip", produces = "application/zip")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public ResponseEntity<StreamingResponseBody> export(@PathVariable Long companyId,
                                                        @RequestParam String from,
                                                        @RequestParam String to) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.PLATINUM);
        var company = accessService.requireCompany(companyId);

        String safeFrom = sanitize(from);
        String safeTo = sanitize(to);
        String filename = ("enterpriseiq-powerbi-" + companyId + "-" + safeFrom + "-" + safeTo + ".zip")
            .replaceAll("[^a-zA-Z0-9._-]", "_");

        StreamingResponseBody body = outputStream -> {
            try (var zos = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
                powerBiExportService.writeZip(zos, company, safeFrom, safeTo);
                zos.finish();
            }
        };

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("application/zip"))
            .body(body);
    }

    private static String sanitize(String v) {
        if (v == null) return "unknown";
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.length() >= 7) return s.substring(0, 7);
        return s.replaceAll("[^0-9a-zA-Z_-]", "");
    }
}

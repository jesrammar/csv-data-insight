package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.TribunalImportDto;
import com.asecon.enterpriseiq.dto.TribunalSummaryDto;
import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.TribunalImportService;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/companies/{companyId}/tribunal")
public class TribunalController {
    private final TribunalImportService tribunalImportService;
    private final AccessService accessService;

    public TribunalController(TribunalImportService tribunalImportService, AccessService accessService) {
        this.tribunalImportService = tribunalImportService;
        this.accessService = accessService;
    }

    @GetMapping("/summary")
    public TribunalSummaryDto summary(@PathVariable Long companyId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.GOLD);
        return tribunalImportService.getSummary(companyId);
    }

    @PostMapping("/imports")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public TribunalImportDto upload(@PathVariable Long companyId,
                                    @RequestPart("file") @NotNull MultipartFile file) throws IOException {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.GOLD);
        return tribunalImportService.importCsv(companyId, file);
    }

    @GetMapping(value = "/exports.csv", produces = "text/csv")
    public ResponseEntity<String> exportCsv(@PathVariable Long companyId) throws IOException {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.GOLD);
        String body = tribunalImportService.exportCsv(companyId);
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "csv"))
            .body(body);
    }
}

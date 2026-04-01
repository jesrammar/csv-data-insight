package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.UniversalSummaryDto;
import com.asecon.enterpriseiq.dto.UniversalRowsDto;
import com.asecon.enterpriseiq.dto.UniversalXlsxPreviewDto;
import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.TabularFileService;
import com.asecon.enterpriseiq.service.UniversalCsvService;
import com.asecon.enterpriseiq.service.UniversalImportFileService;
import com.asecon.enterpriseiq.service.UploadLimitService;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/companies/{companyId}/universal")
public class UniversalController {
    private final UniversalCsvService universalCsvService;
    private final AccessService accessService;
    private final TabularFileService tabularFileService;
    private final UniversalImportFileService universalImportFileService;
    private final UploadLimitService uploadLimitService;

    public UniversalController(UniversalCsvService universalCsvService,
                               AccessService accessService,
                               TabularFileService tabularFileService,
                               UniversalImportFileService universalImportFileService,
                               UploadLimitService uploadLimitService) {
        this.universalCsvService = universalCsvService;
        this.accessService = accessService;
        this.tabularFileService = tabularFileService;
        this.universalImportFileService = universalImportFileService;
        this.uploadLimitService = uploadLimitService;
    }

    @GetMapping("/summary")
    public Optional<UniversalSummaryDto> summary(@PathVariable Long companyId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.BRONZE);
        return universalCsvService.latest(companyId);
    }

    @GetMapping(value = "/imports/latest/normalized.csv", produces = "text/csv")
    public ResponseEntity<byte[]> latestNormalizedCsv(@PathVariable Long companyId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.PLATINUM);
        byte[] bytes = universalImportFileService.latestNormalizedCsv(companyId);
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "csv"))
            .body(bytes);
    }

    @GetMapping("/imports/latest/rows")
    public UniversalRowsDto latestRows(@PathVariable Long companyId,
                                       @RequestParam(defaultValue = "50") int limit) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.PLATINUM);
        return universalImportFileService.latestRows(companyId, limit);
    }

    @PostMapping("/imports")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public UniversalSummaryDto upload(@PathVariable Long companyId,
                                      @RequestPart("file") @NotNull MultipartFile file,
                                      @RequestPart(name = "sheetIndex", required = false) String sheetIndex,
                                      @RequestPart(name = "headerRow", required = false) String headerRow) throws IOException {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        uploadLimitService.requireAllowed(file);
        var company = accessService.requireCompany(companyId);
        var plan = company.getPlan();
        accessService.requirePlanAtLeast(companyId, Plan.BRONZE);

        TabularFileService.XlsxOptions xlsxOptions = null;
        if (TabularFileService.isXlsx(file)) {
            Integer sheet = parseOptionalInt(sheetIndex);
            Integer header = parseOptionalInt(headerRow);
            if (sheet != null || header != null) {
                xlsxOptions = new TabularFileService.XlsxOptions(sheet, header);
            }
        }

        return universalCsvService.analyzeAndStore(companyId, file, plan, xlsxOptions);
    }

    @PostMapping("/xlsx/preview")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public UniversalXlsxPreviewDto previewXlsx(@PathVariable Long companyId,
                                               @RequestPart("file") @NotNull MultipartFile file,
                                               @RequestPart(name = "sheetIndex", required = false) String sheetIndex,
                                               @RequestPart(name = "headerRow", required = false) String headerRow) throws IOException {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        uploadLimitService.requireAllowed(file);
        accessService.requirePlanAtLeast(companyId, Plan.BRONZE);
        if (!TabularFileService.isXlsx(file)) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Se esperaba un XLSX");
        }

        Integer sheet = parseOptionalInt(sheetIndex);
        Integer header = parseOptionalInt(headerRow);
        TabularFileService.XlsxOptions options = (sheet != null || header != null)
            ? new TabularFileService.XlsxOptions(sheet, header)
            : null;

        var preview = tabularFileService.previewXlsx(file, options, 8);
        return new UniversalXlsxPreviewDto(
            preview.sheets(),
            preview.detectedSheetIndex(),
            preview.detectedHeaderRow1Based(),
            preview.detectedHeaders(),
            preview.sampleRows()
        );
    }

    private static Integer parseOptionalInt(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}

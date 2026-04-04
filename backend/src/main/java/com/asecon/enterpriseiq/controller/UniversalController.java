package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.UniversalSummaryDto;
import com.asecon.enterpriseiq.dto.UniversalAutoSuggestionDto;
import com.asecon.enterpriseiq.dto.UniversalImportDto;
import com.asecon.enterpriseiq.dto.UniversalRowsDto;
import com.asecon.enterpriseiq.dto.UniversalXlsxPreviewDto;
import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.TabularFileService;
import com.asecon.enterpriseiq.service.UniversalAutoSuggestionService;
import com.asecon.enterpriseiq.service.UniversalCsvService;
import com.asecon.enterpriseiq.service.UniversalImportFileService;
import com.asecon.enterpriseiq.service.UploadLimitService;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
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
import com.asecon.enterpriseiq.repo.UniversalImportRepository;

@RestController
@RequestMapping("/api/companies/{companyId}/universal")
public class UniversalController {
    private final UniversalCsvService universalCsvService;
    private final UniversalAutoSuggestionService universalAutoSuggestionService;
    private final AccessService accessService;
    private final TabularFileService tabularFileService;
    private final UniversalImportFileService universalImportFileService;
    private final UniversalImportRepository universalImportRepository;
    private final UploadLimitService uploadLimitService;

    public UniversalController(UniversalCsvService universalCsvService,
                               UniversalAutoSuggestionService universalAutoSuggestionService,
                               AccessService accessService,
                               TabularFileService tabularFileService,
                               UniversalImportFileService universalImportFileService,
                               UniversalImportRepository universalImportRepository,
                               UploadLimitService uploadLimitService) {
        this.universalCsvService = universalCsvService;
        this.universalAutoSuggestionService = universalAutoSuggestionService;
        this.accessService = accessService;
        this.tabularFileService = tabularFileService;
        this.universalImportFileService = universalImportFileService;
        this.universalImportRepository = universalImportRepository;
        this.uploadLimitService = uploadLimitService;
    }

    @GetMapping("/summary")
    public Optional<UniversalSummaryDto> summary(@PathVariable Long companyId,
                                                 @RequestParam(name = "importId", required = false) Long importId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.BRONZE);
        return universalCsvService.summary(companyId, importId);
    }

    @GetMapping("/suggestions")
    public List<UniversalAutoSuggestionDto> suggestions(@PathVariable Long companyId,
                                                        @RequestParam(name = "importId", required = false) Long importId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.BRONZE);
        return universalAutoSuggestionService.suggest(companyId, importId);
    }

    @GetMapping("/imports")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public List<UniversalImportDto> listImports(@PathVariable Long companyId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.BRONZE);
        return universalImportRepository.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
            .map(imp -> new UniversalImportDto(
                imp.getId(),
                imp.getFilename(),
                imp.getCreatedAt(),
                imp.getRowCount() == null ? 0 : imp.getRowCount(),
                imp.getColumnCount() == null ? 0 : imp.getColumnCount()
            ))
            .collect(Collectors.toList());
    }

    @GetMapping(value = "/imports/latest/normalized.csv", produces = "text/csv")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
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
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public UniversalRowsDto latestRows(@PathVariable Long companyId,
                                       @RequestParam(defaultValue = "50") int limit) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.PLATINUM);
        return universalImportFileService.latestRows(companyId, limit);
    }

    @GetMapping(value = "/imports/{importId}/normalized.csv", produces = "text/csv")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public ResponseEntity<byte[]> normalizedCsv(@PathVariable Long companyId, @PathVariable Long importId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.PLATINUM);
        byte[] bytes = universalImportFileService.normalizedCsv(companyId, importId);
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "csv"))
            .body(bytes);
    }

    @GetMapping("/imports/{importId}/rows")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public UniversalRowsDto rows(@PathVariable Long companyId,
                                 @PathVariable Long importId,
                                 @RequestParam(defaultValue = "50") int limit) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.PLATINUM);
        return universalImportFileService.rows(companyId, importId, limit);
    }

    @PostMapping("/imports")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public UniversalSummaryDto upload(@PathVariable Long companyId,
                                      @RequestPart("file") @NotNull MultipartFile file,
                                      @RequestPart(name = "sheetIndex", required = false) String sheetIndex,
                                      @RequestPart(name = "headerRow", required = false) String headerRow) throws IOException {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        var company = accessService.requireCompany(companyId);
        var plan = company.getPlan();
        accessService.requirePlanAtLeast(companyId, Plan.BRONZE);
        uploadLimitService.requireAllowed(file, plan);

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
        var company = accessService.requireCompany(companyId);
        uploadLimitService.requireAllowed(file, company.getPlan());
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

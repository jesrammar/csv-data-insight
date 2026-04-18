package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.ImportDto;
import com.asecon.enterpriseiq.dto.ImportPreviewDto;
import com.asecon.enterpriseiq.dto.ImportQualityDto;
import com.asecon.enterpriseiq.model.ImportJob;
import com.asecon.enterpriseiq.repo.ImportJobRepository;
import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.ImportMappingService;
import com.asecon.enterpriseiq.service.ImportService;
import com.asecon.enterpriseiq.service.ImportQualityService;
import com.asecon.enterpriseiq.service.TabularFileService;
import com.asecon.enterpriseiq.service.UploadLimitService;
import com.asecon.enterpriseiq.service.CompanySavedMappingService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/companies/{companyId}/imports")
public class ImportController {
    private final ImportService importService;
    private final AccessService accessService;
    private final ImportMappingService importMappingService;
    private final UploadLimitService uploadLimitService;
    private final CompanySavedMappingService savedMappingService;
    private final ObjectMapper objectMapper;
    private final ImportJobRepository importJobRepository;
    private final ImportQualityService importQualityService;

    public ImportController(ImportService importService,
                            AccessService accessService,
                            ImportMappingService importMappingService,
                            UploadLimitService uploadLimitService,
                            CompanySavedMappingService savedMappingService,
                            ObjectMapper objectMapper,
                            ImportJobRepository importJobRepository,
                            ImportQualityService importQualityService) {
        this.importService = importService;
        this.accessService = accessService;
        this.importMappingService = importMappingService;
        this.uploadLimitService = uploadLimitService;
        this.savedMappingService = savedMappingService;
        this.objectMapper = objectMapper;
        this.importJobRepository = importJobRepository;
        this.importQualityService = importQualityService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public List<ImportDto> list(@PathVariable Long companyId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        return importService.listByCompany(companyId).stream().map(this::toDto).collect(Collectors.toList());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public ImportDto upload(@PathVariable Long companyId,
                            @RequestParam("period") @NotBlank String period,
                            @RequestPart("file") MultipartFile file) throws IOException {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        var company = accessService.requireCompany(companyId);
        uploadLimitService.requireAllowed(file, company.getPlan());
        ImportJob job = importService.createImport(companyId, period, file);
        return toDto(job);
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public ImportPreviewDto preview(@PathVariable Long companyId,
                                    @RequestPart("file") @NotNull MultipartFile file,
                                    @RequestPart(name = "sheetIndex", required = false) String sheetIndex,
                                    @RequestPart(name = "headerRow", required = false) String headerRow) throws IOException {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        var company = accessService.requireCompany(companyId);
        uploadLimitService.requireAllowed(file, company.getPlan());
        TabularFileService.XlsxOptions xlsxOptions = null;
        Integer sheet = parseOptionalInt(sheetIndex);
        Integer header = parseOptionalInt(headerRow);
        if (TabularFileService.isXlsx(file) && (sheet != null || header != null)) {
            xlsxOptions = new TabularFileService.XlsxOptions(sheet, header);
        }
        return importMappingService.preview(file, xlsxOptions);
    }

    @PostMapping("/smart")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public ImportDto uploadSmart(@PathVariable Long companyId,
                                 @RequestParam("period") @NotBlank String period,
                                 @RequestPart("file") @NotNull MultipartFile file,
                                 @RequestPart("txnDateCol") @NotBlank String txnDateCol,
                                 @RequestPart("amountCol") @NotBlank String amountCol,
                                 @RequestPart(name = "descriptionCol", required = false) String descriptionCol,
                                 @RequestPart(name = "counterpartyCol", required = false) String counterpartyCol,
                                 @RequestPart(name = "balanceEndCol", required = false) String balanceEndCol,
                                 @RequestPart(name = "sheetIndex", required = false) String sheetIndex,
                                 @RequestPart(name = "headerRow", required = false) String headerRow) throws IOException {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        var company = accessService.requireCompany(companyId);
        uploadLimitService.requireAllowed(file, company.getPlan());
        Integer sheet = parseOptionalInt(sheetIndex);
        Integer header = parseOptionalInt(headerRow);
        ImportJob job = importService.createImportMapped(companyId, period, file, txnDateCol, amountCol, descriptionCol, counterpartyCol, balanceEndCol, sheet, header);

        // Guardar mapeo por empresa para futuras cargas (producto consultora).
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("txnDateCol", txnDateCol);
            map.put("amountCol", amountCol);
            if (descriptionCol != null) map.put("descriptionCol", descriptionCol);
            if (counterpartyCol != null) map.put("counterpartyCol", counterpartyCol);
            if (balanceEndCol != null) map.put("balanceEndCol", balanceEndCol);
            if (sheet != null) map.put("sheetIndex", sheet);
            if (header != null) map.put("headerRow", header);
            String payload = objectMapper.writeValueAsString(map);
            savedMappingService.upsert(companyId, CompanySavedMappingService.KEY_IMPORTS_SMART, payload);
        } catch (Exception ignored) {}
        return toDto(job);
    }

    @PostMapping("/{importId}/retry")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public ImportDto retry(@PathVariable Long companyId, @PathVariable Long importId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        ImportJob job = importService.retry(companyId, importId);
        return toDto(job);
    }

    @GetMapping("/{importId}/quality")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public ImportQualityDto quality(@PathVariable Long companyId, @PathVariable Long importId) throws IOException {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        ImportJob job = importJobRepository.findById(importId).orElseThrow();
        if (!job.getCompany().getId().equals(companyId)) {
            throw new IllegalArgumentException("Import not found for company");
        }
        return importQualityService.compute(job);
    }

    private ImportDto toDto(ImportJob job) {
        return new ImportDto(job.getId(), job.getCompany().getId(), job.getPeriod(), job.getStatus(),
            job.getCreatedAt(), job.getProcessedAt(), job.getErrorSummary(), job.getWarningCount(), job.getErrorCount(),
            job.getUpdatedAt(), job.getRunAfter(), job.getAttempts(), job.getMaxAttempts(), job.getLastError(), job.getStorageRef(), job.getOriginalFilename());
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

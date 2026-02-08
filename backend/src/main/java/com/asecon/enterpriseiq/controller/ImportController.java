package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.ImportDto;
import com.asecon.enterpriseiq.model.ImportJob;
import com.asecon.enterpriseiq.model.Role;
import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.ImportService;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/companies/{companyId}/imports")
public class ImportController {
    private final ImportService importService;
    private final AccessService accessService;

    public ImportController(ImportService importService, AccessService accessService) {
        this.importService = importService;
        this.accessService = accessService;
    }

    @GetMapping
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
        ImportJob job = importService.createImport(companyId, period, file);
        return toDto(job);
    }

    private ImportDto toDto(ImportJob job) {
        return new ImportDto(job.getId(), job.getCompany().getId(), job.getPeriod(), job.getStatus(),
            job.getCreatedAt(), job.getProcessedAt(), job.getErrorSummary(), job.getWarningCount(), job.getErrorCount());
    }
}

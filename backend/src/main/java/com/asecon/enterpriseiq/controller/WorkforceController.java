package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.WorkforceImportDto;
import com.asecon.enterpriseiq.dto.WorkforceLaborCostComparisonDto;
import com.asecon.enterpriseiq.dto.WorkforceSummaryDto;
import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.UploadLimitService;
import com.asecon.enterpriseiq.service.WorkforceImportService;
import com.asecon.enterpriseiq.service.WorkforceReportService;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/companies/{companyId}/workforce")
public class WorkforceController {
    private final WorkforceImportService workforceImportService;
    private final AccessService accessService;
    private final UploadLimitService uploadLimitService;
    private final WorkforceReportService workforceReportService;

    public WorkforceController(WorkforceImportService workforceImportService,
                               AccessService accessService,
                               UploadLimitService uploadLimitService,
                               WorkforceReportService workforceReportService) {
        this.workforceImportService = workforceImportService;
        this.accessService = accessService;
        this.uploadLimitService = uploadLimitService;
        this.workforceReportService = workforceReportService;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public WorkforceSummaryDto summary(@PathVariable Long companyId,
                                       @RequestParam(value = "workforceImportId", required = false) Long workforceImportId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.BRONZE);
        return workforceImportService.getSummary(companyId, workforceImportId);
    }

    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public WorkforceImportDto status(@PathVariable Long companyId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.BRONZE);
        return workforceImportService.getLatestImport(companyId);
    }

    @GetMapping("/costs/status")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public WorkforceImportDto laborCostsStatus(@PathVariable Long companyId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.BRONZE);
        return workforceImportService.getLatestLaborCostsImport(companyId);
    }

    @GetMapping("/costs/comparison")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public WorkforceLaborCostComparisonDto laborCostsComparison(@PathVariable Long companyId,
                                                                @RequestParam("basePeriod") String basePeriod,
                                                                @RequestParam("comparisonPeriod") String comparisonPeriod,
                                                                @RequestParam(value = "baseImportId", required = false) Long baseImportId,
                                                                @RequestParam(value = "comparisonImportId", required = false) Long comparisonImportId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.BRONZE);
        return workforceImportService.getLaborCostComparison(companyId, basePeriod, comparisonPeriod, baseImportId, comparisonImportId);
    }

    @GetMapping(value = "/report.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public ResponseEntity<byte[]> reportPdf(@PathVariable Long companyId,
                                            @RequestParam(value = "workforceImportId", required = false) Long workforceImportId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.BRONZE);

        WorkforceSummaryDto summary = workforceImportService.getSummary(companyId, workforceImportId);
        WorkforceImportDto workforceImport = summary == null ? null : summary.workforceImport();
        if (workforceImport == null || summary == null || summary.gestores() == null || summary.gestores().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Importa primero el fichero de trabajadores.");
        }

        var company = accessService.requireCompany(companyId);
        WorkforceImportDto laborCostsImport = summary.laborCostsImport();
        byte[] pdf = workforceReportService.renderWorkforcePdf(company, summary, workforceImport, laborCostsImport);
        String filename = ("workforce-report-" + companyId + ".pdf").replaceAll("[^a-zA-Z0-9._-]", "_");

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.APPLICATION_PDF)
            .contentLength(pdf.length)
            .body(pdf);
    }

    @PostMapping("/imports")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public WorkforceImportDto upload(@PathVariable Long companyId,
                                     @RequestPart("file") @NotNull MultipartFile file,
                                     @RequestPart(value = "referencePeriod", required = false) String referencePeriod) throws IOException {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.BRONZE);
        var company = accessService.requireCompany(companyId);
        uploadLimitService.requireAllowed(file, company.getPlan());
        return workforceImportService.importFile(companyId, file, referencePeriod);
    }

    @PostMapping("/costs/imports")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public WorkforceImportDto uploadLaborCosts(@PathVariable Long companyId,
                                               @RequestPart("file") @NotNull MultipartFile file,
                                               @RequestPart(value = "referencePeriod", required = false) String referencePeriod,
                                               @RequestPart(value = "referenceYear", required = false) Integer referenceYear) throws IOException {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.BRONZE);
        var company = accessService.requireCompany(companyId);
        uploadLimitService.requireAllowed(file, company.getPlan());
        String effectiveReferencePeriod = referencePeriod;
        if ((effectiveReferencePeriod == null || effectiveReferencePeriod.isBlank()) && referenceYear != null) {
            effectiveReferencePeriod = String.valueOf(referenceYear);
        }
        return workforceImportService.importLaborCosts(companyId, file, effectiveReferencePeriod);
    }
}

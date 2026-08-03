package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.BudgetSummaryDto;
import com.asecon.enterpriseiq.dto.BudgetAnalysisBundleDto;
import com.asecon.enterpriseiq.dto.BudgetLongInsightsDto;
import com.asecon.enterpriseiq.dto.BudgetLongPreviewDto;
import com.asecon.enterpriseiq.dto.BudgetItemDetailDto;
import com.asecon.enterpriseiq.dto.BudgetWorkflowDto;
import com.asecon.enterpriseiq.dto.CashflowSummaryDto;
import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.BudgetService;
import com.asecon.enterpriseiq.service.BudgetReportService;
import com.asecon.enterpriseiq.service.BudgetWorkflowService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies/{companyId}/budget")
@PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
public class BudgetController {
    private final BudgetService budgetService;
    private final AccessService accessService;
    private final CompanyRepository companyRepository;
    private final BudgetReportService budgetReportService;
    private final BudgetWorkflowService budgetWorkflowService;

    public BudgetController(BudgetService budgetService,
                            AccessService accessService,
                            CompanyRepository companyRepository,
                            BudgetReportService budgetReportService,
                            BudgetWorkflowService budgetWorkflowService) {
        this.budgetService = budgetService;
        this.accessService = accessService;
        this.companyRepository = companyRepository;
        this.budgetReportService = budgetReportService;
        this.budgetWorkflowService = budgetWorkflowService;
    }

    @GetMapping("/workflow")
    public BudgetWorkflowDto workflow(@PathVariable Long companyId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.GOLD);
        return budgetWorkflowService.getWorkflow(companyId);
    }

    @GetMapping("/analysis")
    public BudgetAnalysisBundleDto analysis(@PathVariable Long companyId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.GOLD);
        return budgetWorkflowService.getAnalysisBundle(companyId);
    }

    @GetMapping("/summary")
    public BudgetSummaryDto summary(@PathVariable Long companyId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.GOLD);
        return budgetService.latestBudget(companyId);
    }

    @GetMapping("/cashflow")
    public CashflowSummaryDto cashflow(@PathVariable Long companyId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.GOLD);
        return budgetService.latestCashflow(companyId);
    }

    @GetMapping("/long/preview")
    public BudgetLongPreviewDto longPreview(@PathVariable Long companyId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.GOLD);
        return budgetService.latestBudgetLongPreview(companyId);
    }

    @GetMapping(value = "/long.csv", produces = "text/csv")
    public ResponseEntity<byte[]> longCsv(@PathVariable Long companyId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.GOLD);
        byte[] csv = budgetService.latestBudgetLongCsv(companyId);
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "csv"))
            .header("Content-Disposition", "attachment; filename=budget-long.csv")
            .body(csv);
    }

    @GetMapping("/long/insights")
    public BudgetLongInsightsDto longInsights(@PathVariable Long companyId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.GOLD);
        return budgetService.latestBudgetLongInsights(companyId);
    }

    @GetMapping("/long/detail")
    public BudgetItemDetailDto longDetail(@PathVariable Long companyId, @RequestParam String canonicalRowId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.GOLD);
        return budgetService.latestBudgetItemDetail(companyId, canonicalRowId);
    }

    @GetMapping(value = "/report.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> budgetReportPdf(@PathVariable Long companyId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.GOLD);

        var company = companyRepository.findById(companyId).orElseThrow();
        var bundle = budgetService.latestBudgetPdfBundle(companyId);

        byte[] pdf = budgetReportService.renderBudgetPdf(company, bundle);
        String filename = ("budget-report-" + companyId + ".pdf").replaceAll("[^a-zA-Z0-9._-]", "_");

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.APPLICATION_PDF)
            .contentLength(pdf.length)
            .body(pdf);
    }
}

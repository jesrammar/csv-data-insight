package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.BudgetSummaryDto;
import com.asecon.enterpriseiq.dto.BudgetLongInsightsDto;
import com.asecon.enterpriseiq.dto.BudgetLongPreviewDto;
import com.asecon.enterpriseiq.dto.CashflowSummaryDto;
import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.BudgetService;
import com.asecon.enterpriseiq.service.BudgetReportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies/{companyId}/budget")
public class BudgetController {
    private final BudgetService budgetService;
    private final AccessService accessService;
    private final CompanyRepository companyRepository;
    private final BudgetReportService budgetReportService;

    public BudgetController(BudgetService budgetService,
                            AccessService accessService,
                            CompanyRepository companyRepository,
                            BudgetReportService budgetReportService) {
        this.budgetService = budgetService;
        this.accessService = accessService;
        this.companyRepository = companyRepository;
        this.budgetReportService = budgetReportService;
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

    @GetMapping(value = "/report.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> budgetReportPdf(@PathVariable Long companyId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.GOLD);

        var company = companyRepository.findById(companyId).orElseThrow();
        var summary = budgetService.latestBudget(companyId);
        var longInsights = budgetService.latestBudgetLongInsights(companyId);

        byte[] pdf = budgetReportService.renderBudgetPdf(company, summary, longInsights);
        String filename = ("budget-report-" + companyId + ".pdf").replaceAll("[^a-zA-Z0-9._-]", "_");

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.APPLICATION_PDF)
            .contentLength(pdf.length)
            .body(pdf);
    }
}

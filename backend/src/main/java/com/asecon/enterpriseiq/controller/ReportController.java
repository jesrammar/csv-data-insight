package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.ReportDto;
import com.asecon.enterpriseiq.dto.ReportRequest;
import com.asecon.enterpriseiq.model.Report;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.asecon.enterpriseiq.repo.ReportRepository;
import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.ReportService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies/{companyId}/reports")
public class ReportController {
    private final ReportRepository reportRepository;
    private final CompanyRepository companyRepository;
    private final ReportService reportService;
    private final AccessService accessService;

    public ReportController(ReportRepository reportRepository, CompanyRepository companyRepository,
                            ReportService reportService, AccessService accessService) {
        this.reportRepository = reportRepository;
        this.companyRepository = companyRepository;
        this.reportService = reportService;
        this.accessService = accessService;
    }

    @GetMapping
    public List<ReportDto> list(@PathVariable Long companyId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        return reportRepository.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
            .map(this::toDto).collect(Collectors.toList());
    }

    @PostMapping
    public ReportDto generate(@PathVariable Long companyId, @Valid @RequestBody ReportRequest request) throws IOException {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        var company = companyRepository.findById(companyId).orElseThrow();
        String summary = "Reporte base generado desde EnterpriseIQ";
        String html = reportService.buildHtmlTemplate(company, request.getPeriod(), summary);
        Report report = reportService.generateHtmlReport(company, request.getPeriod(), html);
        return toDto(report);
    }

    @GetMapping("/{reportId}/content")
    public String content(@PathVariable Long companyId, @PathVariable Long reportId) throws IOException {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        Report report = reportRepository.findById(reportId).orElseThrow();
        if (!report.getCompany().getId().equals(companyId)) {
            throw new IllegalArgumentException("Report not found for company");
        }
        return reportService.loadReportContent(report);
    }

    private ReportDto toDto(Report report) {
        return new ReportDto(report.getId(), report.getCompany().getId(), report.getPeriod(), report.getFormat(),
            report.getStatus(), report.getCreatedAt());
    }
}

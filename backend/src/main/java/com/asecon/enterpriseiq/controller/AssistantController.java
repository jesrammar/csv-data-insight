package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.AssistantChatRequestDto;
import com.asecon.enterpriseiq.dto.AssistantChatResponseDto;
import com.asecon.enterpriseiq.dto.ReportDto;
import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.model.Report;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.AdvisorAssistantService;
import com.asecon.enterpriseiq.service.ReportService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.YearMonth;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies/{companyId}/assistant")
public class AssistantController {
    private final AdvisorAssistantService advisorAssistantService;
    private final AccessService accessService;
    private final CompanyRepository companyRepository;
    private final ReportService reportService;

    public AssistantController(AdvisorAssistantService advisorAssistantService,
                               AccessService accessService,
                               CompanyRepository companyRepository,
                               ReportService reportService) {
        this.advisorAssistantService = advisorAssistantService;
        this.accessService = accessService;
        this.companyRepository = companyRepository;
        this.reportService = reportService;
    }

    @PostMapping("/chat")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public AssistantChatResponseDto chat(@PathVariable Long companyId, @Valid @RequestBody AssistantChatRequestDto req) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.PLATINUM);
        return advisorAssistantService.chat(companyId, req);
    }

    @PostMapping("/report")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public ReportDto generateConsultingReport(@PathVariable Long companyId) throws IOException {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.PLATINUM);

        var company = companyRepository.findById(companyId).orElseThrow();
        String period = YearMonth.now().toString();
        String html = advisorAssistantService.buildConsultingReportHtml(companyId, company.getName());
        Report report = reportService.generateHtmlReport(company, period, html);
        return new ReportDto(report.getId(), report.getCompany().getId(), report.getPeriod(), report.getFormat(), report.getStatus(), report.getCreatedAt());
    }
}

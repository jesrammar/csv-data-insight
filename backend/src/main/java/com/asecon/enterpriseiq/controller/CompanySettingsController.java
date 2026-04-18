package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.CompanySettingsDto;
import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.CompanySettingsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies/{companyId}/settings")
public class CompanySettingsController {
    private final AccessService accessService;
    private final CompanySettingsService settingsService;

    public CompanySettingsController(AccessService accessService, CompanySettingsService settingsService) {
        this.accessService = accessService;
        this.settingsService = settingsService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR','CLIENTE')")
    public CompanySettingsDto get(@PathVariable Long companyId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        var s = settingsService.getOrCreate(companyId);
        return new CompanySettingsDto(
            companyId,
            s.getWorkingPeriod(),
            s.isAutoMonthlyReport(),
            s.getReportConsultancyName(),
            s.getReportLogoUrl(),
            s.getReportPrimaryColor(),
            s.getReportFooterText()
        );
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public CompanySettingsDto update(@PathVariable Long companyId, @RequestBody CompanySettingsDto body) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        var updated = settingsService.update(
            companyId,
            body == null ? null : body.getWorkingPeriod(),
            body == null ? null : body.getAutoMonthlyReport(),
            body == null ? null : body.getReportConsultancyName(),
            body == null ? null : body.getReportLogoUrl(),
            body == null ? null : body.getReportPrimaryColor(),
            body == null ? null : body.getReportFooterText()
        );
        return new CompanySettingsDto(
            companyId,
            updated.getWorkingPeriod(),
            updated.isAutoMonthlyReport(),
            updated.getReportConsultancyName(),
            updated.getReportLogoUrl(),
            updated.getReportPrimaryColor(),
            updated.getReportFooterText()
        );
    }
}

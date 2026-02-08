package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.DashboardResponse;
import com.asecon.enterpriseiq.dto.KpiDto;
import com.asecon.enterpriseiq.repo.KpiMonthlyRepository;
import com.asecon.enterpriseiq.service.AccessService;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies/{companyId}/dashboard")
public class DashboardController {
    private final KpiMonthlyRepository kpiRepository;
    private final AccessService accessService;

    public DashboardController(KpiMonthlyRepository kpiRepository, AccessService accessService) {
        this.kpiRepository = kpiRepository;
        this.accessService = accessService;
    }

    @GetMapping
    public DashboardResponse dashboard(@PathVariable Long companyId,
                                       @RequestParam String from,
                                       @RequestParam String to) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        var kpis = kpiRepository.findByCompanyIdAndPeriodBetweenOrderByPeriodAsc(companyId, from, to)
            .stream()
            .map(k -> new KpiDto(k.getPeriod(), k.getInflows(), k.getOutflows(), k.getNetFlow(), k.getEndingBalance()))
            .collect(Collectors.toList());
        return new DashboardResponse(companyId, kpis);
    }
}

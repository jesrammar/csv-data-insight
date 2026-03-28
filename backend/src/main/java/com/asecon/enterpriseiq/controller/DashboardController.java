package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.DashboardInsightDto;
import com.asecon.enterpriseiq.dto.DashboardMetricDto;
import com.asecon.enterpriseiq.dto.DashboardResponse;
import com.asecon.enterpriseiq.dto.KpiDto;
import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.repo.KpiMonthlyRepository;
import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.DashboardMetricsService;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies/{companyId}/dashboard")
public class DashboardController {
    private final KpiMonthlyRepository kpiRepository;
    private final AccessService accessService;
    private final DashboardMetricsService metricsService;

    public DashboardController(KpiMonthlyRepository kpiRepository,
                               AccessService accessService,
                               DashboardMetricsService metricsService) {
        this.kpiRepository = kpiRepository;
        this.accessService = accessService;
        this.metricsService = metricsService;
    }

    @GetMapping
    public DashboardResponse dashboard(@PathVariable Long companyId,
                                       @RequestParam String from,
                                       @RequestParam String to) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        var company = accessService.requireCompany(companyId);
        Plan plan = company.getPlan();
        var kpiEntities = kpiRepository.findByCompanyIdAndPeriodBetweenOrderByPeriodAsc(companyId, from, to);
        var kpis = kpiEntities.stream()
            .map(k -> new KpiDto(k.getPeriod(), k.getInflows(), k.getOutflows(), k.getNetFlow(), k.getEndingBalance()))
            .collect(Collectors.toList());

        var computed = metricsService.compute(kpiEntities);
        List<DashboardMetricDto> metrics = filterMetrics(plan, computed.metrics());
        List<DashboardInsightDto> insights = filterInsights(plan, computed.insights());

        return new DashboardResponse(companyId, plan.name(), kpis, metrics, insights);
    }

    private List<DashboardMetricDto> filterMetrics(Plan plan, List<DashboardMetricDto> metrics) {
        if (metrics == null) return List.of();
        return metrics.stream()
            .filter(m -> tierAllowed(plan, m.getTier()))
            .collect(Collectors.toList());
    }

    private List<DashboardInsightDto> filterInsights(Plan plan, List<DashboardInsightDto> insights) {
        if (insights == null) return List.of();
        return insights.stream()
            .filter(i -> tierAllowed(plan, i.getTier()))
            .collect(Collectors.toList());
    }

    private boolean tierAllowed(Plan plan, String tier) {
        if (tier == null || tier.isBlank()) return true;
        try {
            return plan.isAtLeast(Plan.valueOf(tier));
        } catch (IllegalArgumentException ex) {
            return true;
        }
    }
}

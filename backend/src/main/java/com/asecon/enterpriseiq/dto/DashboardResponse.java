package com.asecon.enterpriseiq.dto;

import java.util.List;

public class DashboardResponse {
    private Long companyId;
    private String plan;
    private List<KpiDto> kpis;
    private List<DashboardMetricDto> metrics;
    private List<DashboardInsightDto> insights;

    public DashboardResponse(Long companyId,
                             String plan,
                             List<KpiDto> kpis,
                             List<DashboardMetricDto> metrics,
                             List<DashboardInsightDto> insights) {
        this.companyId = companyId;
        this.plan = plan;
        this.kpis = kpis;
        this.metrics = metrics;
        this.insights = insights;
    }

    public Long getCompanyId() { return companyId; }
    public String getPlan() { return plan; }
    public List<KpiDto> getKpis() { return kpis; }
    public List<DashboardMetricDto> getMetrics() { return metrics; }
    public List<DashboardInsightDto> getInsights() { return insights; }
}

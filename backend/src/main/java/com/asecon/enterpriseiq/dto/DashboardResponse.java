package com.asecon.enterpriseiq.dto;

import java.util.List;

public class DashboardResponse {
    private Long companyId;
    private List<KpiDto> kpis;

    public DashboardResponse(Long companyId, List<KpiDto> kpis) {
        this.companyId = companyId;
        this.kpis = kpis;
    }

    public Long getCompanyId() { return companyId; }
    public List<KpiDto> getKpis() { return kpis; }
}

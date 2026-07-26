package com.asecon.enterpriseiq.dto;

import jakarta.validation.constraints.NotBlank;

public class ReportRequest {
    @NotBlank
    private String period;

    private Long universalViewId;

    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public Long getUniversalViewId() { return universalViewId; }
    public void setUniversalViewId(Long universalViewId) { this.universalViewId = universalViewId; }
}

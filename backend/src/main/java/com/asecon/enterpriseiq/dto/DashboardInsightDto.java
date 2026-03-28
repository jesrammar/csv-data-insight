package com.asecon.enterpriseiq.dto;

public class DashboardInsightDto {
    private String title;
    private String detail;
    private String severity;
    private String tier;

    public DashboardInsightDto() {}

    public DashboardInsightDto(String title, String detail, String severity, String tier) {
        this.title = title;
        this.detail = detail;
        this.severity = severity;
        this.tier = tier;
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }
}

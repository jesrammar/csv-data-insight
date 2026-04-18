package com.asecon.enterpriseiq.dto;

public class CompanySettingsDto {
    private Long companyId;
    private String workingPeriod;
    private Boolean autoMonthlyReport;
    private String reportConsultancyName;
    private String reportLogoUrl;
    private String reportPrimaryColor;
    private String reportFooterText;

    public CompanySettingsDto() {}

    public CompanySettingsDto(Long companyId, String workingPeriod, Boolean autoMonthlyReport,
                              String reportConsultancyName,
                              String reportLogoUrl,
                              String reportPrimaryColor,
                              String reportFooterText) {
        this.companyId = companyId;
        this.workingPeriod = workingPeriod;
        this.autoMonthlyReport = autoMonthlyReport;
        this.reportConsultancyName = reportConsultancyName;
        this.reportLogoUrl = reportLogoUrl;
        this.reportPrimaryColor = reportPrimaryColor;
        this.reportFooterText = reportFooterText;
    }

    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }
    public String getWorkingPeriod() { return workingPeriod; }
    public void setWorkingPeriod(String workingPeriod) { this.workingPeriod = workingPeriod; }
    public Boolean getAutoMonthlyReport() { return autoMonthlyReport; }
    public void setAutoMonthlyReport(Boolean autoMonthlyReport) { this.autoMonthlyReport = autoMonthlyReport; }
    public String getReportConsultancyName() { return reportConsultancyName; }
    public void setReportConsultancyName(String reportConsultancyName) { this.reportConsultancyName = reportConsultancyName; }
    public String getReportLogoUrl() { return reportLogoUrl; }
    public void setReportLogoUrl(String reportLogoUrl) { this.reportLogoUrl = reportLogoUrl; }
    public String getReportPrimaryColor() { return reportPrimaryColor; }
    public void setReportPrimaryColor(String reportPrimaryColor) { this.reportPrimaryColor = reportPrimaryColor; }
    public String getReportFooterText() { return reportFooterText; }
    public void setReportFooterText(String reportFooterText) { this.reportFooterText = reportFooterText; }
}

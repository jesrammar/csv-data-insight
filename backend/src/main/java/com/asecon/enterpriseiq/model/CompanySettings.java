package com.asecon.enterpriseiq.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "company_settings")
public class CompanySettings {
    @Id
    @Column(name = "company_id")
    private Long companyId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "working_period", length = 7)
    private String workingPeriod;

    @Column(name = "auto_monthly_report", nullable = false)
    private boolean autoMonthlyReport = true;

    @Column(name = "report_consultancy_name", length = 140)
    private String reportConsultancyName;

    @Column(name = "report_logo_url", length = 500)
    private String reportLogoUrl;

    @Column(name = "report_primary_color", length = 16)
    private String reportPrimaryColor;

    @Column(name = "report_footer_text", length = 400)
    private String reportFooterText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getCompanyId() { return companyId; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public String getWorkingPeriod() { return workingPeriod; }
    public void setWorkingPeriod(String workingPeriod) { this.workingPeriod = workingPeriod; }
    public boolean isAutoMonthlyReport() { return autoMonthlyReport; }
    public void setAutoMonthlyReport(boolean autoMonthlyReport) { this.autoMonthlyReport = autoMonthlyReport; }
    public String getReportConsultancyName() { return reportConsultancyName; }
    public void setReportConsultancyName(String reportConsultancyName) { this.reportConsultancyName = reportConsultancyName; }
    public String getReportLogoUrl() { return reportLogoUrl; }
    public void setReportLogoUrl(String reportLogoUrl) { this.reportLogoUrl = reportLogoUrl; }
    public String getReportPrimaryColor() { return reportPrimaryColor; }
    public void setReportPrimaryColor(String reportPrimaryColor) { this.reportPrimaryColor = reportPrimaryColor; }
    public String getReportFooterText() { return reportFooterText; }
    public void setReportFooterText(String reportFooterText) { this.reportFooterText = reportFooterText; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

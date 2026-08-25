package com.asecon.enterpriseiq.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "workforce_imports")
public class WorkforceImport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(nullable = false)
    private String filename;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "row_count", nullable = false)
    private Integer rowCount;

    @Column(name = "warning_count", nullable = false)
    private Integer warningCount;

    @Column(name = "error_count", nullable = false)
    private Integer errorCount;

    @Column(name = "error_summary")
    private String errorSummary;

    @Column(name = "summary_json", nullable = false, columnDefinition = "TEXT")
    private String summaryJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "import_kind", nullable = false)
    private WorkforceImportKind importKind = WorkforceImportKind.WORKFORCE;

    @Column(name = "reference_period", nullable = false)
    private String referencePeriod = "UNKNOWN";

    @Column(name = "reference_year")
    private Integer referenceYear;

    @Column(name = "reference_month")
    private Integer referenceMonth;

    @Column(name = "coverage_start_month")
    private Integer coverageStartMonth;

    @Column(name = "coverage_end_month")
    private Integer coverageEndMonth;

    @Column(name = "coverage_complete_year", nullable = false)
    private Boolean coverageCompleteYear = Boolean.FALSE;

    @Enumerated(EnumType.STRING)
    @Column(name = "import_status", nullable = false)
    private WorkforceImportStatus importStatus = WorkforceImportStatus.ACTIVE;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Integer getRowCount() { return rowCount; }
    public void setRowCount(Integer rowCount) { this.rowCount = rowCount; }
    public Integer getWarningCount() { return warningCount; }
    public void setWarningCount(Integer warningCount) { this.warningCount = warningCount; }
    public Integer getErrorCount() { return errorCount; }
    public void setErrorCount(Integer errorCount) { this.errorCount = errorCount; }
    public String getErrorSummary() { return errorSummary; }
    public void setErrorSummary(String errorSummary) { this.errorSummary = errorSummary; }
    public String getSummaryJson() { return summaryJson; }
    public void setSummaryJson(String summaryJson) { this.summaryJson = summaryJson; }
    public WorkforceImportKind getImportKind() { return importKind; }
    public void setImportKind(WorkforceImportKind importKind) { this.importKind = importKind; }
    public String getReferencePeriod() { return referencePeriod; }
    public void setReferencePeriod(String referencePeriod) { this.referencePeriod = referencePeriod; }
    public Integer getReferenceYear() { return referenceYear; }
    public void setReferenceYear(Integer referenceYear) { this.referenceYear = referenceYear; }
    public Integer getReferenceMonth() { return referenceMonth; }
    public void setReferenceMonth(Integer referenceMonth) { this.referenceMonth = referenceMonth; }
    public Integer getCoverageStartMonth() { return coverageStartMonth; }
    public void setCoverageStartMonth(Integer coverageStartMonth) { this.coverageStartMonth = coverageStartMonth; }
    public Integer getCoverageEndMonth() { return coverageEndMonth; }
    public void setCoverageEndMonth(Integer coverageEndMonth) { this.coverageEndMonth = coverageEndMonth; }
    public Boolean getCoverageCompleteYear() { return coverageCompleteYear; }
    public void setCoverageCompleteYear(Boolean coverageCompleteYear) { this.coverageCompleteYear = coverageCompleteYear; }
    public WorkforceImportStatus getImportStatus() { return importStatus; }
    public void setImportStatus(WorkforceImportStatus importStatus) { this.importStatus = importStatus; }
}

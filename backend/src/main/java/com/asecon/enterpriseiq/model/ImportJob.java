package com.asecon.enterpriseiq.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "imports")
public class ImportJob {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(nullable = false)
    private String period;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ImportStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "error_summary")
    private String errorSummary;

    @Column(name = "warning_count")
    private Integer warningCount;

    @Column(name = "error_count")
    private Integer errorCount;

    public Long getId() { return id; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public ImportStatus getStatus() { return status; }
    public void setStatus(ImportStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
    public String getErrorSummary() { return errorSummary; }
    public void setErrorSummary(String errorSummary) { this.errorSummary = errorSummary; }
    public Integer getWarningCount() { return warningCount; }
    public void setWarningCount(Integer warningCount) { this.warningCount = warningCount; }
    public Integer getErrorCount() { return errorCount; }
    public void setErrorCount(Integer errorCount) { this.errorCount = errorCount; }
}

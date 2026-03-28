package com.asecon.enterpriseiq.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "tribunal_imports")
public class TribunalImport {
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

    public Long getId() { return id; }
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
}

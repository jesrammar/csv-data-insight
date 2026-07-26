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

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "run_after")
    private Instant runAfter;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "error_summary")
    private String errorSummary;

    @Column(name = "warning_count")
    private Integer warningCount;

    @Column(name = "error_count")
    private Integer errorCount;

    @Column(nullable = false)
    private Integer attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    private Integer maxAttempts = 3;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "storage_ref")
    private String storageRef;

    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "normalized_hash", length = 64)
    private String normalizedHash;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo = 1;

    @Column(name = "supersedes_import_id")
    private Long supersedesImportId;

    @Column(name = "duplicate_of_import_id")
    private Long duplicateOfImportId;

    @Column(name = "blocking_code")
    private String blockingCode;

    @Column(name = "blocking_reason")
    private String blockingReason;

    @Column(name = "rows_received")
    private Integer rowsReceived;

    @Column(name = "rows_valid")
    private Integer rowsValid;

    @Column(name = "applied_at")
    private Instant appliedAt;

    public Long getId() { return id; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public ImportStatus getStatus() { return status; }
    public void setStatus(ImportStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getRunAfter() { return runAfter; }
    public void setRunAfter(Instant runAfter) { this.runAfter = runAfter; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
    public String getErrorSummary() { return errorSummary; }
    public void setErrorSummary(String errorSummary) { this.errorSummary = errorSummary; }
    public Integer getWarningCount() { return warningCount; }
    public void setWarningCount(Integer warningCount) { this.warningCount = warningCount; }
    public Integer getErrorCount() { return errorCount; }
    public void setErrorCount(Integer errorCount) { this.errorCount = errorCount; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }
    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public String getStorageRef() { return storageRef; }
    public void setStorageRef(String storageRef) { this.storageRef = storageRef; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getNormalizedHash() { return normalizedHash; }
    public void setNormalizedHash(String normalizedHash) { this.normalizedHash = normalizedHash; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public Long getSupersedesImportId() { return supersedesImportId; }
    public void setSupersedesImportId(Long supersedesImportId) { this.supersedesImportId = supersedesImportId; }
    public Long getDuplicateOfImportId() { return duplicateOfImportId; }
    public void setDuplicateOfImportId(Long duplicateOfImportId) { this.duplicateOfImportId = duplicateOfImportId; }
    public String getBlockingCode() { return blockingCode; }
    public void setBlockingCode(String blockingCode) { this.blockingCode = blockingCode; }
    public String getBlockingReason() { return blockingReason; }
    public void setBlockingReason(String blockingReason) { this.blockingReason = blockingReason; }
    public Integer getRowsReceived() { return rowsReceived; }
    public void setRowsReceived(Integer rowsReceived) { this.rowsReceived = rowsReceived; }
    public Integer getRowsValid() { return rowsValid; }
    public void setRowsValid(Integer rowsValid) { this.rowsValid = rowsValid; }
    public Instant getAppliedAt() { return appliedAt; }
    public void setAppliedAt(Instant appliedAt) { this.appliedAt = appliedAt; }
}

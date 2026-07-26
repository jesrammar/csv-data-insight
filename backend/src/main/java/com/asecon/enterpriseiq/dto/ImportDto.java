package com.asecon.enterpriseiq.dto;

import com.asecon.enterpriseiq.model.ImportStatus;
import java.time.Instant;

public class ImportDto {
    private Long id;
    private Long companyId;
    private String period;
    private ImportStatus status;
    private Instant createdAt;
    private Instant processedAt;
    private String errorSummary;
    private Integer warningCount;
    private Integer errorCount;
    private Instant updatedAt;
    private Instant runAfter;
    private Integer attempts;
    private Integer maxAttempts;
    private String lastError;
    private String storageRef;
    private String originalFilename;
    private Integer versionNo;
    private String contentHash;
    private String normalizedHash;
    private Long supersedesImportId;
    private Long duplicateOfImportId;
    private String blockingCode;
    private String blockingReason;
    private Integer rowsReceived;
    private Integer rowsValid;
    private Instant appliedAt;

    public ImportDto() {}

    public ImportDto(Long id, Long companyId, String period, ImportStatus status, Instant createdAt, Instant processedAt,
                     String errorSummary, Integer warningCount, Integer errorCount,
                     Instant updatedAt, Instant runAfter, Integer attempts, Integer maxAttempts, String lastError, String storageRef, String originalFilename,
                     Integer versionNo, String contentHash, String normalizedHash, Long supersedesImportId, Long duplicateOfImportId,
                     String blockingCode, String blockingReason, Integer rowsReceived, Integer rowsValid, Instant appliedAt) {
        this.id = id;
        this.companyId = companyId;
        this.period = period;
        this.status = status;
        this.createdAt = createdAt;
        this.processedAt = processedAt;
        this.errorSummary = errorSummary;
        this.warningCount = warningCount;
        this.errorCount = errorCount;
        this.updatedAt = updatedAt;
        this.runAfter = runAfter;
        this.attempts = attempts;
        this.maxAttempts = maxAttempts;
        this.lastError = lastError;
        this.storageRef = storageRef;
        this.originalFilename = originalFilename;
        this.versionNo = versionNo;
        this.contentHash = contentHash;
        this.normalizedHash = normalizedHash;
        this.supersedesImportId = supersedesImportId;
        this.duplicateOfImportId = duplicateOfImportId;
        this.blockingCode = blockingCode;
        this.blockingReason = blockingReason;
        this.rowsReceived = rowsReceived;
        this.rowsValid = rowsValid;
        this.appliedAt = appliedAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }
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
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getRunAfter() { return runAfter; }
    public void setRunAfter(Instant runAfter) { this.runAfter = runAfter; }
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
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getNormalizedHash() { return normalizedHash; }
    public void setNormalizedHash(String normalizedHash) { this.normalizedHash = normalizedHash; }
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

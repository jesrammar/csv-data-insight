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

    public ImportDto() {}

    public ImportDto(Long id, Long companyId, String period, ImportStatus status, Instant createdAt, Instant processedAt,
                     String errorSummary, Integer warningCount, Integer errorCount,
                     Instant updatedAt, Instant runAfter, Integer attempts, Integer maxAttempts, String lastError, String storageRef, String originalFilename) {
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
}

package com.asecon.enterpriseiq.dto;

import com.asecon.enterpriseiq.model.IngestionFileKind;
import com.asecon.enterpriseiq.model.IngestionFileStatus;
import java.time.Instant;

public class PipelineFileDto {
    private Long id;
    private Long companyId;
    private String sourceType;
    private IngestionFileKind detectedKind;
    private String filename;
    private String inboxPath;
    private String archivedPath;
    private String period;
    private IngestionFileStatus status;
    private String statusTitle;
    private String statusDetail;
    private String badgeTone;
    private String actionLabel;
    private String message;
    private Long importJobId;
    private Instant detectedAt;
    private Instant processedAt;
    private Instant updatedAt;

    public PipelineFileDto(Long id,
                           Long companyId,
                           String sourceType,
                           IngestionFileKind detectedKind,
                           String filename,
                           String inboxPath,
                           String archivedPath,
                           String period,
                           IngestionFileStatus status,
                           String statusTitle,
                           String statusDetail,
                           String badgeTone,
                           String actionLabel,
                           String message,
                           Long importJobId,
                           Instant detectedAt,
                           Instant processedAt,
                           Instant updatedAt) {
        this.id = id;
        this.companyId = companyId;
        this.sourceType = sourceType;
        this.detectedKind = detectedKind;
        this.filename = filename;
        this.inboxPath = inboxPath;
        this.archivedPath = archivedPath;
        this.period = period;
        this.status = status;
        this.statusTitle = statusTitle;
        this.statusDetail = statusDetail;
        this.badgeTone = badgeTone;
        this.actionLabel = actionLabel;
        this.message = message;
        this.importJobId = importJobId;
        this.detectedAt = detectedAt;
        this.processedAt = processedAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }
    public Long getCompanyId() { return companyId; }
    public String getSourceType() { return sourceType; }
    public IngestionFileKind getDetectedKind() { return detectedKind; }
    public String getFilename() { return filename; }
    public String getInboxPath() { return inboxPath; }
    public String getArchivedPath() { return archivedPath; }
    public String getPeriod() { return period; }
    public IngestionFileStatus getStatus() { return status; }
    public String getStatusTitle() { return statusTitle; }
    public String getStatusDetail() { return statusDetail; }
    public String getBadgeTone() { return badgeTone; }
    public String getActionLabel() { return actionLabel; }
    public String getMessage() { return message; }
    public Long getImportJobId() { return importJobId; }
    public Instant getDetectedAt() { return detectedAt; }
    public Instant getProcessedAt() { return processedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

package com.asecon.enterpriseiq.dto;

import java.time.Instant;

public class PipelineSummaryDto {
    private Instant generatedAt;
    private long totalFiles;
    private long doneFiles;
    private long errorFiles;
    private long pendingFiles;
    private long processingFiles;
    private long skippedFiles;
    private Instant lastDetectedAt;
    private String headline;
    private String detail;
    private String badgeTone;
    private String actionLabel;

    public PipelineSummaryDto(Instant generatedAt,
                              long totalFiles,
                              long doneFiles,
                              long errorFiles,
                              long pendingFiles,
                              long processingFiles,
                              long skippedFiles,
                              Instant lastDetectedAt,
                              String headline,
                              String detail,
                              String badgeTone,
                              String actionLabel) {
        this.generatedAt = generatedAt;
        this.totalFiles = totalFiles;
        this.doneFiles = doneFiles;
        this.errorFiles = errorFiles;
        this.pendingFiles = pendingFiles;
        this.processingFiles = processingFiles;
        this.skippedFiles = skippedFiles;
        this.lastDetectedAt = lastDetectedAt;
        this.headline = headline;
        this.detail = detail;
        this.badgeTone = badgeTone;
        this.actionLabel = actionLabel;
    }

    public Instant getGeneratedAt() { return generatedAt; }
    public long getTotalFiles() { return totalFiles; }
    public long getDoneFiles() { return doneFiles; }
    public long getErrorFiles() { return errorFiles; }
    public long getPendingFiles() { return pendingFiles; }
    public long getProcessingFiles() { return processingFiles; }
    public long getSkippedFiles() { return skippedFiles; }
    public Instant getLastDetectedAt() { return lastDetectedAt; }
    public String getHeadline() { return headline; }
    public String getDetail() { return detail; }
    public String getBadgeTone() { return badgeTone; }
    public String getActionLabel() { return actionLabel; }
}

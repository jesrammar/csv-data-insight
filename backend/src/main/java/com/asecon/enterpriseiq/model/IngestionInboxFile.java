package com.asecon.enterpriseiq.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ingestion_inbox_files")
public class IngestionInboxFile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "detected_kind", nullable = false)
    private IngestionFileKind detectedKind;

    @Column(nullable = false)
    private String filename;

    @Column(name = "inbox_path", nullable = false)
    private String inboxPath;

    @Column(name = "archived_path")
    private String archivedPath;

    @Column
    private String period;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IngestionFileStatus status;

    @Column(columnDefinition = "TEXT")
    private String message;

    @ManyToOne
    @JoinColumn(name = "import_job_id")
    private ImportJob importJob;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public IngestionFileKind getDetectedKind() { return detectedKind; }
    public void setDetectedKind(IngestionFileKind detectedKind) { this.detectedKind = detectedKind; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getInboxPath() { return inboxPath; }
    public void setInboxPath(String inboxPath) { this.inboxPath = inboxPath; }
    public String getArchivedPath() { return archivedPath; }
    public void setArchivedPath(String archivedPath) { this.archivedPath = archivedPath; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public IngestionFileStatus getStatus() { return status; }
    public void setStatus(IngestionFileStatus status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public ImportJob getImportJob() { return importJob; }
    public void setImportJob(ImportJob importJob) { this.importJob = importJob; }
    public Instant getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Instant detectedAt) { this.detectedAt = detectedAt; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

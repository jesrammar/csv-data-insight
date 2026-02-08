package com.asecon.enterpriseiq.dto;

import com.asecon.enterpriseiq.model.ReportFormat;
import com.asecon.enterpriseiq.model.ReportStatus;
import java.time.Instant;

public class ReportDto {
    private Long id;
    private Long companyId;
    private String period;
    private ReportFormat format;
    private ReportStatus status;
    private Instant createdAt;

    public ReportDto(Long id, Long companyId, String period, ReportFormat format, ReportStatus status, Instant createdAt) {
        this.id = id;
        this.companyId = companyId;
        this.period = period;
        this.format = format;
        this.status = status;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getCompanyId() { return companyId; }
    public String getPeriod() { return period; }
    public ReportFormat getFormat() { return format; }
    public ReportStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}

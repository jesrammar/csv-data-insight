package com.asecon.enterpriseiq.dto;

import java.time.Instant;

public class AuditEventDto {
    private Long id;
    private Instant at;
    private Long userId;
    private Long companyId;
    private String action;
    private String method;
    private String path;
    private Integer status;
    private Long durationMs;
    private String resourceType;
    private String resourceId;
    private String metaJson;

    public AuditEventDto(Long id, Instant at, Long userId, Long companyId, String action, String method, String path,
                         Integer status, Long durationMs, String resourceType, String resourceId, String metaJson) {
        this.id = id;
        this.at = at;
        this.userId = userId;
        this.companyId = companyId;
        this.action = action;
        this.method = method;
        this.path = path;
        this.status = status;
        this.durationMs = durationMs;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.metaJson = metaJson;
    }

    public Long getId() { return id; }
    public Instant getAt() { return at; }
    public Long getUserId() { return userId; }
    public Long getCompanyId() { return companyId; }
    public String getAction() { return action; }
    public String getMethod() { return method; }
    public String getPath() { return path; }
    public Integer getStatus() { return status; }
    public Long getDurationMs() { return durationMs; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public String getMetaJson() { return metaJson; }
}


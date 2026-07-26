package com.asecon.enterpriseiq.dto;

import java.time.Instant;

public record AdvisorActionFollowUpDto(
    Long id,
    Long companyId,
    Long recommendationSnapshotId,
    String period,
    String source,
    Integer actionIndex,
    String actionKey,
    String horizon,
    String priority,
    String title,
    String detail,
    String kpi,
    String status,
    boolean carriedOver,
    Long originFollowUpId,
    String originPeriod,
    Instant createdAt,
    Instant updatedAt,
    Instant resolvedAt
) {}

package com.asecon.enterpriseiq.dto;

import java.time.Instant;

public record AutomationJobDto(
    Long id,
    Long companyId,
    String type,
    String status,
    int attempts,
    int maxAttempts,
    Instant runAfter,
    Instant createdAt,
    Instant updatedAt,
    String traceId,
    String lastError
) {}


package com.asecon.enterpriseiq.dto;

import java.time.Instant;

public record UniversalViewDto(
    Long id,
    Long companyId,
    String name,
    String type,
    Instant createdAt,
    Long sourceImportId,
    String sourceFilename,
    String sourceImportedAt
) {}

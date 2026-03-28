package com.asecon.enterpriseiq.dto;

import java.time.Instant;

public record TribunalImportDto(
    Long id,
    Long companyId,
    String filename,
    Instant createdAt,
    Integer rowCount,
    Integer warningCount,
    Integer errorCount,
    String errorSummary
) {}

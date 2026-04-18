package com.asecon.enterpriseiq.dto;

import java.time.Instant;

public record UniversalImportLineageDto(
    Long importId,
    String filename,
    Instant createdAt,
    Integer rowCount,
    Integer columnCount,
    UniversalImportAnalysisDto analysis
) {}

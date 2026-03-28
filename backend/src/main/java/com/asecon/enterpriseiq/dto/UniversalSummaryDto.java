package com.asecon.enterpriseiq.dto;

import java.time.Instant;
import java.util.List;

public record UniversalSummaryDto(
    Long importId,
    String filename,
    Instant createdAt,
    int rowCount,
    int columnCount,
    List<UniversalColumnDto> columns,
    List<UniversalCorrelationDto> correlations,
    List<UniversalInsightDto> insights
) {}

package com.asecon.enterpriseiq.dto;

import java.time.Instant;

public record BudgetSourceMetaDto(
    Long sourceImportId,
    String analysisVersion,
    String sourceFilename,
    Instant sourceCreatedAt,
    String sourceType,
    Integer sourceSheetIndex,
    String sourceSheetName,
    Integer sourceHeaderRow,
    String sourceHeaderLabel,
    Integer plannedMonthsAvailable
) {}

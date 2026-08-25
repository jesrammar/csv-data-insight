package com.asecon.enterpriseiq.dto;

import java.time.Instant;
import java.util.List;

public record WorkforceImportDto(
    Long id,
    Long companyId,
    String importKind,
    String filename,
    Instant createdAt,
    Integer rowCount,
    Integer warningCount,
    Integer errorCount,
    String errorSummary,
    String referencePeriod,
    String referenceLabel,
    String status,
    Integer referenceYear,
    Integer referenceMonth,
    Integer coverageStartMonth,
    Integer coverageEndMonth,
    Boolean annualCoverage,
    Integer versionNumber,
    List<String> detectedColumns,
    List<Integer> activityYears
) {}

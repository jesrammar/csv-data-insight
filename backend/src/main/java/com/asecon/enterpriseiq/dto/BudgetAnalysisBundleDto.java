package com.asecon.enterpriseiq.dto;

import java.time.Instant;

public record BudgetAnalysisBundleDto(
    Long companyId,
    Long planImportId,
    Long actualImportId,
    Long sourceImportId,
    String analysisVersion,
    Instant generatedAt,
    String comparisonStatus,
    String sourceFilename,
    String sourceType,
    Instant sourceCreatedAt,
    Integer sourceSheetIndex,
    String sourceSheetName,
    Integer sourceHeaderRow,
    String sourceHeaderLabel,
    BudgetWorkflowDto workflow,
    BudgetSummaryDto summary,
    CashflowSummaryDto cashflow,
    BudgetLongInsightsDto insights
) {}

package com.asecon.enterpriseiq.dto;

import java.time.Instant;
import java.util.List;

public record BudgetWorkflowDto(
    Long companyId,
    String status,
    String statusTitle,
    String statusDetail,
    String statusBadgeTone,
    String nextActionLabel,
    String sourceFilename,
    Instant sourceCreatedAt,
    Integer sourceSheetIndex,
    Integer sourceHeaderRow,
    String sourceAttemptTrend,
    String sourceAttemptTrendTitle,
    String sourceAttemptTrendDetail,
    boolean sourcePresent,
    boolean structureValidated,
    boolean annualInsightsReady,
    boolean plannedCashflowReady,
    boolean comparisonReady,
    Integer plannedMonthsAvailable,
    Integer actualMonthsAvailable,
    BudgetComparisonSummaryDto comparisonSummary,
    List<BudgetComparisonMonthDto> comparisonMonths
) {}

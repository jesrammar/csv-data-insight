package com.asecon.enterpriseiq.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BudgetSummaryDto(
    String sourceFilename,
    Instant sourceCreatedAt,
    Long sourceImportId,
    String analysisVersion,
    List<BudgetMonthDto> months,
    BigDecimal totalIncome,
    BigDecimal totalExpense,
    BigDecimal totalMargin,
    BigDecimal totalCapex,
    BigDecimal totalDepreciation,
    BigDecimal totalEbit,
    BigDecimal financialResult,
    BigDecimal netResult,
    String bestMonth,
    String worstMonth
) {}


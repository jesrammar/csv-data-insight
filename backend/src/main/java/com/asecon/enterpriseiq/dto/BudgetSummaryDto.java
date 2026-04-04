package com.asecon.enterpriseiq.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BudgetSummaryDto(
    String sourceFilename,
    Instant sourceCreatedAt,
    List<BudgetMonthDto> months,
    BigDecimal totalIncome,
    BigDecimal totalExpense,
    BigDecimal totalMargin,
    String bestMonth,
    String worstMonth
) {}


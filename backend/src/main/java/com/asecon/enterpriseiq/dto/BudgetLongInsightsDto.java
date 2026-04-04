package com.asecon.enterpriseiq.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BudgetLongInsightsDto(
    String filename,
    Instant createdAt,
    int itemCount,
    BigDecimal totalAbsAnnual,
    String bestMonth,
    String worstMonth,
    BigDecimal concentrationTop3AbsPct,
    List<BudgetMonthTotalDto> monthTotals,
    List<BudgetItemInsightDto> topDrivers,
    List<BudgetItemInsightDto> zeroHeavyItems
) {}


package com.asecon.enterpriseiq.dto;

import java.math.BigDecimal;

public record BudgetComparisonSummaryDto(
    Integer comparisonYear,
    Integer plannedMonths,
    Integer actualMonths,
    Integer commonMonths,
    String assumption,
    String latestComparedPeriod,
    BigDecimal plannedInflowYtd,
    BigDecimal actualInflowYtd,
    BigDecimal inflowVarianceYtd,
    BigDecimal plannedOutflowYtd,
    BigDecimal actualOutflowYtd,
    BigDecimal outflowVarianceYtd,
    BigDecimal plannedNetYtd,
    BigDecimal actualNetYtd,
    BigDecimal netVarianceYtd,
    BigDecimal plannedEndingBalanceLatest,
    BigDecimal actualEndingBalanceLatest,
    BigDecimal endingBalanceVarianceLatest,
    String strongestPositiveMonth,
    BigDecimal strongestPositiveVariance,
    String strongestNegativeMonth,
    BigDecimal strongestNegativeVariance
) {}

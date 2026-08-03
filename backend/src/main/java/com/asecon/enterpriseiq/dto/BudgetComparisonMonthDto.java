package com.asecon.enterpriseiq.dto;

import java.math.BigDecimal;

public record BudgetComparisonMonthDto(
    String monthKey,
    String monthLabel,
    String actualPeriod,
    boolean hasActual,
    String actualStatus,
    BigDecimal plannedInflow,
    BigDecimal actualInflow,
    BigDecimal inflowVariance,
    BigDecimal plannedOutflow,
    BigDecimal actualOutflow,
    BigDecimal outflowVariance,
    BigDecimal plannedNet,
    BigDecimal actualNet,
    BigDecimal netVariance,
    BigDecimal plannedEndingBalance,
    BigDecimal actualEndingBalance,
    BigDecimal endingBalanceVariance
) {}

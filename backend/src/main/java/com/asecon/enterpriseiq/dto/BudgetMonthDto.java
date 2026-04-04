package com.asecon.enterpriseiq.dto;

import java.math.BigDecimal;

public record BudgetMonthDto(
    String monthKey,
    String label,
    BigDecimal income,
    BigDecimal expense,
    BigDecimal margin,
    BigDecimal deltaMargin,
    BigDecimal deltaMarginPct
) {}


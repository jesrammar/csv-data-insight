package com.asecon.enterpriseiq.dto;

import java.math.BigDecimal;

public record BudgetMonthTotalDto(
    String monthKey,
    String monthLabel,
    BigDecimal total
) {}


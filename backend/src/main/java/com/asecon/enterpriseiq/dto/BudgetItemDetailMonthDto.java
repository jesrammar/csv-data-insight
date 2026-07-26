package com.asecon.enterpriseiq.dto;

import java.math.BigDecimal;

public record BudgetItemDetailMonthDto(
    String monthKey,
    String monthLabel,
    BigDecimal amount
) {}

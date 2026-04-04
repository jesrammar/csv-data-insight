package com.asecon.enterpriseiq.dto;

import java.math.BigDecimal;

public record BudgetItemInsightDto(
    String code,
    String label,
    BigDecimal annualTotal,
    int zeroMonths,
    BigDecimal shareAbsPct
) {}


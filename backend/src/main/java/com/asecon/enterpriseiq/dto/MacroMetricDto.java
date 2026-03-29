package com.asecon.enterpriseiq.dto;

import java.math.BigDecimal;

public record MacroMetricDto(
    String label,
    BigDecimal value,
    String unit,
    String source
) {}


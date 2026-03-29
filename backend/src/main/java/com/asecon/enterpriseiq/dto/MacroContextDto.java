package com.asecon.enterpriseiq.dto;

import java.time.Instant;

public record MacroContextDto(
    String period,
    Instant updatedAt,
    MacroMetricDto inflationYoyPct,
    MacroMetricDto euribor1yPct,
    MacroMetricDto usdPerEur
) {}


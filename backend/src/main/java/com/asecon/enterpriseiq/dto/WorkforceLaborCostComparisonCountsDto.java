package com.asecon.enterpriseiq.dto;

public record WorkforceLaborCostComparisonCountsDto(
    long baseWorkers,
    long comparisonWorkers,
    long comparableWorkers,
    long onlyBaseWorkers,
    long onlyComparisonWorkers,
    long reviewWorkers,
    long unmatchedWorkers,
    long validBaseWorkers,
    long validComparisonWorkers
) {}

package com.asecon.enterpriseiq.dto;

import java.util.List;

public record WorkforceInsightsDto(
    List<String> executiveReadings,
    List<String> priorityReview,
    List<String> costActivityReadings,
    List<String> findings,
    List<String> reviewPoints,
    List<String> recommendedActions,
    List<String> limitations
) {}

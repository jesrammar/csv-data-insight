package com.asecon.enterpriseiq.dto;

import java.util.List;

public record UniversalIntakeCandidateDto(
    String kind,
    String label,
    Double confidence,
    String confidenceLabel,
    String recommendedRoute,
    String primaryActionLabel,
    List<String> reasons
) {}

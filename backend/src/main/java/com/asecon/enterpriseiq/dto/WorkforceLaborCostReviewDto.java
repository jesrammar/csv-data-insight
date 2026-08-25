package com.asecon.enterpriseiq.dto;

public record WorkforceLaborCostReviewDto(
    String sourceGestor,
    String normalizedGestor,
    String status,
    String detail,
    long rows
) {}

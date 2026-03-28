package com.asecon.enterpriseiq.dto;

import java.util.List;

public record AdvisorRecommendationsDto(
    String period,
    String summary,
    List<AdvisorActionDto> actions
) {}


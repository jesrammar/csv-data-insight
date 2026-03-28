package com.asecon.enterpriseiq.dto;

import java.time.Instant;
import java.util.List;

public record AdvisorRecommendationSnapshotDto(
    Long id,
    Long companyId,
    String period,
    String source,
    Instant createdAt,
    String summary,
    List<AdvisorActionDto> actions
) {}


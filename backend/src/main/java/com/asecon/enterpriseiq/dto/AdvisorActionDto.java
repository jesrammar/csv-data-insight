package com.asecon.enterpriseiq.dto;

import java.util.List;

public record AdvisorActionDto(
    String horizon,
    String priority,
    String title,
    String detail,
    String kpi,
    List<AdvisorEvidenceDto> evidence
) {}

package com.asecon.enterpriseiq.dto;

import java.util.List;

public record TribunalSummaryDto(
    TribunalKpiDto kpis,
    List<TribunalGestorDto> gestores,
    List<TribunalActivityPointDto> activity,
    List<TribunalRiskDto> risk
) {}

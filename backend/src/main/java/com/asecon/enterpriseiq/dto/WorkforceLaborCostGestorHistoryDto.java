package com.asecon.enterpriseiq.dto;

import java.util.List;

public record WorkforceLaborCostGestorHistoryDto(
    String gestor,
    List<WorkforceLaborCostGestorHistoryPointDto> points
) {}

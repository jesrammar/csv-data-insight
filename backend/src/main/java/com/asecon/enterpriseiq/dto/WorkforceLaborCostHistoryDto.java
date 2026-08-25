package com.asecon.enterpriseiq.dto;

import java.util.List;

public record WorkforceLaborCostHistoryDto(
    List<WorkforceLaborCostHistoryPointDto> imports,
    List<WorkforceLaborCostGestorHistoryDto> gestores
) {}

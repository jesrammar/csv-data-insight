package com.asecon.enterpriseiq.dto;

import java.time.Instant;

public record WorkforceLaborCostGestorHistoryPointDto(
    String referencePeriod,
    String referenceLabel,
    Instant createdAt,
    Double costePersonalAnual,
    Double ssEmpresaAnual,
    Double costeLaboralAnual
) {}

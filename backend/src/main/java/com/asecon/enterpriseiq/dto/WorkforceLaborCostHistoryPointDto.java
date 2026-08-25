package com.asecon.enterpriseiq.dto;

import java.time.Instant;

public record WorkforceLaborCostHistoryPointDto(
    Long importId,
    String referencePeriod,
    String referenceLabel,
    String filename,
    Instant createdAt,
    Double totalCostePersonalAnual,
    Double totalSsEmpresaAnual,
    Double totalCosteLaboralAnual,
    Integer gestorCount
) {}

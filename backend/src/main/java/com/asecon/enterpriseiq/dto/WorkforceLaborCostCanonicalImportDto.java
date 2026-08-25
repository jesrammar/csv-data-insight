package com.asecon.enterpriseiq.dto;

import java.util.List;

public record WorkforceLaborCostCanonicalImportDto(
    Long matchedWorkforceImportId,
    String matchedWorkforceReferencePeriod,
    String matchedWorkforceReferenceLabel,
    String currency,
    boolean multiCurrency,
    List<String> detectedCurrencies,
    List<WorkforceLaborCostWorkerDto> workers
) {}

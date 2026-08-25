package com.asecon.enterpriseiq.dto;

import java.util.List;

public record WorkforceLaborCostImportSummaryDto(
    WorkforceLaborCostsDto summary,
    List<String> detectedColumns,
    WorkforceLaborCostCanonicalImportDto canonical
) {}

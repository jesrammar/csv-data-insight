package com.asecon.enterpriseiq.dto;

import java.util.List;

public record WorkforceLaborCostsDto(
    Double totalCostePersonalAnual,
    Double totalSsEmpresaAnual,
    Double totalCosteLaboralAnual,
    Double costeMedioPorGestor,
    List<WorkforceMonthlyCostDto> monthlyTotals,
    List<WorkforceLaborCostGestorDto> gestores,
    long reviewCount,
    List<WorkforceLaborCostReviewDto> reviews
) {}

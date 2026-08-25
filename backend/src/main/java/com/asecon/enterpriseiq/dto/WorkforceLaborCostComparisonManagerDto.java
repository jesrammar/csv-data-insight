package com.asecon.enterpriseiq.dto;

public record WorkforceLaborCostComparisonManagerDto(
    String gestor,
    long baseWorkers,
    long comparisonWorkers,
    Double baseCosteTotal,
    Double comparisonCosteTotal,
    Double deltaCosteTotal,
    Double deltaPctCosteTotal,
    String deltaPctCosteTotalState
) {}

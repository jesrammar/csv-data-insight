package com.asecon.enterpriseiq.dto;

public record WorkforceLaborCostComparisonWorkerDto(
    String canonicalWorkerId,
    String workerLabel,
    String identityStrategy,
    String baseGestor,
    String comparisonGestor,
    String baseMatchingState,
    String comparisonMatchingState,
    String comparisonState,
    String detail,
    Double baseCostePersonal,
    Double comparisonCostePersonal,
    Double deltaCostePersonal,
    Double baseSsEmpresa,
    Double comparisonSsEmpresa,
    Double deltaSsEmpresa,
    Double baseCosteTotal,
    Double comparisonCosteTotal,
    Double deltaCosteTotal,
    Double deltaPctCosteTotal,
    String deltaPctCosteTotalState
) {}

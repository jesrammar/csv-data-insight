package com.asecon.enterpriseiq.dto;

public record WorkforceLaborCostComparisonTotalsDto(
    Double baseCostePersonal,
    Double comparisonCostePersonal,
    Double deltaCostePersonal,
    Double deltaPctCostePersonal,
    String deltaPctCostePersonalState,
    Double baseSsEmpresa,
    Double comparisonSsEmpresa,
    Double deltaSsEmpresa,
    Double deltaPctSsEmpresa,
    String deltaPctSsEmpresaState,
    Double baseCosteTotal,
    Double comparisonCosteTotal,
    Double deltaCosteTotal,
    Double deltaPctCosteTotal,
    String deltaPctCosteTotalState,
    Double reconciledBaseCosteTotal,
    Double reconciledComparisonCosteTotal
) {}

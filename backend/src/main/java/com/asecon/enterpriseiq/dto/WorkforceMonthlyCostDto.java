package com.asecon.enterpriseiq.dto;

public record WorkforceMonthlyCostDto(
    String month,
    Double costePersonal,
    Double ssEmpresa,
    Double costeTotal
) {}

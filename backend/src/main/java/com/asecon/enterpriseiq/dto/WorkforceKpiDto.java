package com.asecon.enterpriseiq.dto;

public record WorkforceKpiDto(
    long totalClients,
    long totalGestores,
    long activeClients,
    long inactiveClients,
    Double totalMinutas,
    Double totalCarga,
    Double totalVolumenAsientos
) {}

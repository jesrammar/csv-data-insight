package com.asecon.enterpriseiq.dto;

public record TribunalKpiDto(
    long totalClients,
    long activeClients,
    double bajaPct,
    double minutasAvg,
    double cargaAvg,
    double contabilidadPct,
    double fiscalPct
) {}

package com.asecon.enterpriseiq.dto;

public record TribunalGestorDto(
    String gestor,
    long totalClients,
    long activeClients,
    double minutasAvg,
    double cargaAvg
) {}

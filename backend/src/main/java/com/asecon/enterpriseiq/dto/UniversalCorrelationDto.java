package com.asecon.enterpriseiq.dto;

public record UniversalCorrelationDto(
    String columnA,
    String columnB,
    double correlation
) {}

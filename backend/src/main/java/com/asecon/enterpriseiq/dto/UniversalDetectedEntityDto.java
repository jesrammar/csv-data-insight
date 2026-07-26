package com.asecon.enterpriseiq.dto;

public record UniversalDetectedEntityDto(
    String entityType,
    String keyColumn,
    String labelColumn,
    long distinctCount
) {}

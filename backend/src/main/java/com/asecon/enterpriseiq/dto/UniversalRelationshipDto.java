package com.asecon.enterpriseiq.dto;

public record UniversalRelationshipDto(
    String sourceColumn,
    String targetColumn,
    String relationType,
    double confidence,
    String detail
) {}

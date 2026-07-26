package com.asecon.enterpriseiq.dto;

import java.util.List;

public record UniversalColumnDto(
    String name,
    String detectedType,
    String physicalType,
    String semanticType,
    String analyticalType,
    String nullSemantics,
    Double semanticConfidence,
    long totalCount,
    long nullCount,
    long uniqueCount,
    Double min,
    Double max,
    Double mean,
    Double median,
    Double p90,
    String dateMin,
    String dateMax,
    List<String> validAggregations,
    List<String> recommendedCharts,
    List<String> relatedColumns,
    List<String> warnings,
    List<UniversalTopValueDto> topValues,
    List<UniversalBucketDto> histogram,
    List<UniversalBucketDto> dateSeries
) {}

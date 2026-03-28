package com.asecon.enterpriseiq.dto;

import java.util.List;

public record UniversalColumnDto(
    String name,
    String detectedType,
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
    List<UniversalTopValueDto> topValues,
    List<UniversalBucketDto> histogram,
    List<UniversalBucketDto> dateSeries
) {}

package com.asecon.enterpriseiq.dto;

import java.util.List;
import java.util.Map;

public record UniversalChartDataDto(
    String type,
    List<String> labels,
    List<Map<String, Object>> series,
    Map<String, Object> meta
) {}


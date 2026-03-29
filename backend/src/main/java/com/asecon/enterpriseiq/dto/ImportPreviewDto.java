package com.asecon.enterpriseiq.dto;

import java.util.List;
import java.util.Map;

public record ImportPreviewDto(
    List<String> headers,
    List<List<String>> sampleRows,
    Map<String, String> suggestedMapping,
    double confidence
) {}


package com.asecon.enterpriseiq.dto;

import java.util.List;

public record UniversalXlsxPreviewDto(
    List<String> sheets,
    Integer sheetIndex,
    Integer headerRow,
    List<String> headers,
    List<List<String>> sampleRows
) {}


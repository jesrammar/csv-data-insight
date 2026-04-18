package com.asecon.enterpriseiq.dto;

import java.util.List;

public record UniversalImportQualityDto(
    Long importId,
    String filename,
    long rowsScanned,
    int columns,
    long irregularRows,
    long nullCells,
    long totalCells,
    long dateParseErrors,
    long numberParseErrors,
    String minDate,
    String maxDate,
    int score,
    String level, // GREEN | YELLOW | RED
    List<Issue> issues,
    List<String> examples
) {
    public record Issue(String severity, String code, String title, String detail) {}
}


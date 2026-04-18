package com.asecon.enterpriseiq.dto;

public record UniversalImportAnalysisDto(
    Long bytes,
    Long durationMs,
    String charsetName,
    String delimiter,
    Boolean sampled,
    Integer totalRowsRead,
    Integer goodRows,
    Integer badRows,
    Integer observedRows,
    Integer removedEmptyColumns,
    Boolean convertedFromXlsx,
    UniversalXlsxOptionsDto xlsx
) {}


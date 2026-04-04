package com.asecon.enterpriseiq.dto;

import java.time.Instant;

public record UniversalImportDto(
    Long id,
    String filename,
    Instant createdAt,
    int rowCount,
    int columnCount
) {}


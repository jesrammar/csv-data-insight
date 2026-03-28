package com.asecon.enterpriseiq.dto;

import java.util.List;

public record UniversalRowsDto(
    String filename,
    List<String> headers,
    List<List<String>> rows
) {}


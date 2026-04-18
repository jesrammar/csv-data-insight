package com.asecon.enterpriseiq.dto;

import java.util.List;
import java.util.Map;

public record UniversalEvidenceDto(
    String filename,
    List<String> headers,
    List<List<String>> rows,
    List<Integer> rowNumbers,
    Map<String, Object> meta
) {}


package com.asecon.enterpriseiq.dto;

public record UniversalAutoSuggestionDto(
    String title,
    String description,
    UniversalViewRequest request
) {}


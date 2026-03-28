package com.asecon.enterpriseiq.dto;

import java.util.List;

public record TransactionPageDto(
    List<TransactionDto> items,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean hasNext
) {}


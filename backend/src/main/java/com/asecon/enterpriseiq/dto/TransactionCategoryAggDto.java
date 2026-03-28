package com.asecon.enterpriseiq.dto;

import java.math.BigDecimal;

public record TransactionCategoryAggDto(
    String category,
    BigDecimal total,
    BigDecimal inflows,
    BigDecimal outflows,
    long count
) {}


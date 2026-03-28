package com.asecon.enterpriseiq.dto;

import java.math.BigDecimal;

public record TransactionTotalsDto(
    BigDecimal inflows,
    BigDecimal outflows,
    BigDecimal net,
    long count
) {}


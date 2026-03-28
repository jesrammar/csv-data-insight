package com.asecon.enterpriseiq.dto;

import java.math.BigDecimal;

public record TransactionCounterpartyAggDto(
    String counterparty,
    BigDecimal total,
    long count
) {}


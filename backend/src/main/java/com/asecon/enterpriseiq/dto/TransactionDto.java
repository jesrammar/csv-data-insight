package com.asecon.enterpriseiq.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TransactionDto(
    Long id,
    String period,
    LocalDate txnDate,
    String description,
    BigDecimal amount,
    String counterparty
) {}


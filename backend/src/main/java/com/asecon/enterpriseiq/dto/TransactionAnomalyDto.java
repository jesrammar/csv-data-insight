package com.asecon.enterpriseiq.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TransactionAnomalyDto(
    LocalDate date,
    BigDecimal net,
    double score,
    String reason
) {}


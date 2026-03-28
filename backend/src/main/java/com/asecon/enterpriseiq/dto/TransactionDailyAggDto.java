package com.asecon.enterpriseiq.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TransactionDailyAggDto(
    LocalDate date,
    BigDecimal inflows,
    BigDecimal outflows,
    BigDecimal net,
    long count
) {}


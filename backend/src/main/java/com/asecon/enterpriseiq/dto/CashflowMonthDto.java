package com.asecon.enterpriseiq.dto;

import java.math.BigDecimal;

public record CashflowMonthDto(
    String monthKey,
    String label,
    BigDecimal inflow,
    BigDecimal outflow,
    BigDecimal net,
    BigDecimal endingBalance,
    BigDecimal deltaNet,
    BigDecimal deltaNetPct
) {}


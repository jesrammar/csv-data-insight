package com.asecon.enterpriseiq.dto;

import java.math.BigDecimal;

public record CashflowMonthDto(
    String monthKey,
    String label,
    BigDecimal inflow,
    BigDecimal outflow,
    BigDecimal net,
    BigDecimal declaredNet,
    BigDecimal derivedNet,
    String reconciliationStatus,
    String reconciliationWarning,
    BigDecimal endingBalance,
    BigDecimal deltaNet,
    BigDecimal deltaNetPct
) {}


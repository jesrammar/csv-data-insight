package com.asecon.enterpriseiq.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CashflowSummaryDto(
    String sourceFilename,
    Instant sourceCreatedAt,
    BigDecimal openingBalance,
    List<CashflowMonthDto> months,
    BigDecimal totalInflow,
    BigDecimal totalOutflow,
    BigDecimal totalNet,
    BigDecimal endingBalance,
    String bestMonth,
    String worstMonth
) {}


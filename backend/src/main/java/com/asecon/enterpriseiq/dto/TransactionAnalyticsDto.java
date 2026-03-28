package com.asecon.enterpriseiq.dto;

import java.util.List;

public record TransactionAnalyticsDto(
    TransactionTotalsDto totals,
    List<TransactionDailyAggDto> daily,
    List<TransactionCounterpartyAggDto> topCounterparties,
    List<TransactionCategoryAggDto> categories,
    List<TransactionAnomalyDto> anomalies
) {}

package com.asecon.enterpriseiq.dto;

import java.util.List;

public record ImportQualityDto(
    String period,
    long rowsNonEmpty,
    long rowsEmpty,
    long rowsParsed,
    long missingTxnDate,
    long missingAmount,
    long dateParseErrors,
    long amountParseErrors,
    String minDate,
    String maxDate,
    long outsidePeriodRows,
    long duplicateRows,
    long missingCounterpartyRows,
    long balanceEndMismatchRows,
    List<Issue> issues,
    List<String> examples
) {
    public record Issue(String severity, String code, String title, String detail) {}
}


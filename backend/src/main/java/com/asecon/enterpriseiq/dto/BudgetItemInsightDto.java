package com.asecon.enterpriseiq.dto;

import java.math.BigDecimal;

public record BudgetItemInsightDto(
    String code,
    String label,
    String normalizedLabel,
    String semanticKind,
    String financialNature,
    String cashflowNature,
    BigDecimal annualTotal,
    int zeroMonths,
    BigDecimal shareAbsPct,
    String zeroInterpretation,
    String rowType,
    String sectionKind,
    String mappingStatus,
    Integer sourceRow,
    String blockId,
    String exclusionReason,
    String canonicalIdentity,
    String canonicalRowId
) {}


package com.asecon.enterpriseiq.dto;

import java.math.BigDecimal;
import java.util.List;

public record BudgetItemDetailDto(
    Long sourceImportId,
    String analysisVersion,
    String lookupStrategy,
    String canonicalRowId,
    String canonicalIdentity,
    String code,
    String label,
    String normalizedLabel,
    String rowType,
    String semanticKind,
    String financialNature,
    String cashflowNature,
    String sectionKind,
    String mappingStatus,
    String blockId,
    String aggregationPolicy,
    BigDecimal declaredAnnualTotal,
    BigDecimal computedAnnualTotal,
    int sourceRowCount,
    List<Integer> sourceRows,
    List<String> reconciliationWarnings,
    List<BudgetItemDetailMonthDto> months
) {}

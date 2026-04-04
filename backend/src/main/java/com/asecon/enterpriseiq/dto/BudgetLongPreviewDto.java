package com.asecon.enterpriseiq.dto;

import com.asecon.enterpriseiq.service.BudgetLongNormalizer;
import java.time.Instant;
import java.util.List;

public record BudgetLongPreviewDto(
    String filename,
    Instant createdAt,
    List<String> monthKeys,
    String labelHeader,
    long totalRowsProduced,
    List<BudgetLongNormalizer.LongRow> sampleRows
) {}


package com.asecon.enterpriseiq.dto;

import java.util.List;

public record WorkforceLaborCostComparisonDto(
    String basePeriod,
    String comparisonPeriod,
    WorkforceImportDto baseImport,
    WorkforceImportDto comparisonImport,
    String currency,
    String status,
    String message,
    WorkforceLaborCostComparisonTotalsDto totals,
    WorkforceLaborCostComparisonCountsDto counts,
    List<WorkforceLaborCostComparisonManagerDto> gestores,
    List<WorkforceLaborCostComparisonWorkerDto> comparableWorkers,
    List<WorkforceLaborCostComparisonWorkerDto> onlyBaseWorkers,
    List<WorkforceLaborCostComparisonWorkerDto> onlyComparisonWorkers,
    List<WorkforceLaborCostComparisonWorkerDto> reviewWorkers
) {}

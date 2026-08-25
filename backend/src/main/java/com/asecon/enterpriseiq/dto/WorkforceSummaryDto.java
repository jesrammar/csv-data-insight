package com.asecon.enterpriseiq.dto;

import java.util.List;

public record WorkforceSummaryDto(
    WorkforceKpiDto kpis,
    List<WorkforceGestorDto> gestores,
    List<String> detectedColumns,
    List<Integer> activityYears,
    WorkforceLaborCostsDto laborCosts,
    WorkforceLaborCostsDto pairedLaborCosts,
    WorkforceHistoryDto history,
    WorkforceLaborCostHistoryDto laborCostsHistory,
    WorkforceInsightsDto insights,
    WorkforceImportDto workforceImport,
    WorkforceImportDto laborCostsImport,
    WorkforceImportDto pairedLaborCostsImport,
    List<WorkforceImportDto> workforceImports,
    List<WorkforceImportDto> laborCostImports
) {}

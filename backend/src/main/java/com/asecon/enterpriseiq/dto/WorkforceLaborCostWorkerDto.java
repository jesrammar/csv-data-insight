package com.asecon.enterpriseiq.dto;

import java.util.Map;

public record WorkforceLaborCostWorkerDto(
    String canonicalWorkerId,
    String identityStrategy,
    String workerLabel,
    String sourceWorker,
    String sourceGestor,
    String normalizedGestor,
    String canonicalGestor,
    String matchingState,
    String matchingDetail,
    String currency,
    Map<String, Double> costePersonalMensual,
    Map<String, Double> ssEmpresaMensual,
    Map<String, Double> costeTotalMensual,
    Double costePersonalAnual,
    Double ssEmpresaAnual,
    Double costeLaboralAnual
) {}

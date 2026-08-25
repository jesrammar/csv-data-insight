package com.asecon.enterpriseiq.dto;

import java.util.Map;

public record WorkforceLaborCostGestorDto(
    String gestor,
    Map<String, Double> costePersonalMensual,
    Map<String, Double> ssEmpresaMensual,
    Map<String, Double> costeTotalMensual,
    Double costePersonalAnual,
    Double ssEmpresaAnual,
    Double costeLaboralAnual
) {}

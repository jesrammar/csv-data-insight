package com.asecon.enterpriseiq.dto;

import java.util.List;

public record WorkforceGestorHistoryDto(
    String gestor,
    List<WorkforceGestorHistoryPointDto> points
) {}

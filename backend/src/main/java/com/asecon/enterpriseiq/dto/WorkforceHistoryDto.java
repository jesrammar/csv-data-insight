package com.asecon.enterpriseiq.dto;

import java.util.List;

public record WorkforceHistoryDto(
    List<WorkforceHistoryPointDto> imports,
    List<WorkforceGestorHistoryDto> gestores
) {}

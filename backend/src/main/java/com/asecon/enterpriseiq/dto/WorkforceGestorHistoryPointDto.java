package com.asecon.enterpriseiq.dto;

import java.time.Instant;

public record WorkforceGestorHistoryPointDto(
    Long importId,
    String referencePeriod,
    String referenceLabel,
    String filename,
    Instant createdAt,
    long totalClients,
    long activeClients,
    long inactiveClients,
    Double totalMinutas,
    Double totalCarga,
    Double cargaMedia,
    Double totalVolumenAsientos,
    Double pctContabilidadMedio
) {}

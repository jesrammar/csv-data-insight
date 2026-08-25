package com.asecon.enterpriseiq.dto;

import java.util.Map;

public record WorkforceGestorDto(
    String gestor,
    long totalClients,
    long activeClients,
    long inactiveClients,
    Double totalMinutas,
    Double totalCarga,
    Double cargaMedia,
    Double totalVolumenAsientos,
    Double pctContabilidadMedio,
    Double costePersonalAnual,
    Double ssEmpresaAnual,
    Double costeLaboralAnual,
    Double costePorCliente,
    Double costePor1000Minutas,
    Double costePor1000Asientos,
    Double minutasPor1000Coste,
    Double asientosPor1000Coste,
    long contModelosOk,
    long isIrpfOk,
    long ddccOk,
    long librosOk,
    WorkforceStatusBreakdownDto contModelosStates,
    WorkforceStatusBreakdownDto isIrpfStates,
    WorkforceStatusBreakdownDto ddccStates,
    WorkforceStatusBreakdownDto librosStates,
    Map<Integer, Long> annualSeatTotals
) {}

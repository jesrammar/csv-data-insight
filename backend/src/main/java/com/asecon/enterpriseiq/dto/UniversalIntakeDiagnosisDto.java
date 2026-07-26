package com.asecon.enterpriseiq.dto;

import java.util.List;

public record UniversalIntakeDiagnosisDto(
    String kind,
    String label,
    Double confidence,
    String confidenceLabel,
    boolean needsConfirmation,
    boolean structureRecognized,
    String headline,
    String detail,
    String recommendedModule,
    String recommendedRoute,
    String primaryActionLabel,
    String canonicalStatus,
    String canonicalDetail,
    List<String> reasons,
    List<String> warnings,
    List<UniversalIntakeCandidateDto> candidates
) {}

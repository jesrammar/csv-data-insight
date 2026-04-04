package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.metrics.ErrorTagger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class UniversalCsvServicePlanLimitsTest {
    @Test
    void uses_plan_specific_limits() {
        // We only test the pure decision logic (no parsing/storage).
        UniversalCsvService svc = new UniversalCsvService(
            null,
            null,
            null,
            null,
            null,
            50_000,
            15_000,
            50_000,
            100_000,
            25,
            15,
            25,
            45,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            new ErrorTagger(40, "other")
        );

        assertThat(svc.effectiveMaxAnalyzeRows(Plan.BRONZE)).isEqualTo(15_000);
        assertThat(svc.effectiveMaxAnalyzeRows(Plan.GOLD)).isEqualTo(50_000);
        assertThat(svc.effectiveMaxAnalyzeRows(Plan.PLATINUM)).isEqualTo(100_000);
        assertThat(svc.effectiveMaxAnalyzeSeconds(Plan.BRONZE)).isEqualTo(15);
        assertThat(svc.effectiveMaxAnalyzeSeconds(Plan.GOLD)).isEqualTo(25);
        assertThat(svc.effectiveMaxAnalyzeSeconds(Plan.PLATINUM)).isEqualTo(45);
    }
}

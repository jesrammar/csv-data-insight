package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.UniversalAutoSuggestionDto;
import com.asecon.enterpriseiq.dto.UniversalSummaryDto;
import com.asecon.enterpriseiq.metrics.ErrorTagger;
import com.asecon.enterpriseiq.model.Plan;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UniversalAutoSuggestionServiceTest {

    @Test
    void suggest_emitsExplicitAggregationModeInEveryPreset() throws Exception {
        UniversalCsvService csvService = mock(UniversalCsvService.class);
        UniversalViewService viewService = new UniversalViewService(null, new ObjectMapper());
        UniversalAutoSuggestionService service = new UniversalAutoSuggestionService(csvService, viewService);
        UniversalSummaryDto summary = summaryFor(UniversalCsvServiceSemanticTest.buildAccountingDataset());
        when(csvService.summary(7L, null)).thenReturn(Optional.of(summary));

        List<UniversalAutoSuggestionDto> suggestions = service.suggest(7L);

        assertThat(suggestions).isNotEmpty();
        assertThat(suggestions)
            .allSatisfy(item -> {
                assertThat(item.request()).isNotNull();
                assertThat(item.request().getAggregationMode()).isNotBlank();
                assertThat(item.request().getAggregation()).isNotBlank();
            });
    }

    private static UniversalSummaryDto summaryFor(String csv) throws Exception {
        UniversalCsvService service = new UniversalCsvService(
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
            new SimpleMeterRegistry(),
            new ErrorTagger(40, "other")
        );
        return service.analyzePreview(
            "dataset_contable_consultoria_ficticia_2025.csv",
            csv.getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8,
            Plan.PLATINUM,
            Instant.parse("2026-07-20T10:00:00Z")
        );
    }
}

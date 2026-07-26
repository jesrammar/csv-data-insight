package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.UniversalImportQualityDto;
import com.asecon.enterpriseiq.dto.UniversalSummaryDto;
import com.asecon.enterpriseiq.metrics.ErrorTagger;
import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.model.UniversalImport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UniversalImportQualityServiceTest {
    @Test
    void does_not_raise_generic_null_issue_for_structural_nulls() throws Exception {
        UniversalCsvService csvService = new UniversalCsvService(
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

        String csv = String.join("\n",
            "asiento_id,factura_id,cuenta_contable,debe,haber,base_imponible_eur",
            "ASI0001,F0001,0430,121.00,0.00,",
            "ASI0001,F0001,0705,0.00,100.00,100.00",
            "ASI0001,F0001,0477,0.00,21.00,",
            "ASI0002,F0002,0430,242.00,0.00,",
            "ASI0002,F0002,0705,0.00,200.00,200.00",
            "ASI0002,F0002,0477,0.00,42.00,",
            "ASI0003,,0572,0.00,90.00,",
            "ASI0003,,0400,90.00,0.00,",
            "ASI0004,,0572,0.00,110.00,",
            "ASI0004,,0626,10.00,0.00,"
        );
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        UniversalSummaryDto summary = csvService.analyzePreview(
            "quality-accounting.csv",
            bytes,
            StandardCharsets.UTF_8,
            Plan.PLATINUM,
            Instant.parse("2026-07-20T10:00:00Z")
        );

        UniversalImportFileService fileService = mock(UniversalImportFileService.class);
        UniversalCsvService summaryService = mock(UniversalCsvService.class);
        UniversalImport imp = new UniversalImport();
        imp.setFilename("quality-accounting.csv");
        imp.setCreatedAt(Instant.parse("2026-07-20T10:00:00Z"));
        imp.setRowCount(10);
        imp.setColumnCount(6);

        when(fileService.latest(1L)).thenReturn(java.util.Optional.of(imp));
        when(fileService.normalizedCsv(1L, null)).thenReturn(bytes);
        when(summaryService.summary(1L, null)).thenReturn(java.util.Optional.of(summary));

        UniversalImportQualityService qualityService = new UniversalImportQualityService(fileService, summaryService);
        UniversalImportQualityDto quality = qualityService.compute(1L, null);

        assertThat(quality.issues()).extracting(UniversalImportQualityDto.Issue::code).contains("STRUCTURAL_NULLS");
        assertThat(quality.issues()).extracting(UniversalImportQualityDto.Issue::code).doesNotContain("NULLS");
    }
}

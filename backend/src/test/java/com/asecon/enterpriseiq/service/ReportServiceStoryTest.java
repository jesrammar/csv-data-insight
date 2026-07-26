package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.UniversalChartDataDto;
import com.asecon.enterpriseiq.dto.UniversalSummaryDto;
import com.asecon.enterpriseiq.dto.UniversalViewRequest;
import com.asecon.enterpriseiq.model.Alert;
import com.asecon.enterpriseiq.model.AlertType;
import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.KpiMonthly;
import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.model.UniversalView;
import com.asecon.enterpriseiq.repo.AlertRepository;
import com.asecon.enterpriseiq.repo.CompanySettingsRepository;
import com.asecon.enterpriseiq.repo.KpiMonthlyRepository;
import com.asecon.enterpriseiq.repo.ReportRepository;
import com.asecon.enterpriseiq.repo.UniversalViewRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ReportServiceStoryTest {

    @Test
    void buildHtmlTemplate_includesStorySections() {
        ReportRepository reportRepository = mock(ReportRepository.class);
        KpiMonthlyRepository kpiMonthlyRepository = mock(KpiMonthlyRepository.class);
        AlertRepository alertRepository = mock(AlertRepository.class);
        UniversalCsvService universalCsvService = mock(UniversalCsvService.class);
        UniversalViewRepository universalViewRepository = mock(UniversalViewRepository.class);
        UniversalViewService universalViewService = mock(UniversalViewService.class);
        CompanySettingsRepository companySettingsRepository = mock(CompanySettingsRepository.class);
        PeriodWorkflowService periodWorkflowService = mock(PeriodWorkflowService.class);

        ReportService svc = new ReportService(
            reportRepository,
            kpiMonthlyRepository,
            alertRepository,
            universalCsvService,
            universalViewRepository,
            universalViewService,
            companySettingsRepository,
            periodWorkflowService,
            "target/test-reports"
        );

        Company company = new Company();
        company.setName("ACME SL");
        company.setPlan(Plan.PLATINUM);
        ReflectionTestUtils.setField(company, "id", 1L);

        KpiMonthly kpi = new KpiMonthly();
        kpi.setCompany(company);
        kpi.setPeriod("2026-03");
        kpi.setInflows(new BigDecimal("10000"));
        kpi.setOutflows(new BigDecimal("12000"));
        kpi.setNetFlow(new BigDecimal("-2000"));
        kpi.setEndingBalance(new BigDecimal("1500"));

        KpiMonthly prev = new KpiMonthly();
        prev.setCompany(company);
        prev.setPeriod("2026-02");
        prev.setInflows(new BigDecimal("9000"));
        prev.setOutflows(new BigDecimal("9500"));
        prev.setNetFlow(new BigDecimal("-500"));
        prev.setEndingBalance(new BigDecimal("3500"));

        Alert alert = new Alert();
        alert.setCompany(company);
        alert.setPeriod("2026-03");
        alert.setType(AlertType.ENDING_BALANCE_LOW);
        alert.setMessage("Saldo final bajo");
        alert.setCreatedAt(Instant.now());

        UniversalSummaryDto universalSummary = new UniversalSummaryDto(
            10L,
            "ventas.csv",
            Instant.now(),
            120,
            8,
            List.of(),
            List.of(),
            "invoice",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null
        );

        UniversalViewRequest universalRequest = new UniversalViewRequest();
        universalRequest.setName("Facturas por cliente");
        universalRequest.setType("CATEGORY_BAR");
        universalRequest.setAggregationMode("DISTINCT_INVOICE_COUNT");

        UniversalView universalView = new UniversalView();
        universalView.setName("Facturas por cliente");
        universalView.setType("CATEGORY_BAR");
        universalView.setConfigJson("{}");
        universalView.setSourceUniversalImportId(10L);
        ReflectionTestUtils.setField(universalView, "id", 77L);

        UniversalChartDataDto universalChart = new UniversalChartDataDto(
            "CATEGORY_BAR",
            List.of("Cliente A", "Cliente B"),
            List.of(java.util.Map.of("name", "Facturas", "data", List.of(new BigDecimal("8"), new BigDecimal("5")))),
            java.util.Map.of(
                "sourceFilename", "ventas.csv",
                "rowsUsed", 13,
                "categoryColumn", "cliente",
                "aggregationMode", "DISTINCT_INVOICE_COUNT"
            )
        );

        when(kpiMonthlyRepository.findByCompanyIdAndPeriod(1L, "2026-03")).thenReturn(Optional.of(kpi));
        when(kpiMonthlyRepository.findByCompanyIdAndPeriodBetweenOrderByPeriodAsc(eq(1L), anyString(), eq("2026-03")))
            .thenReturn(List.of(prev, kpi));
        when(alertRepository.findByCompanyIdAndPeriod(1L, "2026-03")).thenReturn(List.of(alert));
        when(universalCsvService.latest(1L)).thenReturn(Optional.of(universalSummary));
        when(universalViewRepository.findByIdAndCompanyId(77L, 1L)).thenReturn(Optional.of(universalView));
        when(universalViewService.decodeConfig("{}")).thenReturn(universalRequest);
        when(universalViewService.canonicalizeRequest(any(UniversalViewRequest.class), eq(1L), eq(10L))).thenReturn(universalRequest);
        when(universalViewService.previewSnapshot(eq(1L), any(UniversalViewRequest.class), eq(10L))).thenReturn(universalChart);
        when(companySettingsRepository.findById(1L)).thenReturn(Optional.empty());

        String html = svc.buildHtmlTemplate(company, "2026-03", "Resumen del periodo.", 77L);

        assertThat(html).contains("Señales clave para decidir");
        assertThat(html).contains("Plan de acción sugerido");
        assertThat(html).contains("Ficha rápida del periodo");
        assertThat(html).contains("Lectura complementaria");
        assertThat(html).contains("Caja del mes");
        assertThat(html).contains("Vista Universal enlazada");
        assertThat(html).contains("Esta vista cuenta facturas distintas.");
        assertThat(html).contains("Cliente A");
        assertThat(html).contains("Filas usadas: 13");
        assertThat(html).contains("lang=\"es\"");
        assertThat(html).contains("Radiografía operativa");
        assertThat(html).doesNotContain("Mini resumen");
        assertThat(html.indexOf("Resumen ejecutivo")).isLessThan(html.indexOf("Señales clave para decidir"));
        assertThat(html.indexOf("Señales clave para decidir")).isLessThan(html.indexOf("Radiografía operativa"));
        assertThat(html).doesNotContain("ÃƒÆ’");
    }
}

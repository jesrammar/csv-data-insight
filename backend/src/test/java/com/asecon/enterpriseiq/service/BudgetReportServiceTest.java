package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.BudgetItemInsightDto;
import com.asecon.enterpriseiq.dto.BudgetLongInsightsDto;
import com.asecon.enterpriseiq.dto.BudgetMonthDto;
import com.asecon.enterpriseiq.dto.BudgetMonthTotalDto;
import com.asecon.enterpriseiq.dto.BudgetSourceMetaDto;
import com.asecon.enterpriseiq.dto.BudgetSummaryDto;
import com.asecon.enterpriseiq.dto.CashflowSummaryDto;
import com.asecon.enterpriseiq.repo.AlertRepository;
import com.asecon.enterpriseiq.repo.CompanySettingsRepository;
import com.asecon.enterpriseiq.repo.KpiMonthlyRepository;
import com.asecon.enterpriseiq.repo.ReportRepository;
import com.asecon.enterpriseiq.repo.UniversalViewRepository;
import com.asecon.enterpriseiq.model.Company;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BudgetReportServiceTest {
    @Test
    void builds_html_with_story_sections() {
        ReportService reportService = mock(ReportService.class);
        BudgetReportService svc = new BudgetReportService(reportService);

        Company c = new Company();
        c.setName("ACME SL");

        BudgetSummaryDto summary = new BudgetSummaryDto(
            "presupuesto.xlsx",
            Instant.now(),
            11L,
            "budget-11-1",
            List.of(
                new BudgetMonthDto("ENERO", "Enero", new BigDecimal("55000.00"), new BigDecimal("38000.00"), new BigDecimal("17000.00"), null, null),
                new BudgetMonthDto("FEBRERO", "Febrero", new BigDecimal("55000.00"), new BigDecimal("38000.00"), new BigDecimal("17000.00"), null, null)
            ),
            new BigDecimal("110000.00"),
            new BigDecimal("76000.00"),
            new BigDecimal("34000.00"),
            new BigDecimal("8000.00"),
            new BigDecimal("4000.00"),
            new BigDecimal("30000.00"),
            new BigDecimal("-2000.00"),
            new BigDecimal("28000.00"),
            "ENERO",
            "FEBRERO"
        );

        BudgetLongInsightsDto longInsights = new BudgetLongInsightsDto(
            "presupuesto.xlsx",
            Instant.now(),
            11L,
            "budget-11-1",
            2,
            new BigDecimal("123"),
            "ENERO",
            "FEBRERO",
            new BigDecimal("65.00"),
            List.of(new BudgetMonthTotalDto("ENERO", "Enero", new BigDecimal("160"))),
            List.of(new BudgetItemInsightDto("700-2", "Ingresos", "INGRESOS", "REVENUE", "REVENUE", null, new BigDecimal("200"), 0, new BigDecimal("55.5"), "NOT_APPLICABLE", "DETAIL", "P_AND_L", "CANONICAL", 12, "ROW-12", null, "P_AND_L|REVENUE|||INGRESOS", "budget-11-1::11::ROW-12::P_AND_L::700-2::INGRESOS::P_AND_L|REVENUE|||INGRESOS")),
            List.of(new BudgetItemInsightDto("700-8", "Trigo", "TRIGO", "REVENUE", "REVENUE", null, new BigDecimal("10"), 10, new BigDecimal("4.5"), "SEASONAL", "DETAIL", "P_AND_L", "CANONICAL", 13, "ROW-13", null, "P_AND_L|REVENUE|||TRIGO", "budget-11-1::11::ROW-13::P_AND_L::700-8::TRIGO::P_AND_L|REVENUE|||TRIGO")),
            List.of()
        );

        CashflowSummaryDto cashflow = new CashflowSummaryDto(
            "presupuesto.xlsx",
            Instant.now(),
            11L,
            "budget-11-1",
            new BigDecimal("100000.00"),
            List.of(),
            new BigDecimal("0"),
            new BigDecimal("0"),
            new BigDecimal("0"),
            new BigDecimal("126000.00"),
            "ENERO",
            "FEBRERO"
        );

        String html = svc.buildBudgetReportHtml(
            c,
            new BudgetService.BudgetPdfBundle(
                new BudgetSourceMetaDto(11L, "budget-11-1", "presupuesto.xlsx", Instant.now(), "XLSX", 0, "Presupuesto", 3, "Concepto", 12),
                summary,
                cashflow,
                longInsights
            )
        );
        assertThat(html).contains("Mini resumen de control por mes");
        assertThat(html).contains("Acciones recomendadas");
        assertThat(html).contains("Drivers principales");
        assertThat(html).contains("Resumen por mes");
        assertThat(html).contains("700-2");
        assertThat(html).contains("data:image/png;base64");
        assertThat(html).contains("Resultado neto");
        assertThat(html).contains("Saldo final");
        assertThat(html).contains("110.000,00 €");
        assertThat(html).contains("76.000,00 €");
        assertThat(html).contains("34.000,00 €");
        assertThat(html).doesNotContain("110000.00 EUR");
        assertThat(html).doesNotContain("76000.00 EUR");
    }

    @Test
    void renders_pdf_cover_preview() throws Exception {
        Path artifactDir = Path.of("target", "test-artifacts");
        Files.createDirectories(artifactDir);

        ReportService reportService = new ReportService(
            mock(ReportRepository.class),
            mock(KpiMonthlyRepository.class),
            mock(AlertRepository.class),
            mock(UniversalCsvService.class),
            mock(UniversalViewRepository.class),
            mock(UniversalViewService.class),
            mock(CompanySettingsRepository.class),
            mock(PeriodWorkflowService.class),
            artifactDir.toString()
        );
        BudgetReportService svc = new BudgetReportService(reportService);

        Company c = new Company();
        c.setName("ACME SL");

        BudgetSummaryDto summary = new BudgetSummaryDto(
            "presupuesto.xlsx",
            Instant.now(),
            11L,
            "budget-11-1",
            List.of(
                new BudgetMonthDto("ENERO", "Enero", new BigDecimal("55000.00"), new BigDecimal("38000.00"), new BigDecimal("17000.00"), null, null),
                new BudgetMonthDto("FEBRERO", "Febrero", new BigDecimal("55000.00"), new BigDecimal("38000.00"), new BigDecimal("17000.00"), null, null)
            ),
            new BigDecimal("110000.00"),
            new BigDecimal("76000.00"),
            new BigDecimal("34000.00"),
            new BigDecimal("8000.00"),
            new BigDecimal("4000.00"),
            new BigDecimal("30000.00"),
            new BigDecimal("-2000.00"),
            new BigDecimal("28000.00"),
            "ENERO",
            "FEBRERO"
        );

        BudgetLongInsightsDto longInsights = new BudgetLongInsightsDto(
            "presupuesto.xlsx",
            Instant.now(),
            11L,
            "budget-11-1",
            2,
            new BigDecimal("123"),
            "ENERO",
            "FEBRERO",
            new BigDecimal("65.00"),
            List.of(new BudgetMonthTotalDto("ENERO", "Enero", new BigDecimal("160"))),
            List.of(new BudgetItemInsightDto("700-2", "Ingresos", "INGRESOS", "REVENUE", "REVENUE", null, new BigDecimal("200"), 0, new BigDecimal("55.5"), "NOT_APPLICABLE", "DETAIL", "P_AND_L", "CANONICAL", 12, "ROW-12", null, "P_AND_L|REVENUE|||INGRESOS", "budget-11-1::11::ROW-12::P_AND_L::700-2::INGRESOS::P_AND_L|REVENUE|||INGRESOS")),
            List.of(new BudgetItemInsightDto("700-8", "Trigo", "TRIGO", "REVENUE", "REVENUE", null, new BigDecimal("10"), 10, new BigDecimal("4.5"), "SEASONAL", "DETAIL", "P_AND_L", "CANONICAL", 13, "ROW-13", null, "P_AND_L|REVENUE|||TRIGO", "budget-11-1::11::ROW-13::P_AND_L::700-8::TRIGO::P_AND_L|REVENUE|||TRIGO")),
            List.of()
        );

        CashflowSummaryDto cashflow = new CashflowSummaryDto(
            "presupuesto.xlsx",
            Instant.now(),
            11L,
            "budget-11-1",
            new BigDecimal("100000.00"),
            List.of(),
            new BigDecimal("0"),
            new BigDecimal("0"),
            new BigDecimal("0"),
            new BigDecimal("126000.00"),
            "ENERO",
            "FEBRERO"
        );

        byte[] pdf = svc.renderBudgetPdf(
            c,
            new BudgetService.BudgetPdfBundle(
                new BudgetSourceMetaDto(11L, "budget-11-1", "presupuesto.xlsx", Instant.now(), "XLSX", 0, "Presupuesto", 3, "Concepto", 12),
                summary,
                cashflow,
                longInsights
            )
        );

        Path pdfPath = artifactDir.resolve("budget-report-cover-preview.pdf");
        Files.write(pdfPath, pdf);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(Files.exists(pdfPath)).isTrue();
    }
}

package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.BudgetItemInsightDto;
import com.asecon.enterpriseiq.dto.BudgetLongInsightsDto;
import com.asecon.enterpriseiq.dto.BudgetMonthDto;
import com.asecon.enterpriseiq.dto.BudgetMonthTotalDto;
import com.asecon.enterpriseiq.dto.BudgetSummaryDto;
import com.asecon.enterpriseiq.model.Company;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
            List.of(
                new BudgetMonthDto("ENERO", "Enero", new BigDecimal("100"), new BigDecimal("60"), new BigDecimal("40"), null, null),
                new BudgetMonthDto("FEBRERO", "Febrero", new BigDecimal("80"), new BigDecimal("90"), new BigDecimal("-10"), null, null)
            ),
            new BigDecimal("180"),
            new BigDecimal("150"),
            new BigDecimal("30"),
            "ENERO",
            "FEBRERO"
        );

        BudgetLongInsightsDto longInsights = new BudgetLongInsightsDto(
            "presupuesto.xlsx",
            Instant.now(),
            2,
            new BigDecimal("123"),
            "ENERO",
            "FEBRERO",
            new BigDecimal("65.00"),
            List.of(new BudgetMonthTotalDto("ENERO", "Enero", new BigDecimal("160"))),
            List.of(new BudgetItemInsightDto("700-2", "Ingresos", new BigDecimal("200"), 0, new BigDecimal("55.5"))),
            List.of(new BudgetItemInsightDto("700-8", "Trigo", new BigDecimal("10"), 10, new BigDecimal("4.5")))
        );

        String html = svc.buildBudgetReportHtml(c, summary, longInsights);
        assertThat(html).contains("Mini resumen");
        assertThat(html).contains("Insights accionables");
        assertThat(html).contains("Top drivers");
        assertThat(html).contains("Tabla resumen");
        assertThat(html).contains("700-2");
    }
}

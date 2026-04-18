package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.Alert;
import com.asecon.enterpriseiq.model.AlertType;
import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.KpiMonthly;
import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.repo.AlertRepository;
import com.asecon.enterpriseiq.repo.CompanySettingsRepository;
import com.asecon.enterpriseiq.repo.KpiMonthlyRepository;
import com.asecon.enterpriseiq.repo.ReportRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
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
        CompanySettingsRepository companySettingsRepository = mock(CompanySettingsRepository.class);

        ReportService svc = new ReportService(
            reportRepository,
            kpiMonthlyRepository,
            alertRepository,
            universalCsvService,
            companySettingsRepository,
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

        Alert a = new Alert();
        a.setCompany(company);
        a.setPeriod("2026-03");
        a.setType(AlertType.ENDING_BALANCE_LOW);
        a.setMessage("Saldo final bajo");
        a.setCreatedAt(Instant.now());

        when(kpiMonthlyRepository.findByCompanyIdAndPeriod(1L, "2026-03")).thenReturn(Optional.of(kpi));
        when(kpiMonthlyRepository.findByCompanyIdAndPeriodBetweenOrderByPeriodAsc(eq(1L), anyString(), eq("2026-03")))
            .thenReturn(List.of(prev, kpi));
        when(alertRepository.findByCompanyIdAndPeriod(1L, "2026-03")).thenReturn(List.of(a));
        when(universalCsvService.latest(1L)).thenReturn(Optional.empty());
        when(companySettingsRepository.findById(1L)).thenReturn(Optional.empty());

        String html = svc.buildHtmlTemplate(company, "2026-03", "Resumen del periodo.");

        assertThat(html).contains("Mini resumen");
        assertThat(html).contains("Insights accionables");
        assertThat(html).contains("Recomendaciones por impacto");
        assertThat(html).contains("Caja del mes");
    }
}

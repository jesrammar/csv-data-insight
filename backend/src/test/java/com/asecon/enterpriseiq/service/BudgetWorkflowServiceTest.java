package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.BudgetLongInsightsDto;
import com.asecon.enterpriseiq.dto.BudgetMonthDto;
import com.asecon.enterpriseiq.dto.BudgetMonthTotalDto;
import com.asecon.enterpriseiq.dto.BudgetSourceMetaDto;
import com.asecon.enterpriseiq.dto.BudgetSummaryDto;
import com.asecon.enterpriseiq.dto.CashflowMonthDto;
import com.asecon.enterpriseiq.dto.CashflowSummaryDto;
import com.asecon.enterpriseiq.model.KpiMonthly;
import com.asecon.enterpriseiq.model.UniversalImport;
import com.asecon.enterpriseiq.repo.KpiMonthlyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BudgetWorkflowServiceTest {

    @Test
    void builds_official_workflow_with_real_vs_budget_comparison() {
        BudgetService budgetService = mock(BudgetService.class);
        UniversalImportFileService universalImportFileService = mock(UniversalImportFileService.class);
        KpiMonthlyRepository kpiMonthlyRepository = mock(KpiMonthlyRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();

        BudgetWorkflowService service = new BudgetWorkflowService(
            budgetService,
            universalImportFileService,
            kpiMonthlyRepository,
            objectMapper
        );

        UniversalImport imp = new UniversalImport();
        imp.setFilename("presupuesto-2026.xlsx");
        imp.setCreatedAt(Instant.parse("2026-01-15T10:00:00Z"));
        when(universalImportFileService.latestList(7L, 2)).thenReturn(List.of(imp));
        when(universalImportFileService.latestAnnualBudgetList(7L, 2)).thenReturn(List.of(imp));

        BudgetSummaryDto summary = new BudgetSummaryDto(
            "presupuesto-2026.xlsx",
            imp.getCreatedAt(),
            41L,
            "budget-41-1",
            List.of(
                new BudgetMonthDto("ENERO", "Enero", new BigDecimal("1000"), new BigDecimal("700"), new BigDecimal("300"), null, null),
                new BudgetMonthDto("FEBRERO", "Febrero", new BigDecimal("1200"), new BigDecimal("800"), new BigDecimal("400"), null, null)
            ),
            new BigDecimal("2200"),
            new BigDecimal("1500"),
            new BigDecimal("700"),
            new BigDecimal("120"),
            new BigDecimal("40"),
            new BigDecimal("580"),
            new BigDecimal("95"),
            new BigDecimal("675"),
            "FEBRERO",
            "ENERO"
        );
        when(budgetService.latestBudget(7L)).thenReturn(summary);

        CashflowSummaryDto cashflow = new CashflowSummaryDto(
            "presupuesto-2026.xlsx",
            imp.getCreatedAt(),
            41L,
            "budget-41-1",
            new BigDecimal("500"),
            List.of(
                new CashflowMonthDto("ENERO", "Enero", new BigDecimal("900"), new BigDecimal("700"), new BigDecimal("200"), new BigDecimal("200"), null, "DECLARED_ONLY", null, new BigDecimal("700"), null, null),
                new CashflowMonthDto("FEBRERO", "Febrero", new BigDecimal("1000"), new BigDecimal("850"), new BigDecimal("150"), new BigDecimal("150"), null, "DECLARED_ONLY", null, new BigDecimal("850"), null, null)
            ),
            new BigDecimal("1900"),
            new BigDecimal("1550"),
            new BigDecimal("350"),
            new BigDecimal("850"),
            "ENERO",
            "FEBRERO"
        );
        when(budgetService.latestCashflow(7L)).thenReturn(cashflow);

        BudgetLongInsightsDto insights = new BudgetLongInsightsDto(
            "presupuesto-2026.xlsx",
            imp.getCreatedAt(),
            41L,
            "budget-41-1",
            10,
            new BigDecimal("3000"),
            "FEBRERO",
            "ENERO",
            new BigDecimal("60"),
            List.of(new BudgetMonthTotalDto("ENERO", "Enero", new BigDecimal("1600"))),
            List.of(),
            List.of(),
            List.of()
        );
        when(budgetService.latestBudgetLongInsights(7L)).thenReturn(insights);

        KpiMonthly latest = new KpiMonthly();
        latest.setPeriod("2026-02");
        when(kpiMonthlyRepository.findFirstByCompanyIdOrderByPeriodDesc(7L)).thenReturn(Optional.of(latest));

        KpiMonthly jan = new KpiMonthly();
        jan.setPeriod("2026-01");
        jan.setInflows(new BigDecimal("950"));
        jan.setOutflows(new BigDecimal("650"));
        jan.setNetFlow(new BigDecimal("300"));
        jan.setEndingBalance(new BigDecimal("800"));

        KpiMonthly feb = new KpiMonthly();
        feb.setPeriod("2026-02");
        feb.setInflows(new BigDecimal("980"));
        feb.setOutflows(new BigDecimal("900"));
        feb.setNetFlow(new BigDecimal("80"));
        feb.setEndingBalance(new BigDecimal("880"));

        when(kpiMonthlyRepository.findByCompanyIdAndPeriodBetweenOrderByPeriodAsc(7L, "2026-01", "2026-12"))
            .thenReturn(List.of(jan, feb));

        var workflow = service.getWorkflow(7L);

        assertThat(workflow.status()).isEqualTo("COMPARISON_READY");
        assertThat(workflow.sourcePresent()).isTrue();
        assertThat(workflow.comparisonReady()).isTrue();
        assertThat(workflow.actualMonthsAvailable()).isEqualTo(2);
        assertThat(workflow.comparisonSummary()).isNotNull();
        assertThat(workflow.comparisonSummary().comparisonYear()).isEqualTo(2026);
        assertThat(workflow.comparisonSummary().commonMonths()).isEqualTo(2);
        assertThat(workflow.comparisonSummary().plannedNetYtd()).isEqualByComparingTo("350.00");
        assertThat(workflow.comparisonSummary().actualNetYtd()).isEqualByComparingTo("380.00");
        assertThat(workflow.comparisonSummary().netVarianceYtd()).isEqualByComparingTo("30.00");
        assertThat(workflow.comparisonMonths()).hasSize(2);
        assertThat(workflow.comparisonMonths().get(0).actualPeriod()).isEqualTo("2026-01");
    }

    @Test
    void keeps_annual_workflow_alive_when_newer_universal_upload_is_not_annual() {
        BudgetService budgetService = mock(BudgetService.class);
        UniversalImportFileService universalImportFileService = mock(UniversalImportFileService.class);
        KpiMonthlyRepository kpiMonthlyRepository = mock(KpiMonthlyRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();

        BudgetWorkflowService service = new BudgetWorkflowService(
            budgetService,
            universalImportFileService,
            kpiMonthlyRepository,
            objectMapper
        );

        UniversalImport annual = new UniversalImport();
        annual.setFilename("presupuesto-2026.xlsx");
        annual.setCreatedAt(Instant.parse("2026-01-15T10:00:00Z"));

        UniversalImport newerGeneric = new UniversalImport();
        newerGeneric.setFilename("dataset-operativo.csv");
        newerGeneric.setCreatedAt(Instant.parse("2026-01-20T10:00:00Z"));
        newerGeneric.setAnalysisJson("""
            {"intakeDiagnosis":{"kind":"ACCOUNTING_LEDGER","label":"Libro contable"}}
            """);

        when(universalImportFileService.latestList(7L, 2)).thenReturn(List.of(newerGeneric, annual));
        when(universalImportFileService.latestAnnualBudgetList(7L, 2)).thenReturn(List.of(annual));

        BudgetSummaryDto summary = new BudgetSummaryDto(
            "presupuesto-2026.xlsx",
            annual.getCreatedAt(),
            41L,
            "budget-41-1",
            List.of(new BudgetMonthDto("ENERO", "Enero", new BigDecimal("1000"), new BigDecimal("700"), new BigDecimal("300"), null, null)),
            new BigDecimal("1000"),
            new BigDecimal("700"),
            new BigDecimal("300"),
            new BigDecimal("45"),
            new BigDecimal("15"),
            new BigDecimal("255"),
            new BigDecimal("30"),
            new BigDecimal("285"),
            "ENERO",
            "ENERO"
        );
        when(budgetService.latestBudget(7L)).thenReturn(summary);
        when(budgetService.latestCashflow(7L)).thenReturn(new CashflowSummaryDto(
            "presupuesto-2026.xlsx",
            annual.getCreatedAt(),
            41L,
            "budget-41-1",
            BigDecimal.ZERO,
            List.of(new CashflowMonthDto("ENERO", "Enero", new BigDecimal("1000"), new BigDecimal("700"), new BigDecimal("300"), new BigDecimal("300"), null, "DECLARED_ONLY", null, new BigDecimal("300"), null, null)),
            new BigDecimal("1000"),
            new BigDecimal("700"),
            new BigDecimal("300"),
            new BigDecimal("300"),
            "ENERO",
            "ENERO"
        ));
        when(budgetService.latestBudgetLongInsights(7L)).thenReturn(new BudgetLongInsightsDto(
            "presupuesto-2026.xlsx",
            annual.getCreatedAt(),
            41L,
            "budget-41-1",
            1,
            new BigDecimal("1000"),
            "ENERO",
            "ENERO",
            new BigDecimal("100"),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        ));
        when(kpiMonthlyRepository.findFirstByCompanyIdOrderByPeriodDesc(7L)).thenReturn(Optional.empty());

        var workflow = service.getWorkflow(7L);

        assertThat(workflow.status()).isNotEqualTo("WRONG_SOURCE");
        assertThat(workflow.sourcePresent()).isTrue();
        assertThat(workflow.sourceFilename()).isEqualTo("presupuesto-2026.xlsx");
    }

    @Test
    void analysis_bundle_stays_plan_only_even_if_legacy_actual_kpis_exist() {
        BudgetService budgetService = mock(BudgetService.class);
        UniversalImportFileService universalImportFileService = mock(UniversalImportFileService.class);
        KpiMonthlyRepository kpiMonthlyRepository = mock(KpiMonthlyRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();

        BudgetWorkflowService service = new BudgetWorkflowService(
            budgetService,
            universalImportFileService,
            kpiMonthlyRepository,
            objectMapper
        );

        Instant createdAt = Instant.parse("2026-01-15T10:00:00Z");
        BudgetSourceMetaDto meta = new BudgetSourceMetaDto(
            41L,
            "budget-41-1",
            "presupuesto-2026.xlsx",
            createdAt,
            "XLSX",
            0,
            "Escenario 2026",
            3,
            "Concepto",
            12
        );

        BudgetSummaryDto summary = new BudgetSummaryDto(
            "presupuesto-2026.xlsx",
            createdAt,
            41L,
            "budget-41-1",
            List.of(
                new BudgetMonthDto("ENERO", "Enero", new BigDecimal("1000"), new BigDecimal("700"), new BigDecimal("300"), null, null),
                new BudgetMonthDto("FEBRERO", "Febrero", new BigDecimal("1200"), new BigDecimal("800"), new BigDecimal("400"), null, null)
            ),
            new BigDecimal("2200"),
            new BigDecimal("1500"),
            new BigDecimal("700"),
            new BigDecimal("120"),
            new BigDecimal("40"),
            new BigDecimal("580"),
            new BigDecimal("95"),
            new BigDecimal("675"),
            "FEBRERO",
            "ENERO"
        );

        CashflowSummaryDto cashflow = new CashflowSummaryDto(
            "presupuesto-2026.xlsx",
            createdAt,
            41L,
            "budget-41-1",
            new BigDecimal("500"),
            List.of(
                new CashflowMonthDto("ENERO", "Enero", new BigDecimal("900"), new BigDecimal("700"), new BigDecimal("200"), new BigDecimal("200"), null, "DECLARED_ONLY", null, new BigDecimal("700"), null, null),
                new CashflowMonthDto("FEBRERO", "Febrero", new BigDecimal("1000"), new BigDecimal("850"), new BigDecimal("150"), new BigDecimal("150"), null, "DECLARED_ONLY", null, new BigDecimal("850"), null, null)
            ),
            new BigDecimal("1900"),
            new BigDecimal("1550"),
            new BigDecimal("350"),
            new BigDecimal("850"),
            "ENERO",
            "FEBRERO"
        );

        BudgetLongInsightsDto insights = new BudgetLongInsightsDto(
            "presupuesto-2026.xlsx",
            createdAt,
            41L,
            "budget-41-1",
            10,
            new BigDecimal("3000"),
            "FEBRERO",
            "ENERO",
            new BigDecimal("60"),
            List.of(new BudgetMonthTotalDto("ENERO", "Enero", new BigDecimal("1600"))),
            List.of(),
            List.of(),
            List.of()
        );

        when(budgetService.latestAnalysisSnapshot(7L)).thenReturn(
            new BudgetService.BudgetAnalysisSnapshot(meta, summary, cashflow, insights, null)
        );

        KpiMonthly legacyActual = new KpiMonthly();
        legacyActual.setPeriod("2026-01");
        legacyActual.setInflows(new BigDecimal("999"));
        legacyActual.setOutflows(new BigDecimal("777"));
        when(kpiMonthlyRepository.findByCompanyIdAndPeriodBetweenOrderByPeriodAsc(7L, "2026-01", "2026-12"))
            .thenReturn(List.of(legacyActual));

        var bundle = service.getAnalysisBundle(7L);

        assertThat(bundle.planImportId()).isEqualTo(41L);
        assertThat(bundle.actualImportId()).isNull();
        assertThat(bundle.sourceImportId()).isEqualTo(41L);
        assertThat(bundle.analysisVersion()).isEqualTo("budget-41-1");
        assertThat(bundle.sourceSheetIndex()).isEqualTo(0);
        assertThat(bundle.sourceHeaderRow()).isEqualTo(3);
        assertThat(bundle.sourceSheetName()).isEqualTo("Escenario 2026");
        assertThat(bundle.sourceHeaderLabel()).isEqualTo("Concepto");
        assertThat(bundle.comparisonStatus()).isEqualTo("NO_ACTUAL_DATA");
        assertThat(bundle.workflow().status()).isEqualTo("WAITING_ACTUALS");
        assertThat(bundle.workflow().comparisonReady()).isFalse();
        assertThat(bundle.workflow().actualMonthsAvailable()).isEqualTo(0);
        assertThat(bundle.workflow().comparisonSummary()).isNotNull();
        assertThat(bundle.workflow().comparisonSummary().commonMonths()).isEqualTo(0);
        assertThat(bundle.workflow().comparisonSummary().actualDataStatus()).isEqualTo("NO_ACTUAL_DATA");
    }
}

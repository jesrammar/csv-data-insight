package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.BudgetLongPreviewDto;
import com.asecon.enterpriseiq.dto.BudgetLongInsightsDto;
import com.asecon.enterpriseiq.dto.BudgetSummaryDto;
import com.asecon.enterpriseiq.dto.CashflowSummaryDto;
import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.UniversalImport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BudgetAnnualEndToEndIntegrationTest {

    private static final long COMPANY_ID = 7L;
    private static final Instant CREATED_AT = Instant.parse("2026-07-24T09:00:00Z");
    private static final BigDecimal EXPECTED_INCOME = new BigDecimal("660000.00");
    private static final BigDecimal EXPECTED_OPEX = new BigDecimal("456000.00");
    private static final BigDecimal EXPECTED_DEPRECIATION = new BigDecimal("24000.00");
    private static final BigDecimal EXPECTED_EBITDA = new BigDecimal("204000.00");
    private static final BigDecimal EXPECTED_EBIT = new BigDecimal("180000.00");
    private static final BigDecimal EXPECTED_FINANCIAL = new BigDecimal("12000.00");
    private static final BigDecimal EXPECTED_NET = new BigDecimal("192000.00");
    private static final BigDecimal EXPECTED_CLOSING = new BigDecimal("256000.00");

    @Test
    void processes_synthetic_annual_xlsx_end_to_end_without_double_counting() throws Exception {
        TabularFileService tabularFileService = new TabularFileService(120_000, 25, new SimpleMeterRegistry());
        MockMultipartFile file = annualWorkbookCompatible(Variant.base());

        TabularFileService.XlsxPreview preview = tabularFileService.previewXlsx(file, null, 5);
        TabularFileService.TabularCsv converted = tabularFileService.toCsv(file, null);
        TestContext ctx = contextForConvertedCsv(converted.bytes(), file.getOriginalFilename());

        BudgetLongPreviewDto previewDto = ctx.service.latestBudgetLongPreview(COMPANY_ID);
        BudgetService.LongBudgetSource source = ctx.service.latestLongBudgetSource(COMPANY_ID);
        BudgetSummaryDto summary = ctx.service.latestBudget(COMPANY_ID);
        CashflowSummaryDto cashflow = ctx.service.latestCashflow(COMPANY_ID);
        BudgetLongInsightsDto insights = ctx.service.latestBudgetLongInsights(COMPANY_ID);
        BudgetService.CanonicalBudgetAnalysis diagnostics = ctx.service.parseCanonicalBudgetAnalysis(converted.bytes(), file.getOriginalFilename(), preview.sheets().get(preview.detectedSheetIndex()));

        assertThat(preview.detectedHeaderRow1Based()).isEqualTo(3);
        assertThat(preview.detectedSheetIndex()).isEqualTo(0);
        assertThat(previewDto.monthKeys()).hasSize(12);
        assertThat(source.orderedMonthKeys()).hasSize(12);
        assertThat(summary.months()).hasSize(12);

        assertThat(total(source.plannedIncomeByMonth().values())).isEqualByComparingTo("600000.00");
        assertThat(total(source.plannedIncomeByMonth().values()).add(total(source.plannedOperatingAdjustmentsByMonth().values())))
            .isEqualByComparingTo(EXPECTED_INCOME);
        assertThat(total(source.plannedExpenseByMonth().values())).isEqualByComparingTo(EXPECTED_OPEX);
        assertThat(total(source.plannedCapexByMonth().values())).isEqualByComparingTo("48000.00");
        assertThat(total(source.plannedDepreciationByMonth().values())).isEqualByComparingTo(EXPECTED_DEPRECIATION);
        assertThat(total(source.plannedFinancialResultByMonth().values())).isEqualByComparingTo(EXPECTED_FINANCIAL);
        assertThat(summary.totalIncome()).isEqualByComparingTo(EXPECTED_INCOME);
        assertThat(summary.totalExpense()).isEqualByComparingTo(EXPECTED_OPEX);
        assertThat(summary.totalMargin()).isEqualByComparingTo(EXPECTED_EBITDA);
        assertThat(cashflow.endingBalance()).isEqualByComparingTo(EXPECTED_CLOSING);

        BigDecimal ebitda = total(source.plannedIncomeByMonth().values())
            .add(total(source.plannedOperatingAdjustmentsByMonth().values()))
            .subtract(total(source.plannedExpenseByMonth().values()));
        BigDecimal ebit = ebitda.subtract(total(source.plannedDepreciationByMonth().values()));
        BigDecimal net = ebit.add(total(source.plannedFinancialResultByMonth().values()));
        assertThat(ebitda).isEqualByComparingTo(EXPECTED_EBITDA);
        assertThat(ebit).isEqualByComparingTo(EXPECTED_EBIT);
        assertThat(net).isEqualByComparingTo(EXPECTED_NET);

        assertThat(diagnostics.rowAudits())
            .filteredOn(row -> "628".equals(row.accountingCode()) && row.includedInDrivers())
            .hasSizeLessThanOrEqualTo(1)
            .allMatch(row -> "P_AND_L".equals(row.sectionKind()));
        assertThat(diagnostics.rowAudits())
            .filteredOn(row -> "628".equals(row.accountingCode()) && row.includedInPnL())
            .allMatch(row -> row.exclusionReason() == null);
        assertThat(diagnostics.rowAudits())
            .filteredOn(row -> "628".equals(row.accountingCode()) && row.includedInCashflow())
            .isEmpty();
        assertThat(diagnostics.rowAudits())
            .filteredOn(row -> "628".equals(row.accountingCode()) && !row.includedInPnL())
            .allMatch(row -> !row.includedInDrivers())
            .allMatch(row -> row.exclusionReason() != null);
        assertThat(diagnostics.rowAudits())
            .filteredOn(BudgetService.RowAudit::includedInDrivers)
            .allMatch(row -> "DETAIL".equals(row.rowType()))
            .allMatch(row -> "P_AND_L".equals(row.sectionKind()))
            .allMatch(row -> !"REVIEW".equals(row.mappingStatus()));
        assertThat(diagnostics.rowAudits())
            .filteredOn(row -> List.of(
                "GASTOS EXPLOTACION",
                "OTROS GASTOS EXPLOTACION",
                "TOTAL COMPRAS",
                "TOTAL GASTOS",
                "TOTAL COMPRAS MAS GASTOS",
                "MARGEN BRUTO",
                "EBITDA",
                "EBIT",
                "BENEFICIO NETO",
                "SALDO ACUMULADO DE TESORERIA"
            ).contains(upper(row.normalizedLabel())))
            .allMatch(row -> !row.includedInDrivers());
        assertThat(diagnostics.rowAudits())
            .filteredOn(row -> "EXCLUDED_NO_ACTIVITY".equals(row.exclusionReason()))
            .allMatch(row -> !row.includedInDrivers() && !row.includedInPnL() && !row.includedInCashflow());
        assertThat(diagnostics.rowAudits())
            .filteredOn(row -> "USED_AS_AGGREGATE_FALLBACK".equals(row.aggregationPolicy()) && List.of("REVENUE", "OPEX", "CAPEX", "DEPRECIATION_AMORTIZATION").contains(row.financialNature()))
            .isEmpty();

        assertThat(insights.topDrivers()).extracting(item -> upper(item.label()))
            .doesNotContain("GASTOS EXPLOTACION", "OTROS GASTOS EXPLOTACION", "640-642 PERSONAL Y SEG SOCIAL", "TOTAL COMPRAS MAS GASTOS");
        assertThat(insights.topDrivers())
            .extracting(item -> item.canonicalIdentity())
            .doesNotHaveDuplicates();

        assertThat(diagnostics.reconciliations()).allMatch(BudgetService.ReconciliationCheck::passed);

        ReportService reportService = mock(ReportService.class);
        when(reportService.renderPdfFromHtml(anyString())).thenReturn("PDF".getBytes());
        BudgetReportService report = new BudgetReportService(reportService);
        Company company = new Company();
        company.setName("ACME Retail");
        byte[] pdf = report.renderBudgetPdf(
            company,
            new BudgetService.BudgetPdfBundle(
                new com.asecon.enterpriseiq.dto.BudgetSourceMetaDto(
                    ctx.imp().getId(),
                    summary.analysisVersion(),
                    summary.sourceFilename(),
                    summary.sourceCreatedAt(),
                    "XLSX",
                    0,
                    "Escenario anual",
                    3,
                    "Concepto",
                    12
                ),
                summary,
                cashflow,
                insights
            )
        );

        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(reportService).renderPdfFromHtml(htmlCaptor.capture());
        assertThat(pdf).isEqualTo("PDF".getBytes());
        assertThat(htmlCaptor.getValue()).contains("ACME Retail", "Drivers principales", "Resumen por mes", "660.000,00");
    }

    @Test
    void keeps_cashflow_tax_out_of_net_result_for_synthetic_retail_fixture() throws Exception {
        TabularFileService tabularFileService = new TabularFileService(120_000, 25, new SimpleMeterRegistry());
        MockMultipartFile file = fixtureWorkbook("budget/small-retail-annual-accounts-2026.xlsx");

        TabularFileService.XlsxPreview preview = tabularFileService.previewXlsx(file, null, 8);
        TabularFileService.TabularCsv converted = tabularFileService.toCsv(file, null);
        TestContext ctx = contextForConvertedCsv(converted.bytes(), file.getOriginalFilename());
        BudgetLongNormalizer.Result normalized = BudgetLongNormalizer.normalizeToLongCsv(
            converted.bytes(),
            String.valueOf(COMPANY_ID),
            50_000,
            50
        );

        BudgetService.CanonicalBudgetAnalysis diagnostics = ctx.service.parseCanonicalBudgetAnalysisStrict(
            normalized.longCsvBytes(),
            file.getOriginalFilename(),
            preview.sheets().get(preview.detectedSheetIndex())
        );
        BudgetService.LongBudgetSource source = ctx.service.latestLongBudgetSource(COMPANY_ID);
        BudgetSummaryDto summary = ctx.service.latestBudget(COMPANY_ID);
        CashflowSummaryDto cashflow = ctx.service.latestCashflow(COMPANY_ID);

        assertThat(summary.totalIncome()).isEqualByComparingTo("731950.00");
        assertThat(summary.totalExpense()).isEqualByComparingTo("664611.60");
        assertThat(summary.totalMargin()).isEqualByComparingTo("67338.40");
        assertThat(summary.totalEbit()).isEqualByComparingTo("61338.40");
        assertThat(summary.financialResult()).isEqualByComparingTo("-1800.00");
        assertThat(summary.netResult()).isEqualByComparingTo("47630.72");
        assertThat(cashflow.endingBalance()).isEqualByComparingTo("56900.15");
        assertThat(total(source.plannedTaxByMonth().values())).isEqualByComparingTo("11907.68");

        assertThat(diagnostics.rowAudits())
            .filteredOn(row -> "Impuestos pagados".equals(row.originalLabel()))
            .isNotEmpty()
            .allMatch(row -> "CASHFLOW".equals(row.sectionKind()))
            .allMatch(row -> !row.includedInPnL())
            .allMatch(row -> !row.includedInDrivers());
    }

    @Test
    void processes_cafeteria_energy_sample_through_the_same_annual_flow() throws Exception {
        TabularFileService tabularFileService = new TabularFileService(120_000, 25, new SimpleMeterRegistry());
        MockMultipartFile file = fixtureWorkbook("budget/cafe-energy-annual-plan-2026.xlsx");

        TabularFileService.XlsxPreview preview = tabularFileService.previewXlsx(file, null, 8);
        TabularFileService.TabularCsv converted = tabularFileService.toCsv(file, null);
        TestContext ctx = contextForConvertedCsv(converted.bytes(), file.getOriginalFilename());
        BudgetLongNormalizer.Result normalized = BudgetLongNormalizer.normalizeToLongCsv(
            converted.bytes(),
            String.valueOf(COMPANY_ID),
            50_000,
            50
        );

        BudgetService.CanonicalBudgetAnalysis diagnostics = ctx.service.parseCanonicalBudgetAnalysisStrict(
            normalized.longCsvBytes(),
            file.getOriginalFilename(),
            preview.sheets().get(preview.detectedSheetIndex())
        );
        BudgetLongPreviewDto previewDto = ctx.service.latestBudgetLongPreview(COMPANY_ID);
        BudgetService.LongBudgetSource source = ctx.service.latestLongBudgetSource(COMPANY_ID);
        BudgetSummaryDto summary = ctx.service.latestBudget(COMPANY_ID);
        CashflowSummaryDto cashflow = ctx.service.latestCashflow(COMPANY_ID);
        BudgetLongInsightsDto insights = ctx.service.latestBudgetLongInsights(COMPANY_ID);

        assertThat(preview.detectedSheetIndex()).isEqualTo(0);
        assertThat(preview.detectedHeaderRow1Based()).isEqualTo(6);
        assertThat(normalized.longCsvBytes()).isNotEmpty();
        assertThat(normalized.requiresConfirmation()).isFalse();
        assertThat(previewDto.monthKeys()).hasSize(12);
        assertThat(source.orderedMonthKeys()).hasSize(12);
        assertThat(summary).isNotNull();
        assertThat(summary.months()).hasSize(12);
        assertThat(total(source.plannedOperatingAdjustmentsByMonth().values())).isEqualByComparingTo("700.00");
        assertThat(summary.totalIncome()).isEqualByComparingTo("491200.00");
        assertThat(summary.totalExpense()).isEqualByComparingTo("365400.00");
        assertThat(summary.totalMargin()).isEqualByComparingTo("126500.00");
        assertThat(summary.totalDepreciation()).isEqualByComparingTo("10800.00");
        assertThat(summary.totalEbit()).isEqualByComparingTo("115700.00");
        assertThat(summary.financialResult()).isEqualByComparingTo("-2400.00");
        assertThat(total(source.plannedTaxByMonth().values())).isEqualByComparingTo("0.00");
        assertThat(summary.netResult()).isEqualByComparingTo("113300.00");
        assertThat(cashflow.endingBalance()).isEqualByComparingTo("136400.00");
        assertThat(diagnostics.source()).isNotNull();
        assertThat(diagnostics.rowAudits())
            .filteredOn(BudgetService.RowAudit::includedInDrivers)
            .allMatch(row -> "DETAIL".equals(row.rowType()))
            .allMatch(row -> "P_AND_L".equals(row.sectionKind()))
            .allMatch(row -> !"REVIEW".equals(row.mappingStatus()));
        assertThat(insights.topDrivers()).isNotEmpty();
    }

    @Test
    void processes_fruteria_sample_with_all_revenue_detail_rows_inside_ingresos_block() throws Exception {
        TabularFileService tabularFileService = new TabularFileService(120_000, 25, new SimpleMeterRegistry());
        MockMultipartFile file = fixtureWorkbook("budget/produce-market-annual-plan-2026.xlsx");

        TabularFileService.XlsxPreview preview = tabularFileService.previewXlsx(file, null, 8);
        TabularFileService.TabularCsv converted = tabularFileService.toCsv(file, null);
        TestContext ctx = contextForConvertedCsv(converted.bytes(), file.getOriginalFilename());
        BudgetLongNormalizer.Result normalized = BudgetLongNormalizer.normalizeToLongCsv(
            converted.bytes(),
            String.valueOf(COMPANY_ID),
            50_000,
            80
        );

        BudgetService.CanonicalBudgetAnalysis diagnostics = ctx.service.parseCanonicalBudgetAnalysisStrict(
            normalized.longCsvBytes(),
            file.getOriginalFilename(),
            preview.sheets().get(preview.detectedSheetIndex())
        );
        BudgetSummaryDto summary = ctx.service.latestBudget(COMPANY_ID);
        CashflowSummaryDto cashflow = ctx.service.latestCashflow(COMPANY_ID);

        assertThat(preview.detectedHeaderRow1Based()).isEqualTo(5);
        assertThat(normalized.requiresConfirmation()).isFalse();
        assertThat(summary.totalIncome()).isEqualByComparingTo("773800.00");
        assertThat(summary.totalExpense()).isEqualByComparingTo("698190.00");
        assertThat(summary.totalMargin()).isEqualByComparingTo("75610.00");
        assertThat(summary.totalEbit()).isEqualByComparingTo("64810.00");
        assertThat(summary.financialResult()).isEqualByComparingTo("-2160.00");
        assertThat(diagnostics.rowAudits())
            .filteredOn(row -> "Impuesto sobre beneficios estimado".equals(row.originalLabel()))
            .hasSize(12)
            .extracting(
                BudgetService.RowAudit::rowType,
                BudgetService.RowAudit::sectionKind,
                BudgetService.RowAudit::financialNature,
                BudgetService.RowAudit::includedInPnL,
                BudgetService.RowAudit::mappingStatus
            )
            .containsOnly(org.assertj.core.groups.Tuple.tuple("DETAIL", "P_AND_L", "TAX", true, "CANONICAL"));
        assertThat(diagnostics.rowAudits())
            .filteredOn(row -> "Intereses pagados".equals(row.originalLabel()))
            .hasSize(12)
            .allMatch(row -> "CASHFLOW".equals(row.sectionKind()))
            .allMatch(row -> !row.includedInPnL())
            .allMatch(row -> !row.includedInDrivers())
            .allMatch(row -> row.includedInCashflow());
        assertThat(summary.netResult()).isEqualByComparingTo("50120.00");
        assertThat(cashflow.endingBalance()).isEqualByComparingTo("78272.00");
        assertThat(diagnostics.rowAudits())
            .filteredOn(row -> List.of(
                "Ventas de fruta fresca",
                "Ventas de verduras y hortalizas",
                "Productos ecológicos y gourmet",
                "Zumos naturales y batidos",
                "Encargos y reparto a domicilio",
                "Otros ingresos"
            ).contains(row.originalLabel()))
            .isNotEmpty()
            .allMatch(row -> "DETAIL".equals(row.rowType()))
            .allMatch(row -> "P_AND_L".equals(row.sectionKind()))
            .allMatch(row -> "REVENUE".equals(row.financialNature()))
            .allMatch(row -> !row.includedInDrivers() || "REVENUE".equals(row.financialNature()));
        assertThat(diagnostics.rowAudits())
            .filteredOn(row -> "TOTAL INGRESOS".equals(row.originalLabel()))
            .isNotEmpty()
            .allMatch(row -> !"DETAIL".equals(row.rowType()))
            .allMatch(row -> !row.includedInDrivers());
    }

    @Test
    void processes_tintoreria_long_sample_without_leaving_operating_adjustments_in_review() throws Exception {
        TabularFileService tabularFileService = new TabularFileService(120_000, 25, new SimpleMeterRegistry());
        MockMultipartFile file = fixtureWorkbook("budget/dry-cleaning-annual-plan-long-2026.xlsx");

        TabularFileService.XlsxPreview preview = tabularFileService.previewXlsx(file, null, 8);
        TabularFileService.TabularCsv converted = tabularFileService.toCsv(file, null);
        TestContext ctx = contextForConvertedCsv(converted.bytes(), file.getOriginalFilename());
        BudgetLongNormalizer.Result normalized = BudgetLongNormalizer.normalizeToLongCsv(
            converted.bytes(),
            String.valueOf(COMPANY_ID),
            50_000,
            80
        );

        BudgetService.CanonicalBudgetAnalysis diagnostics = ctx.service.parseCanonicalBudgetAnalysisStrict(
            normalized.longCsvBytes(),
            file.getOriginalFilename(),
            preview.sheets().get(preview.detectedSheetIndex())
        );
        BudgetSummaryDto summary = ctx.service.latestBudget(COMPANY_ID);

        assertThat(preview.detectedSheetIndex()).isEqualTo(0);
        assertThat(preview.detectedHeaderRow1Based()).isEqualTo(5);
        assertThat(normalized.longCsvBytes()).isNotEmpty();
        assertThat(normalized.requiresConfirmation()).isFalse();
        assertThat(summary.months()).hasSize(12);

        assertThat(diagnostics.rowAudits())
            .filteredOn(row -> row.originalLabel() != null && row.originalLabel().toLowerCase(Locale.ROOT).contains("stock"))
            .isNotEmpty()
            .allMatch(row -> "DETAIL".equals(row.rowType()))
            .allMatch(row -> "P_AND_L".equals(row.sectionKind()))
            .allMatch(row -> "OPERATING_ADJUSTMENT".equals(row.financialNature()))
            .allMatch(BudgetService.RowAudit::includedInPnL)
            .allMatch(row -> !row.includedInDrivers())
            .allMatch(row -> !"REVIEW".equals(row.mappingStatus()));
        assertThat(diagnostics.rowAudits())
            .filteredOn(row -> "DETAIL".equals(row.rowType()) && "P_AND_L".equals(row.sectionKind()))
            .allMatch(row -> !"REVIEW".equals(row.mappingStatus()));
        assertThat(diagnostics.rowAudits())
            .filteredOn(BudgetService.RowAudit::includedInDrivers)
            .allMatch(row -> "DETAIL".equals(row.rowType()))
            .allMatch(row -> "P_AND_L".equals(row.sectionKind()))
            .allMatch(row -> !"REVIEW".equals(row.mappingStatus()));
    }

    @Test
    void processes_small_business_accounts_with_plain_gastos_block_and_correct_net_result() throws Exception {
        TabularFileService tabularFileService = new TabularFileService(120_000, 25, new SimpleMeterRegistry());
        MockMultipartFile file = smallBusinessWorkbook();

        TabularFileService.XlsxPreview preview = tabularFileService.previewXlsx(file, null, 8);
        TabularFileService.TabularCsv converted = tabularFileService.toCsv(file, null);
        TestContext ctx = contextForConvertedCsv(converted.bytes(), file.getOriginalFilename());

        BudgetLongNormalizer.Result normalized = BudgetLongNormalizer.normalizeToLongCsv(
            converted.bytes(),
            String.valueOf(COMPANY_ID),
            50_000,
            50
        );
        BudgetService.CanonicalBudgetAnalysis diagnostics = ctx.service.parseCanonicalBudgetAnalysisStrict(
            normalized.longCsvBytes(),
            file.getOriginalFilename(),
            preview.sheets().get(preview.detectedSheetIndex())
        );
        String debugSmallBusiness = diagnostics.rowAudits().stream()
            .filter(row -> List.of(
                "TESORERÍA",
                "Saldo inicial del mes",
                "Cobros del mes",
                "Pagos operativos",
                "CAPEX",
                "Préstamo",
                "Intereses del préstamo"
            ).contains(row.originalLabel()))
            .map(row -> row.originalLabel()
                + " rowType=" + row.rowType()
                + " month=" + row.monthKey()
                + " semantic=" + row.semanticKind()
                + " fin=" + row.financialNature()
                + " cash=" + row.cashflowNature()
                + " section=" + row.sectionKind()
                + " pnl=" + row.includedInPnL()
                + " cashflow=" + row.includedInCashflow()
                + " amount=" + row.plannedAmount()
                + " exclusion=" + row.exclusionReason()
                + " block=" + row.blockId())
            .reduce((left, right) -> left + " || " + right)
            .orElse("sin filas");
        BudgetSummaryDto summary = ctx.service.latestBudget(COMPANY_ID);
        CashflowSummaryDto cashflow = ctx.service.latestCashflow(COMPANY_ID);
        BudgetService.LongBudgetSource source = ctx.service.latestLongBudgetSource(COMPANY_ID);

        assertThat(preview.detectedHeaderRow1Based()).isEqualTo(4);
        assertThat(summary.totalIncome()).isEqualByComparingTo("731950.00");
        assertThat(summary.totalExpense()).isEqualByComparingTo("664611.60");
        assertThat(summary.totalMargin()).isEqualByComparingTo("67338.40");
        assertThat(summary.totalDepreciation()).isEqualByComparingTo("6000.00");
        assertThat(summary.totalEbit()).isEqualByComparingTo("61338.40");
        assertThat(summary.financialResult()).isEqualByComparingTo("-1800.00");
        assertThat(summary.netResult()).isEqualByComparingTo("47630.72");
        assertThat(cashflow.totalNet()).isEqualByComparingTo("38900.15");
        assertThat(cashflow.endingBalance()).isEqualByComparingTo("56900.15");
        assertThat(total(source.plannedTaxByMonth().values())).isEqualByComparingTo("11907.68");

        assertThat(summary.months()).filteredOn(month -> "ENERO".equals(month.monthKey()))
            .singleElement()
            .satisfies(month -> assertThat(month.margin()).isEqualByComparingTo("3193.20"));
        assertThat(summary.months()).filteredOn(month -> "FEBRERO".equals(month.monthKey()))
            .singleElement()
            .satisfies(month -> assertThat(month.margin()).isEqualByComparingTo("2917.40"));
        assertThat(summary.months()).filteredOn(month -> "ABRIL".equals(month.monthKey()))
            .singleElement()
            .satisfies(month -> assertThat(month.margin()).isEqualByComparingTo("5858.60"));
        assertThat(summary.months()).filteredOn(month -> "DICIEMBRE".equals(month.monthKey()))
            .singleElement()
            .satisfies(month -> assertThat(month.margin()).isEqualByComparingTo("9477.20"));

        assertThat(diagnostics.rowAudits())
            .filteredOn(row -> List.of(
                "Sueldos y Seguridad Social",
                "Alquiler del local",
                "Luz",
                "Agua",
                "Comisiones del TPV",
                "Seguro del negocio",
                "Gestoría",
                "Limpieza, bolsas y pequeños consumibles",
                "Reparaciones y mantenimiento",
                "Otros gastos"
            ).contains(row.originalLabel()))
            .allMatch(BudgetService.RowAudit::includedInPnL)
            .allMatch(row -> "DETAIL".equals(row.rowType()))
            .allMatch(row -> "OPEX".equals(row.financialNature()))
            .allMatch(row -> "P_AND_L".equals(row.sectionKind()));
        assertThat(diagnostics.rowAudits())
            .filteredOn(row -> "GASTOS".equals(row.originalLabel()))
            .allMatch(row -> "SUBTOTAL".equals(row.rowType()))
            .allMatch(row -> !row.includedInDrivers());
        assertThat(diagnostics.rowAudits())
            .filteredOn(row -> "Intereses del préstamo".equals(row.originalLabel()))
            .allMatch(BudgetService.RowAudit::includedInPnL)
            .allMatch(row -> "FINANCING".equals(row.financialNature()))
            .allMatch(row -> !row.includedInDrivers());
        assertThat(diagnostics.rowAudits())
            .filteredOn(row -> "Impuesto estimado".equals(row.originalLabel()))
            .allMatch(BudgetService.RowAudit::includedInPnL)
            .allMatch(row -> "TAX".equals(row.financialNature()))
            .allMatch(row -> !row.includedInDrivers());
        assertThat(diagnostics.rowAudits())
            .filteredOn(row -> "CAPEX".equals(row.originalLabel()))
            .allMatch(row -> "CAPEX".equals(row.financialNature()))
            .allMatch(row -> "CASHFLOW".equals(row.sectionKind()))
            .allMatch(row -> !row.includedInPnL());
        assertThat(diagnostics.rowAudits())
            .filteredOn(row -> "Préstamo".equals(row.originalLabel()))
            .allMatch(row -> "CASHFLOW".equals(row.sectionKind()))
            .allMatch(row -> row.includedInCashflow() && !row.includedInPnL());
    }

    @ParameterizedTest
    @MethodSource("mutationVariants")
    void keeps_financial_reading_stable_across_equivalent_xlsx_mutations(Variant variant) throws Exception {
        TabularFileService tabularFileService = new TabularFileService(120_000, 25, new SimpleMeterRegistry());
        MockMultipartFile file = annualWorkbookCompatible(variant);
        TabularFileService.TabularCsv converted = tabularFileService.toCsv(file, null);
        TestContext ctx = contextForConvertedCsv(converted.bytes(), file.getOriginalFilename());

        BudgetService.LongBudgetSource source = ctx.service.latestLongBudgetSource(COMPANY_ID);
        BudgetSummaryDto summary = ctx.service.latestBudget(COMPANY_ID);
        BudgetLongInsightsDto insights = ctx.service.latestBudgetLongInsights(COMPANY_ID);
        BudgetService.CanonicalBudgetAnalysis diagnostics = ctx.service.parseCanonicalBudgetAnalysis(converted.bytes(), file.getOriginalFilename(), variant.sheetName);

        assertThat(source.orderedMonthKeys()).hasSize(12);
        assertThat(total(source.plannedIncomeByMonth().values()).add(total(source.plannedOperatingAdjustmentsByMonth().values())))
            .isEqualByComparingTo(EXPECTED_INCOME);
        assertThat(total(source.plannedExpenseByMonth().values())).isEqualByComparingTo(EXPECTED_OPEX);
        assertThat(total(source.plannedFinancialResultByMonth().values())).isEqualByComparingTo(EXPECTED_FINANCIAL);
        assertThat(summary.totalMargin()).isEqualByComparingTo(EXPECTED_EBITDA);
        assertThat(insights.topDrivers()).extracting(item -> upper(item.label()))
            .doesNotContain("COSTES OPERATIVOS", "GASTOS GENERALES", "TOTAL DE COSTES");

        if (variant.addAmbiguousRow) {
            assertThat(diagnostics.rowAudits())
                .filteredOn(row -> upper(row.originalLabel()).contains("ELEMENTO MISCELANEO")
                    || upper(row.normalizedLabel()).contains("ELEMENTO MISCELANEO"))
                .allMatch(row -> "REVIEW".equals(row.mappingStatus())
                    || (!row.includedInPnL() && !row.includedInCashflow() && !row.includedInDrivers()));
            assertThat(insights.topDrivers()).extracting(item -> upper(item.label()))
                .doesNotContain("ELEMENTO MISCELANEO");
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("annualSampleFiles")
    void observes_detail_vs_declared_subtotals_across_annual_samples(String filename) throws Exception {
        TabularFileService tabularFileService = new TabularFileService(120_000, 25, new SimpleMeterRegistry());
        MockMultipartFile file = fixtureWorkbook("budget/" + filename);

        TabularFileService.XlsxPreview preview = tabularFileService.previewXlsx(file, null, 8);
        TabularFileService.TabularCsv converted = tabularFileService.toCsv(file, null);
        TestContext ctx = contextForConvertedCsv(converted.bytes(), file.getOriginalFilename());
        BudgetLongNormalizer.Result normalized = BudgetLongNormalizer.normalizeToLongCsv(
            converted.bytes(),
            String.valueOf(COMPANY_ID),
            50_000,
            80
        );
        BudgetService.CanonicalBudgetAnalysis diagnostics = ctx.service.parseCanonicalBudgetAnalysisStrict(
            normalized.longCsvBytes(),
            file.getOriginalFilename(),
            preview.sheets().get(preview.detectedSheetIndex())
        );

        List<BudgetService.ReconciliationCheck> observed = diagnostics.reconciliations().stream()
            .filter(check -> check.code().startsWith("detail_"))
            .toList();

        assertThat(diagnostics).isNotNull();
        System.out.println(buildSampleObservationLine(filename, observed));
    }

    private void debug_uppercase_ambiguous_variant_canonical_snapshot() throws Exception {
        Variant variant = Variant.base().withAddAmbiguousRow(true).withAccentedLabels(true);
        TabularFileService tabularFileService = new TabularFileService(120_000, 25, new SimpleMeterRegistry());
        MockMultipartFile file = annualWorkbookCompatible(variant);
        TabularFileService.TabularCsv converted = tabularFileService.toCsv(file, null);
        TestContext ctx = contextForConvertedCsv(converted.bytes(), file.getOriginalFilename());

        BudgetLongNormalizer.Result normalized = BudgetLongNormalizer.normalizeToLongCsv(
            converted.bytes(),
            String.valueOf(COMPANY_ID),
            50_000,
            50
        );
        BudgetService.CanonicalBudgetAnalysis diagnostics = ctx.service.parseCanonicalBudgetAnalysisStrict(
            normalized.longCsvBytes(),
            file.getOriginalFilename(),
            variant.sheetName
        );
        BudgetSummaryDto summary = ctx.service.latestBudget(COMPANY_ID);

        String debug = diagnostics.rowAudits().stream()
            .filter(row -> List.of(
                "GASTOS EXPLOTACION",
                "OTROS GASTOS EXPLOTACION",
                "640-642 PERSONAL Y SEG SOCIAL",
                "TRABAJOS REALIZADOS OTRAS EMPRESAS",
                "ARRENDAMIENTOS Y CÁNONES",
                "ARRENDAMIENTOS Y CÁNONES",
                "SUMINISTROS",
                "ELEMENTO MISCELANEO"
            ).contains(upper(row.originalLabel())))
            .map(row -> row.monthKey()
                + " | " + row.originalLabel()
                + " | rowType=" + row.rowType()
                + " | section=" + row.sectionKind()
                + " | semantic=" + row.semanticKind()
                + " | fin=" + row.financialNature()
                + " | cash=" + row.cashflowNature()
                + " | pnl=" + row.includedInPnL()
                + " | drivers=" + row.includedInDrivers()
                + " | amount=" + row.plannedAmount()
                + " | status=" + row.mappingStatus()
                + " | exclusion=" + row.exclusionReason())
            .reduce((left, right) -> left + System.lineSeparator() + right)
            .orElse("sin filas");

        System.out.println("DEBUG_VARIANT3_MARGIN=" + summary.totalMargin());
        System.out.println("DEBUG_VARIANT3_OPEX=" + summary.totalExpense());
        System.out.println("DEBUG_VARIANT3_AUDIT_START");
        System.out.println(debug);
        System.out.println("DEBUG_VARIANT3_AUDIT_END");
    }

    private void debug_reordered_aggregate_first_variant_canonical_snapshot() throws Exception {
        Variant variant = Variant.base().withReorderedBlocks(true).withAggregateBeforeDetail(true);
        TabularFileService tabularFileService = new TabularFileService(120_000, 25, new SimpleMeterRegistry());
        MockMultipartFile file = annualWorkbookCompatible(variant);
        TabularFileService.TabularCsv converted = tabularFileService.toCsv(file, null);
        TestContext ctx = contextForConvertedCsv(converted.bytes(), file.getOriginalFilename());

        BudgetLongNormalizer.Result normalized = BudgetLongNormalizer.normalizeToLongCsv(
            converted.bytes(),
            String.valueOf(COMPANY_ID),
            50_000,
            50
        );
        BudgetService.CanonicalBudgetAnalysis diagnostics = ctx.service.parseCanonicalBudgetAnalysisStrict(
            normalized.longCsvBytes(),
            file.getOriginalFilename(),
            variant.sheetName
        );
        BudgetService.LongBudgetSource source = ctx.service.latestLongBudgetSource(COMPANY_ID);
        BudgetSummaryDto summary = ctx.service.latestBudget(COMPANY_ID);

        String debug = diagnostics.rowAudits().stream()
            .filter(row -> List.of(
                "GASTOS EXPLOTACION",
                "OTROS GASTOS EXPLOTACION",
                "640-642 PERSONAL Y SEG SOCIAL",
                "TRABAJOS REALIZADOS OTRAS EMPRESAS",
                "ARRENDAMIENTOS Y CÁNONES",
                "ARRENDAMIENTOS Y CÁNONES",
                "SUMINISTROS",
                "TESORERÍA",
                "TESORERIA",
                "SALDO INICIAL"
            ).contains(upper(row.originalLabel())))
            .map(row -> row.monthKey()
                + " | " + row.originalLabel()
                + " | rowType=" + row.rowType()
                + " | section=" + row.sectionKind()
                + " | semantic=" + row.semanticKind()
                + " | fin=" + row.financialNature()
                + " | cash=" + row.cashflowNature()
                + " | pnl=" + row.includedInPnL()
                + " | drivers=" + row.includedInDrivers()
                + " | amount=" + row.plannedAmount()
                + " | status=" + row.mappingStatus()
                + " | exclusion=" + row.exclusionReason()
                + " | block=" + row.blockId())
            .reduce((left, right) -> left + System.lineSeparator() + right)
            .orElse("sin filas");

        System.out.println("DEBUG_REORDERED_OPEX_SOURCE=" + total(source.plannedExpenseByMonth().values()));
        System.out.println("DEBUG_REORDERED_MARGIN=" + summary.totalMargin());
        System.out.println("DEBUG_REORDERED_OPEX_SUMMARY=" + summary.totalExpense());
        System.out.println("DEBUG_REORDERED_AUDIT_START");
        System.out.println(debug);
        System.out.println("DEBUG_REORDERED_AUDIT_END");
    }

    private static Stream<Arguments> mutationVariants() {
        return Stream.of(
            Arguments.of(Variant.base().withSheetName("Escenario normal 2025").withExtraBlankRows(true)),
            Arguments.of(Variant.base().withTrailingBlankColumns(true).withUppercaseMonths(true)),
            Arguments.of(Variant.base().withReorderedBlocks(true).withAggregateBeforeDetail(true)),
            Arguments.of(Variant.base().withAddAmbiguousRow(true).withAccentedLabels(true)),
            Arguments.of(Variant.base().withAddCashflowDuplicate(true).withSheetName("PRESUPUESTO anual"))
        );
    }

    private static Stream<Arguments> annualSampleFiles() {
        return Stream.of(
            "cafe-energy-annual-plan-2026.xlsx",
            "produce-market-annual-plan-2026.xlsx",
            "small-retail-annual-accounts-2026.xlsx",
            "dry-cleaning-annual-plan-long-2026.xlsx",
            "auto-workshop-annual-plan-2026.xlsx"
        ).map(Arguments::of);
    }

    private static MockMultipartFile fixtureWorkbook(String relativePath) throws IOException {
        String resourcePath = "/fixtures/" + relativePath;
        try (InputStream input = BudgetAnnualEndToEndIntegrationTest.class.getResourceAsStream(resourcePath)) {
            assertThat(input).as(resourcePath).isNotNull();
            return new MockMultipartFile(
                "file",
                relativePath.substring(relativePath.lastIndexOf('/') + 1),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                input.readAllBytes()
            );
        }
    }

    private static String buildSampleObservationLine(String filename, List<BudgetService.ReconciliationCheck> checks) {
        if (checks == null || checks.isEmpty()) {
            return "[annual-observation] " + filename + " -> no reliable declared subtotal";
        }
        String joined = checks.stream()
            .map(check -> check.code()
                + "=" + check.status()
                + " subtotal=" + check.selectedSubtotal()
                + " detail=" + check.comparableDetail()
                + " excluded=" + check.excludedAdjustments()
                + " expected=" + check.expectedValue()
                + " actual=" + check.actualValue()
                + " diff=" + check.difference()
                + (check.warning() == null ? "" : " warning=" + check.warning()))
            .reduce((left, right) -> left + " | " + right)
            .orElse("sin observaciones");
        return "[annual-observation] " + filename + " -> " + joined;
    }

    private TestContext contextForConvertedCsv(byte[] csvBytes, String filename) {
        UniversalImportFileService importFileService = mock(UniversalImportFileService.class);
        UniversalImport imp = new UniversalImport();
        imp.setFilename(filename);
        imp.setCreatedAt(CREATED_AT);

        when(importFileService.latestAnnualBudget(COMPANY_ID)).thenReturn(Optional.of(imp));
        when(importFileService.normalizedCsv(COMPANY_ID, imp.getId())).thenReturn(csvBytes);

        return new TestContext(new BudgetService(importFileService), imp);
    }

    private static MockMultipartFile smallBusinessWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Cuentas 2026");
            sheet.createRow(0).createCell(0).setCellValue("DESAVIO EL RINCON - CUENTAS ANUALES 2026");
            sheet.createRow(1).createCell(0).setCellValue("Datos ficticios para pruebas");
            sheet.createRow(2);

            var header = sheet.createRow(3);
            header.createCell(0).setCellValue("Concepto");
            List<String> monthHeaders = List.of("Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre", "Total año");
            for (int i = 0; i < monthHeaders.size(); i++) {
                header.createCell(i + 1).setCellValue(monthHeaders.get(i));
            }

            int rowIndex = 4;
            for (RowData rowData : smallBusinessAnnualRows()) {
                var row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(rowData.label);
                for (int i = 0; i < rowData.months.length; i++) {
                    row.createCell(i + 1).setCellValue(rowData.months[i]);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return new MockMultipartFile(
                "file",
                "small-retail-annual-accounts-2026.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                out.toByteArray()
            );
        }
    }

    private static MockMultipartFile annualWorkbook(Variant variant) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(variant.sheetName);
            sheet.createRow(0).createCell(0).setCellValue("PRESUPUESTO ANUAL SINTETICO");
            if (variant.extraBlankRows) {
                sheet.createRow(1);
            } else {
                sheet.createRow(1).createCell(0).setCellValue("");
            }

            int headerRowIndex = 2;
            var header = sheet.createRow(headerRowIndex);
            header.createCell(0).setCellValue("Concepto");
            List<String> monthHeaders = variant.uppercaseMonths
                ? List.of("ENERO", "FEBRERO", "MARZO", "ABRIL", "MAYO", "JUNIO", "JULIO", "AGOSTO", "SEPTIEMBRE", "OCTUBRE", "NOVIEMBRE", "DICIEMBRE", "TOTAL AÑO")
                : List.of("Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre", "Total año");
            for (int i = 0; i < monthHeaders.size(); i++) {
                header.createCell(i + 1).setCellValue(monthHeaders.get(i));
            }
            if (variant.trailingBlankColumns) {
                header.createCell(14).setCellValue("");
                header.createCell(15).setCellValue("");
            }

            List<RowData> rows = annualRows(variant);
            int rowIndex = headerRowIndex + 1;
            for (RowData rowData : rows) {
                var row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(rowData.label);
                for (int i = 0; i < rowData.months.length; i++) {
                    row.createCell(i + 1).setCellValue(rowData.months[i]);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return new MockMultipartFile("file", "synthetic-annual-budget.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }

    private static MockMultipartFile annualWorkbookCompatible(Variant variant) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            List<RowData> rows = annualRows(variant);
            boolean hasExplicitAnnualTotalColumn = rows.stream().anyMatch(row -> row.months.length > 12);

            Sheet sheet = workbook.createSheet(variant.sheetName);
            sheet.createRow(0).createCell(0).setCellValue("PRESUPUESTO ANUAL SINTETICO");
            if (variant.extraBlankRows) {
                sheet.createRow(1);
            } else {
                sheet.createRow(1).createCell(0).setCellValue("");
            }

            int headerRowIndex = 2;
            var header = sheet.createRow(headerRowIndex);
            header.createCell(0).setCellValue("Concepto");
            List<String> monthHeaders = variant.uppercaseMonths
                ? (hasExplicitAnnualTotalColumn
                    ? List.of("ENERO", "FEBRERO", "MARZO", "ABRIL", "MAYO", "JUNIO", "JULIO", "AGOSTO", "SEPTIEMBRE", "OCTUBRE", "NOVIEMBRE", "DICIEMBRE", "TOTAL ANUAL")
                    : List.of("ENERO", "FEBRERO", "MARZO", "ABRIL", "MAYO", "JUNIO", "JULIO", "AGOSTO", "SEPTIEMBRE", "OCTUBRE", "NOVIEMBRE", "DICIEMBRE"))
                : (hasExplicitAnnualTotalColumn
                    ? List.of("Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre", "Total anual")
                    : List.of("Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"));
            for (int i = 0; i < monthHeaders.size(); i++) {
                header.createCell(i + 1).setCellValue(monthHeaders.get(i));
            }
            if (variant.trailingBlankColumns) {
                header.createCell(monthHeaders.size() + 1).setCellValue("");
                header.createCell(monthHeaders.size() + 2).setCellValue("");
            }

            int rowIndex = headerRowIndex + 1;
            for (RowData rowData : rows) {
                var row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(rowData.label);
                for (int i = 0; i < rowData.months.length; i++) {
                    row.createCell(i + 1).setCellValue(rowData.months[i]);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return new MockMultipartFile("file", "synthetic-annual-budget.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }

    private static List<RowData> smallBusinessAnnualRows() {
        return List.of(
            row("INGRESOS", zeroes(13)),
            row("Ventas de alimentación", 18000, 17500, 18500, 19000, 20000, 21000, 21500, 19500, 22000, 23000, 24000, 27000, 251000),
            row("Ventas de bebidas", 13000, 12500, 13500, 14000, 14500, 16000, 17000, 15500, 16500, 17500, 18500, 22000, 190500),
            row("Ventas de tabaco", 10500, 10300, 10600, 10800, 11000, 11500, 12000, 11700, 12100, 12400, 12800, 13500, 139200),
            row("Pan y bollería", 5000, 4900, 5200, 5400, 5600, 5800, 6000, 5500, 5900, 6200, 6500, 7500, 69500),
            row("Chucherías y snacks", 4200, 4100, 4300, 4500, 4700, 5000, 5200, 4800, 5100, 5400, 5700, 6800, 59800),
            row("Comisiones de recargas, loterías y paquetería", 1200, 1200, 1250, 1300, 1350, 1500, 1600, 1500, 1550, 1650, 1750, 2100, 17950),
            row("Otros ingresos", 200, 200, 200, 1800, 200, 200, 200, 200, 200, 200, 200, 200, 4000),
            row("TOTAL INGRESOS", 52100, 50700, 53550, 56800, 57350, 61000, 63500, 58700, 63350, 66350, 69450, 79100, 731950),
            row("", zeroes(13)),
            row("GASTOS", zeroes(13)),
            row("Compras de mercadería", 36510, 35517, 37477, 38597, 40067, 42546, 44270, 41016, 44241, 46266, 48379, 54810, 509696),
            row("Sueldos y Seguridad Social", 7800, 7800, 7800, 7800, 7800, 8500, 8500, 8000, 7800, 7800, 7800, 9000, 96400),
            row("Alquiler del local", 1600, 1600, 1600, 1600, 1600, 1600, 1600, 1600, 1600, 1600, 1600, 1600, 19200),
            row("Luz", 900, 850, 820, 800, 780, 850, 1100, 1250, 900, 850, 900, 1150, 11150),
            row("Agua", 180, 180, 180, 180, 180, 180, 180, 180, 180, 180, 180, 180, 2160),
            row("Comisiones del TPV", 416.8, 405.6, 428.4, 454.4, 458.8, 488, 508, 469.6, 506.8, 530.8, 555.6, 632.8, 5855.6),
            row("Seguro del negocio", 220, 220, 220, 220, 220, 220, 220, 220, 220, 220, 220, 220, 2640),
            row("Gestoría", 280, 280, 280, 280, 280, 280, 280, 280, 280, 280, 280, 280, 3360),
            row("Limpieza, bolsas y pequeños consumibles", 450, 430, 450, 480, 500, 520, 550, 500, 520, 550, 580, 700, 6230),
            row("Reparaciones y mantenimiento", 300, 250, 350, 280, 320, 500, 450, 400, 350, 420, 500, 800, 4920),
            row("Otros gastos", 250, 250, 250, 250, 250, 250, 250, 250, 250, 250, 250, 250, 3000),
            row("TOTAL GASTOS", 48906.8, 47782.6, 49855.4, 50941.4, 52455.8, 55934, 57908, 54165.6, 56847.8, 58946.8, 61244.6, 69622.8, 664611.6),
            row("BENEFICIO ANTES DE AMORTIZACIÓN", 3193.2, 2917.4, 3694.6, 5858.6, 4894.2, 5066, 5592, 4534.4, 6502.2, 7403.2, 8205.4, 9477.2, 67338.4),
            row("Amortización de neveras, estanterías y equipos", 500, 500, 500, 500, 500, 500, 500, 500, 500, 500, 500, 500, 6000),
            row("BENEFICIO DE EXPLOTACIÓN", 2693.2, 2417.4, 3194.6, 5358.6, 4394.2, 4566, 5092, 4034.4, 6002.2, 6903.2, 7705.4, 8977.2, 61338.4),
            row("Intereses del préstamo", 150, 150, 150, 150, 150, 150, 150, 150, 150, 150, 150, 150, 1800),
            row("BENEFICIO ANTES DE IMPUESTOS", 2543.2, 2267.4, 3044.6, 5208.6, 4244.2, 4416, 4942, 3884.4, 5852.2, 6753.2, 7555.4, 8827.2, 59538.4),
            row("Impuesto estimado", 508.64, 453.48, 608.92, 1041.72, 848.84, 883.2, 988.4, 776.88, 1170.44, 1350.64, 1511.08, 1765.44, 11907.68),
            row("BENEFICIO NETO", 2034.56, 1813.92, 2435.68, 4166.88, 3395.36, 3532.8, 3953.6, 3107.52, 4681.76, 5402.56, 6044.32, 7061.76, 47630.72),
            row("", zeroes(13)),
            row("TESORERÍA", zeroes(13)),
            row("Saldo inicial del mes", 18000, 21193.2, 24000, 27000, 30000, 33500, 37000, 40500, 43000, 46000, 49500, 53000, 410693.2),
            row("Cobros del mes", 52100, 50700, 53550, 56800, 57350, 61000, 63500, 58700, 63350, 66350, 69450, 79100, 731950),
            row("Pagos operativos", 48906.8, 47893.2, 50550, 49800, 53850, 57500, 60000, 56200, 85350, 62850, 65950, 75199.85, 714049.85),
            row("CAPEX", 0, 0, 0, 4000, 0, 0, 0, 0, 0, 0, 0, 0, 4000),
            row("Préstamo", 0, 0, 0, 0, 0, 0, 0, 0, 25000, 0, 0, 0, 25000),
            row("Cash neto", 3193.2, 2806.8, 3000, 3000, 3500, 3500, 3500, 2500, 3000, 3500, 3500, 3900.15, 38900.15),
            row("Saldo final", 21193.2, 24000, 27000, 30000, 33500, 37000, 40500, 43000, 46000, 49500, 53000, 56900.15, 56900.15)
        );
    }

    private static List<RowData> annualRows(Variant variant) {
        List<RowData> revenue = List.of(
            row(label(variant, "Ingresos de explotación"), 55000, 55000, 55000, 55000, 55000, 55000, 55000, 55000, 55000, 55000, 55000, 55000),
            row(code(variant, "700", "Servicios profesionales"), 50000, 50000, 50000, 50000, 50000, 50000, 50000, 50000, 50000, 50000, 50000, 50000),
            row(code(variant, "710", "Variación de existencias"), 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000)
        );
        List<RowData> expense = List.of(
            row(label(variant, "GASTOS EXPLOTACION"), 38000, 38000, 38000, 38000, 38000, 38000, 38000, 38000, 38000, 38000, 38000, 38000),
            row(label(variant, "OTROS GASTOS EXPLOTACION"), 18000, 18000, 18000, 18000, 18000, 18000, 18000, 18000, 18000, 18000, 18000, 18000),
            row(label(variant, "640-642 Personal y seg social"), 20000, 20000, 20000, 20000, 20000, 20000, 20000, 20000, 20000, 20000, 20000, 20000),
            row(code(variant, "607", "Servicios externos"), 10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000),
            row(code(variant, "621", "Arrendamientos y cánones"), 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000),
            row(code(variant, "628", "Suministros"), 3000, 3000, 3000, 3000, 3000, 3000, 3000, 3000, 3000, 3000, 3000, 3000),
            row(code(variant, "640", "Sueldos y salarios"), 20000, 20000, 20000, 20000, 20000, 20000, 20000, 20000, 20000, 20000, 20000, 20000),
            row(code(variant, "629", "Otros gastos de explotación"), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            row(code(variant, "631", "Servicios auxiliares operativos"), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            row(label(variant, "TOTAL COMPRAS"), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 120000),
            row(label(variant, "TOTAL GASTOS"), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 456000),
            row(label(variant, "TOTAL COMPRAS MAS GASTOS"), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 456000)
        );
        List<RowData> derived = List.of(
            row(label(variant, "MARGEN BRUTO"), 45000, 45000, 45000, 45000, 45000, 45000, 45000, 45000, 45000, 45000, 45000, 45000),
            row(label(variant, "EBITDA"), 17000, 17000, 17000, 17000, 17000, 17000, 17000, 17000, 17000, 17000, 17000, 17000),
            row(label(variant, "EBIT"), 15000, 15000, 15000, 15000, 15000, 15000, 15000, 15000, 15000, 15000, 15000, 15000),
            row(label(variant, "Resultado financiero"), 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000),
            row(label(variant, "Beneficio neto"), 16000, 16000, 16000, 16000, 16000, 16000, 16000, 16000, 16000, 16000, 16000, 16000)
        );
        List<RowData> capexAndCash = List.of(
            row(code(variant, "218", "Inmovilizado y CAPEX"), 4000, 4000, 4000, 4000, 4000, 4000, 4000, 4000, 4000, 4000, 4000, 4000),
            row(code(variant, "681", "Amortización inmovilizado"), 2000, 2000, 2000, 2000, 2000, 2000, 2000, 2000, 2000, 2000, 2000, 2000),
            row(label(variant, "Tesorería"), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            row(label(variant, "Saldo inicial"), 100000, 113000, 126000, 139000, 152000, 165000, 178000, 191000, 204000, 217000, 230000, 243000),
            row(label(variant, "Cobros clientes"), 55000, 55000, 55000, 55000, 55000, 55000, 55000, 55000, 55000, 55000, 55000, 55000),
            row(label(variant, "Saldo mensual"), 13000, 13000, 13000, 13000, 13000, 13000, 13000, 13000, 13000, 13000, 13000, 13000),
            row(label(variant, "Saldo acumulado de tesorería"), 113000, 126000, 139000, 152000, 165000, 178000, 191000, 204000, 217000, 230000, 243000, 256000),
            row(code(variant, "607", "Servicios externos"), 10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000),
            row(code(variant, "621", "Arrendamientos y cánones"), 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000),
            row(code(variant, "628", "Suministros"), 3000, 3000, 3000, 3000, 3000, 3000, 3000, 3000, 3000, 3000, 3000, 3000),
            row(label(variant, "Pagos del periodo"), 42000, 42000, 42000, 42000, 42000, 42000, 42000, 42000, 42000, 42000, 42000, 42000),
            row(label(variant, "Saldo final"), 113000, 126000, 139000, 152000, 165000, 178000, 191000, 204000, 217000, 230000, 243000, 256000)
        );

        List<RowData> all = new ArrayList<>();
        if (variant.aggregateBeforeDetail) {
            all.addAll(expense.subList(0, 3));
        }
        all.addAll(revenue);
        if (variant.reorderedBlocks) {
            all.addAll(capexAndCash);
            all.addAll(expense);
            all.addAll(derived);
        } else {
            all.addAll(expense);
            all.addAll(derived);
            all.addAll(capexAndCash);
        }
        if (variant.addAmbiguousRow) {
            all.add(row("Elemento miscelaneo", 0, 0, 0, 0, 0, 100.00, 0, 0, 0, 0, 0, 0));
        }
        if (variant.addCashflowDuplicate) {
            all.add(row(code(variant, "621", "Arrendamientos y cánones"), 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000));
        }
        return all;
    }

    private static String label(Variant variant, String value) {
        return variant.accentedLabels ? value.toUpperCase(Locale.ROOT) : value;
    }

    private static String code(Variant variant, String code, String label) {
        return code + " " + label(variant, label);
    }

    private static RowData row(String label, double... months) {
        return new RowData(label, months);
    }

    private static double[] zeroes(int count) {
        return new double[count];
    }

    private static BigDecimal total(Iterable<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            if (value != null) {
                total = total.add(value);
            }
        }
        return total.setScale(2);
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private record TestContext(BudgetService service, UniversalImport imp) {}

    private record RowData(String label, double[] months) {}

    private static final class Variant {
        private final String sheetName;
        private final boolean extraBlankRows;
        private final boolean trailingBlankColumns;
        private final boolean uppercaseMonths;
        private final boolean reorderedBlocks;
        private final boolean aggregateBeforeDetail;
        private final boolean addAmbiguousRow;
        private final boolean addCashflowDuplicate;
        private final boolean accentedLabels;

        private Variant(String sheetName,
                        boolean extraBlankRows,
                        boolean trailingBlankColumns,
                        boolean uppercaseMonths,
                        boolean reorderedBlocks,
                        boolean aggregateBeforeDetail,
                        boolean addAmbiguousRow,
                        boolean addCashflowDuplicate,
                        boolean accentedLabels) {
            this.sheetName = sheetName;
            this.extraBlankRows = extraBlankRows;
            this.trailingBlankColumns = trailingBlankColumns;
            this.uppercaseMonths = uppercaseMonths;
            this.reorderedBlocks = reorderedBlocks;
            this.aggregateBeforeDetail = aggregateBeforeDetail;
            this.addAmbiguousRow = addAmbiguousRow;
            this.addCashflowDuplicate = addCashflowDuplicate;
            this.accentedLabels = accentedLabels;
        }

        static Variant base() {
            return new Variant("Escenario 2025 Normal", false, false, false, false, false, false, false, false);
        }

        Variant withSheetName(String value) { return new Variant(value, extraBlankRows, trailingBlankColumns, uppercaseMonths, reorderedBlocks, aggregateBeforeDetail, addAmbiguousRow, addCashflowDuplicate, accentedLabels); }
        Variant withExtraBlankRows(boolean value) { return new Variant(sheetName, value, trailingBlankColumns, uppercaseMonths, reorderedBlocks, aggregateBeforeDetail, addAmbiguousRow, addCashflowDuplicate, accentedLabels); }
        Variant withTrailingBlankColumns(boolean value) { return new Variant(sheetName, extraBlankRows, value, uppercaseMonths, reorderedBlocks, aggregateBeforeDetail, addAmbiguousRow, addCashflowDuplicate, accentedLabels); }
        Variant withUppercaseMonths(boolean value) { return new Variant(sheetName, extraBlankRows, trailingBlankColumns, value, reorderedBlocks, aggregateBeforeDetail, addAmbiguousRow, addCashflowDuplicate, accentedLabels); }
        Variant withReorderedBlocks(boolean value) { return new Variant(sheetName, extraBlankRows, trailingBlankColumns, uppercaseMonths, value, aggregateBeforeDetail, addAmbiguousRow, addCashflowDuplicate, accentedLabels); }
        Variant withAggregateBeforeDetail(boolean value) { return new Variant(sheetName, extraBlankRows, trailingBlankColumns, uppercaseMonths, reorderedBlocks, value, addAmbiguousRow, addCashflowDuplicate, accentedLabels); }
        Variant withAddAmbiguousRow(boolean value) { return new Variant(sheetName, extraBlankRows, trailingBlankColumns, uppercaseMonths, reorderedBlocks, aggregateBeforeDetail, value, addCashflowDuplicate, accentedLabels); }
        Variant withAddCashflowDuplicate(boolean value) { return new Variant(sheetName, extraBlankRows, trailingBlankColumns, uppercaseMonths, reorderedBlocks, aggregateBeforeDetail, addAmbiguousRow, value, accentedLabels); }
        Variant withAccentedLabels(boolean value) { return new Variant(sheetName, extraBlankRows, trailingBlankColumns, uppercaseMonths, reorderedBlocks, aggregateBeforeDetail, addAmbiguousRow, addCashflowDuplicate, value); }
    }
}



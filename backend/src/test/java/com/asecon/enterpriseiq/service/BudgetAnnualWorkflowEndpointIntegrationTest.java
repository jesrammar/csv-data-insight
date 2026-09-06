package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.BudgetItemInsightDto;
import com.asecon.enterpriseiq.dto.BudgetAnalysisBundleDto;
import com.asecon.enterpriseiq.dto.BudgetItemDetailDto;
import com.asecon.enterpriseiq.dto.BudgetLongInsightsDto;
import com.asecon.enterpriseiq.dto.BudgetSummaryDto;
import com.asecon.enterpriseiq.dto.BudgetWorkflowDto;
import com.asecon.enterpriseiq.dto.CashflowSummaryDto;
import com.asecon.enterpriseiq.dto.UniversalRowsDto;
import com.asecon.enterpriseiq.model.UniversalImport;
import com.asecon.enterpriseiq.repo.UniversalImportRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.StreamUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BudgetAnnualWorkflowEndpointIntegrationTest {

    private static final long COMPANY_ID = 9102L;
    private static final long USER_ID = 9102L;
    private static final String USER_EMAIL = "budget-admin@asecon.local";
    private static final String USER_PASSWORD = "password";

    private static final BigDecimal EXPECTED_INCOME = new BigDecimal("660000.00");
    private static final BigDecimal EXPECTED_REVENUE = new BigDecimal("600000.00");
    private static final BigDecimal EXPECTED_OPEX = new BigDecimal("456000.00");
    private static final BigDecimal EXPECTED_CAPEX = new BigDecimal("48000.00");
    private static final BigDecimal EXPECTED_DEPRECIATION = new BigDecimal("24000.00");
    private static final BigDecimal EXPECTED_EBITDA = new BigDecimal("204000.00");
    private static final BigDecimal EXPECTED_EBIT = new BigDecimal("180000.00");
    private static final BigDecimal EXPECTED_FINANCIAL = new BigDecimal("12000.00");
    private static final BigDecimal EXPECTED_NET = new BigDecimal("192000.00");
    private static final BigDecimal EXPECTED_CLOSING = new BigDecimal("256000.00");
    private static final BigDecimal EXPECTED_DESAVIO_INCOME = new BigDecimal("731950.00");
    private static final BigDecimal EXPECTED_DESAVIO_OPEX = new BigDecimal("664611.60");
    private static final BigDecimal EXPECTED_DESAVIO_EBITDA = new BigDecimal("67338.40");
    private static final BigDecimal EXPECTED_DESAVIO_EBIT = new BigDecimal("61338.40");
    private static final BigDecimal EXPECTED_DESAVIO_NET = new BigDecimal("47630.72");
    private static final BigDecimal EXPECTED_DESAVIO_CLOSING = new BigDecimal("56900.15");
    private static final List<String> DESAVIO_TRACKED_LABELS = List.of(
        "Ventas de alimentación",
        "TOTAL INGRESOS",
        "Compras de mercadería",
        "Sueldos y Seguridad Social",
        "Alquiler del local",
        "Cobros de ventas",
        "Pagos a proveedores",
        "Pagos de personal",
        "Pagos de alquiler, luz y agua",
        "Compra de neveras y equipos",
        "Préstamo recibido",
        "CASH NETO DEL MES",
        "SALDO FINAL DEL MES"
    );
    private static final List<BigDecimal> EXPECTED_PNL_INCOME_BY_MONTH = List.of(
        new BigDecimal("55000.00"),
        new BigDecimal("55000.00"),
        new BigDecimal("55000.00"),
        new BigDecimal("55000.00"),
        new BigDecimal("55000.00"),
        new BigDecimal("55000.00"),
        new BigDecimal("55000.00"),
        new BigDecimal("55000.00"),
        new BigDecimal("55000.00"),
        new BigDecimal("55000.00"),
        new BigDecimal("55000.00"),
        new BigDecimal("55000.00")
    );
    private static final List<BigDecimal> EXPECTED_PNL_OPEX_BY_MONTH = List.of(
        new BigDecimal("38000.00"),
        new BigDecimal("38000.00"),
        new BigDecimal("38000.00"),
        new BigDecimal("38000.00"),
        new BigDecimal("38000.00"),
        new BigDecimal("38000.00"),
        new BigDecimal("38000.00"),
        new BigDecimal("38000.00"),
        new BigDecimal("38000.00"),
        new BigDecimal("38000.00"),
        new BigDecimal("38000.00"),
        new BigDecimal("38000.00")
    );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private BudgetService budgetService;

    @Autowired
    private UniversalImportRepository universalImportRepository;

    @Autowired
    private UniversalImportFileService universalImportFileService;

    @BeforeEach
    void seedFixture() {
        jdbcTemplate.update("delete from universal_imports where company_id = ?", COMPANY_ID);
        jdbcTemplate.update("delete from kpi_monthly where company_id = ?", COMPANY_ID);
        jdbcTemplate.update("delete from user_companies where user_id = ?", USER_ID);
        jdbcTemplate.update("delete from users where id = ?", USER_ID);
        jdbcTemplate.update("delete from companies where id = ?", COMPANY_ID);

        jdbcTemplate.update(
            "insert into companies (id, name, plan) values (?, ?, ?)",
            COMPANY_ID,
            "Budget Flow Company",
            "GOLD"
        );
        jdbcTemplate.update(
            "insert into users (id, email, password_hash, role, enabled) values (?, ?, ?, ?, ?)",
            USER_ID,
            USER_EMAIL,
            passwordEncoder.encode(USER_PASSWORD),
            "ADMIN",
            true
        );
        jdbcTemplate.update(
            "insert into user_companies (user_id, company_id) values (?, ?)",
            USER_ID,
            COMPANY_ID
        );
    }

    @Test
    void budget_analysis_endpoint_exposes_the_synthetic_annual_bundle() throws Exception {
        String accessToken = login(USER_EMAIL, USER_PASSWORD).body().get("accessToken").toString();

        uploadAnnualWorkbook(accessToken, annualWorkbook());
        UniversalImport imported = latestImport();

        BudgetAnalysisBundleDto analysis = readJson(
            getAuthorized("/api/companies/{companyId}/budget/analysis", accessToken, COMPANY_ID),
            BudgetAnalysisBundleDto.class
        );
        BudgetService.BudgetPdfBundle pdfBundle = budgetService.latestBudgetPdfBundle(COMPANY_ID);

        assertThat(analysis.companyId()).isEqualTo(COMPANY_ID);
        assertThat(analysis.sourceImportId()).isEqualTo(imported.getId());
        assertThat(analysis.sourceFilename()).isEqualTo(imported.getFilename());
        assertThat(analysis.sourceSheetIndex()).isEqualTo(0);
        assertThat(analysis.sourceHeaderRow()).isEqualTo(3);
        assertThat(analysis.analysisVersion()).isNotBlank();

        assertThat(analysis.workflow()).isNotNull();
        assertThat(analysis.summary()).isNotNull();
        assertThat(analysis.cashflow()).isNotNull();
        assertThat(analysis.insights()).isNotNull();

        assertThat(analysis.workflow().plannedMonthsAvailable()).isEqualTo(12);
        assertThat(analysis.workflow().comparisonSummary()).isNotNull();
        assertThat(analysis.workflow().comparisonSummary().commonMonths()).isEqualTo(0);
        assertThat(analysis.workflow().comparisonSummary().actualDataStatus()).isEqualTo("NO_ACTUAL_DATA");
        assertThat(analysis.workflow().comparisonReady()).isFalse();
        assertThat(analysis.workflow().actualMonthsAvailable()).isEqualTo(0);

        assertThat(analysis.summary().months()).hasSize(12);
        assertThat(analysis.summary().totalIncome()).isEqualByComparingTo(EXPECTED_INCOME);
        assertThat(analysis.summary().totalExpense()).isCloseTo(EXPECTED_OPEX, within(new BigDecimal("0.01")));
        assertThat(analysis.summary().totalMargin()).isCloseTo(EXPECTED_EBITDA, within(new BigDecimal("0.01")));
        assertThat(analysis.summary().totalEbit()).isCloseTo(EXPECTED_EBIT, within(new BigDecimal("0.01")));
        assertThat(analysis.summary().netResult()).isCloseTo(EXPECTED_NET, within(new BigDecimal("0.01")));
        assertThat(analysis.cashflow().endingBalance()).isEqualByComparingTo(EXPECTED_CLOSING);

        assertThat(analysis.summary().sourceImportId()).isEqualTo(analysis.sourceImportId());
        assertThat(analysis.cashflow().sourceImportId()).isEqualTo(analysis.sourceImportId());
        assertThat(analysis.insights().sourceImportId()).isEqualTo(analysis.sourceImportId());
        assertThat(analysis.summary().analysisVersion()).isEqualTo(analysis.analysisVersion());
        assertThat(analysis.cashflow().analysisVersion()).isEqualTo(analysis.analysisVersion());
        assertThat(analysis.insights().analysisVersion()).isEqualTo(analysis.analysisVersion());

        assertThat(pdfBundle.meta().sourceImportId()).isEqualTo(analysis.sourceImportId());
        assertThat(pdfBundle.meta().analysisVersion()).isEqualTo(analysis.analysisVersion());
        assertThat(pdfBundle.summary().analysisVersion()).isEqualTo(analysis.analysisVersion());
        assertThat(pdfBundle.cashflow().analysisVersion()).isEqualTo(analysis.analysisVersion());
        assertThat(pdfBundle.longInsights().analysisVersion()).isEqualTo(analysis.analysisVersion());
        assertThat(pdfBundle.summary().sourceImportId()).isEqualTo(analysis.sourceImportId());
        assertThat(pdfBundle.cashflow().sourceImportId()).isEqualTo(analysis.sourceImportId());
        assertThat(pdfBundle.longInsights().sourceImportId()).isEqualTo(analysis.sourceImportId());
    }

    @Test
    void budget_analysis_endpoint_does_not_inherit_old_actuals_when_only_the_plan_is_linked() throws Exception {
        String accessToken = login(USER_EMAIL, USER_PASSWORD).body().get("accessToken").toString();

        jdbcTemplate.update(
            "insert into kpi_monthly (company_id, period, inflows, outflows, net_flow, ending_balance) values (?, ?, ?, ?, ?, ?)",
            COMPANY_ID,
            "2026-01",
            new BigDecimal("1500.00"),
            new BigDecimal("900.00"),
            new BigDecimal("600.00"),
            new BigDecimal("1000.00")
        );
        jdbcTemplate.update(
            "insert into kpi_monthly (company_id, period, inflows, outflows, net_flow, ending_balance) values (?, ?, ?, ?, ?, ?)",
            COMPANY_ID,
            "2026-02",
            new BigDecimal("1800.00"),
            new BigDecimal("1000.00"),
            new BigDecimal("800.00"),
            new BigDecimal("1800.00")
        );

        uploadAnnualWorkbook(accessToken, annualWorkbook());

        BudgetAnalysisBundleDto analysis = readJson(
            getAuthorized("/api/companies/{companyId}/budget/analysis", accessToken, COMPANY_ID),
            BudgetAnalysisBundleDto.class
        );

        assertThat(analysis.actualImportId()).isNull();
        assertThat(analysis.comparisonStatus()).isEqualTo("NO_ACTUAL_DATA");
        assertThat(analysis.workflow().status()).isNotEqualTo("COMPARISON_READY");
        assertThat(analysis.workflow().actualMonthsAvailable()).isEqualTo(0);
        assertThat(analysis.workflow().comparisonReady()).isFalse();
        assertThat(analysis.workflow().comparisonSummary()).isNotNull();
        assertThat(analysis.workflow().comparisonSummary().commonMonths()).isEqualTo(0);
        assertThat(analysis.workflow().comparisonSummary().actualDataStatus()).isEqualTo("NO_ACTUAL_DATA");
        assertThat(analysis.workflow().comparisonMonths())
            .allMatch(month -> !month.hasActual())
            .allMatch(month -> month.actualNet() == null)
            .allMatch(month -> month.netVariance() == null);
    }

    @Test
    void upload_summary_insights_workflow_and_pdf_share_the_same_canonical_execution() throws Exception {
        String accessToken = login(USER_EMAIL, USER_PASSWORD).body().get("accessToken").toString();

        uploadAnnualWorkbook(accessToken, annualWorkbook());
        UniversalImport firstImport = latestImport();

        BudgetSummaryDto firstSummary = readJson(
            getAuthorized("/api/companies/{companyId}/budget/summary", accessToken, COMPANY_ID),
            BudgetSummaryDto.class
        );
        CashflowSummaryDto firstCashflow = readJson(
            getAuthorized("/api/companies/{companyId}/budget/cashflow", accessToken, COMPANY_ID),
            CashflowSummaryDto.class
        );
        BudgetLongInsightsDto firstInsights = readJson(
            getAuthorized("/api/companies/{companyId}/budget/long/insights", accessToken, COMPANY_ID),
            BudgetLongInsightsDto.class
        );
        BudgetWorkflowDto workflow = readJson(
            getAuthorized("/api/companies/{companyId}/budget/workflow", accessToken, COMPANY_ID),
            BudgetWorkflowDto.class
        );

        assertThat(firstSummary.sourceImportId()).isEqualTo(firstImport.getId());
        assertThat(firstCashflow.sourceImportId()).isEqualTo(firstImport.getId());
        assertThat(firstInsights.sourceImportId()).isEqualTo(firstImport.getId());
        assertThat(firstSummary.analysisVersion()).isEqualTo(firstCashflow.analysisVersion());
        assertThat(firstSummary.analysisVersion()).isEqualTo(firstInsights.analysisVersion());

        assertThat(firstSummary.totalIncome()).isEqualByComparingTo(EXPECTED_INCOME);
        assertThat(firstSummary.totalExpense()).isCloseTo(EXPECTED_OPEX, within(new BigDecimal("0.01")));
        assertThat(firstSummary.totalMargin()).isCloseTo(EXPECTED_EBITDA, within(new BigDecimal("0.01")));
        assertThat(firstSummary.totalCapex()).isEqualByComparingTo(EXPECTED_CAPEX);
        assertThat(firstSummary.totalDepreciation()).isEqualByComparingTo(EXPECTED_DEPRECIATION);
        assertThat(firstSummary.totalEbit()).isCloseTo(EXPECTED_EBIT, within(new BigDecimal("0.01")));
        assertThat(firstSummary.financialResult()).isEqualByComparingTo(EXPECTED_FINANCIAL);
        assertThat(firstSummary.netResult()).isCloseTo(EXPECTED_NET, within(new BigDecimal("0.01")));
        assertThat(firstCashflow.endingBalance()).isEqualByComparingTo(EXPECTED_CLOSING);
        assertThat(firstSummary.months()).extracting(month -> month.income().setScale(2)).containsExactlyElementsOf(EXPECTED_PNL_INCOME_BY_MONTH);
        assertThat(firstSummary.months()).extracting(month -> month.expense().setScale(2)).containsExactlyElementsOf(EXPECTED_PNL_OPEX_BY_MONTH);

        assertThat(workflow.sourcePresent()).isTrue();
        assertThat(workflow.structureValidated()).isTrue();
        assertThat(workflow.annualInsightsReady()).isTrue();
        assertThat(workflow.plannedCashflowReady()).isTrue();
        assertThat(workflow.sourceFilename()).isEqualTo(firstImport.getFilename());
        assertThat(workflow.plannedMonthsAvailable()).isEqualTo(12);

        assertDriverDtoIsCanonical(firstInsights.topDrivers());
        assertThat(firstInsights.topDrivers()).extracting(BudgetItemInsightDto::canonicalIdentity).doesNotHaveDuplicates();
        assertThat(firstInsights.topDrivers()).filteredOn(item -> "607".equals(item.code())).hasSize(1);
        assertThat(firstInsights.topDrivers()).filteredOn(item -> "621".equals(item.code())).hasSize(1);
        assertThat(firstInsights.topDrivers()).filteredOn(item -> "628".equals(item.code())).hasSize(1);
        assertThat(firstInsights.topDrivers()).noneMatch(item -> forbiddenDriverLabels().contains(normalizeLabel(item.label())));
        assertThat(firstInsights.zeroHeavyItems()).noneMatch(item -> "602-0".equals(item.code()));

        byte[] normalizedBytes = universalImportFileService.normalizedCsv(COMPANY_ID, firstImport.getId());
        BudgetLongNormalizer.Result longResult = BudgetLongNormalizer.normalizeToLongCsv(
            normalizedBytes,
            String.valueOf(COMPANY_ID),
            50_000,
            50
        );
        assertThat(longResult.requiresConfirmation()).isFalse();
        BudgetService.CanonicalBudgetAnalysis analysis = budgetService.parseCanonicalBudgetAnalysis(
            longResult.longCsvBytes(),
            firstImport.getFilename(),
            null
        );

        assertThat(analysis.diagnostics().revenueTotal()).isCloseTo(EXPECTED_REVENUE, within(new BigDecimal("0.01")));
        assertThat(analysis.diagnostics().opexTotal()).isCloseTo(EXPECTED_OPEX, within(new BigDecimal("0.01")));
        assertThat(analysis.diagnostics().ebitdaTotal()).isCloseTo(EXPECTED_EBITDA, within(new BigDecimal("0.01")));
        assertThat(analysis.diagnostics().ebitTotal()).isCloseTo(EXPECTED_EBIT, within(new BigDecimal("0.01")));
        assertThat(analysis.diagnostics().financialResultTotal()).isEqualByComparingTo(EXPECTED_FINANCIAL);
        assertThat(analysis.diagnostics().netResultTotal()).isCloseTo(EXPECTED_NET, within(new BigDecimal("0.01")));
        assertThat(analysis.diagnostics().endingBalanceTotal()).isEqualByComparingTo(EXPECTED_CLOSING);
        assertThat(analysis.diagnostics().aggregateRowsExcluded()).isGreaterThan(0);
        assertThat(analysis.diagnostics().duplicateRowsExcluded()).isGreaterThan(0);
        assertThat(analysis.diagnostics().noActivityRowsExcluded()).isGreaterThanOrEqualTo(12);

        assertThat(analysis.rowAudits())
            .filteredOn(BudgetService.RowAudit::includedInDrivers)
            .allMatch(row -> "DETAIL".equals(row.rowType()))
            .allMatch(row -> "P_AND_L".equals(row.sectionKind()))
            .allMatch(row -> !"REVIEW".equals(row.mappingStatus()));
        assertThat(analysis.rowAudits())
            .filteredOn(row -> "681".equals(row.accountingCode()))
            .isNotEmpty()
            .allMatch(row -> "DEPRECIATION_AMORTIZATION".equals(row.financialNature()))
            .allMatch(row -> "P_AND_L".equals(row.sectionKind()))
            .allMatch(BudgetService.RowAudit::includedInPnL)
            .allMatch(row -> !row.includedInCashflow())
            .allMatch(row -> !row.includedInDrivers());
        assertThat(analysis.rowAudits())
            .filteredOn(row -> "602-0".equals(row.accountingCode()))
            .allMatch(row -> "EXCLUDED_NO_ACTIVITY".equals(row.exclusionReason()))
            .allMatch(row -> !row.includedInDrivers());
        assertThat(analysis.rowAudits())
            .filteredOn(row -> "COBROS CLIENTES".equalsIgnoreCase(row.originalLabel()))
            .isNotEmpty()
            .allMatch(row -> "CASHFLOW".equals(row.sectionKind()))
            .allMatch(row -> "CASH_INFLOW".equals(row.cashflowNature()))
            .allMatch(row -> !row.includedInPnL())
            .allMatch(row -> row.includedInCashflow())
            .allMatch(row -> !row.includedInDrivers());
        assertThat(analysis.rowAudits())
            .filteredOn(row -> "607".equals(row.accountingCode()))
            .filteredOn(row -> "P_AND_L".equals(row.sectionKind()))
            .hasSizeGreaterThanOrEqualTo(1)
            .allMatch(BudgetService.RowAudit::includedInPnL);
        assertThat(analysis.rowAudits())
            .filteredOn(row -> "607".equals(row.accountingCode()))
            .filteredOn(row -> "CASHFLOW".equals(row.sectionKind()))
            .isNotEmpty()
            .allMatch(row -> !row.includedInPnL())
            .allMatch(row -> !row.includedInDrivers())
            .allMatch(BudgetService.RowAudit::includedInCashflow);
        assertThat(analysis.rowAudits())
            .filteredOn(row -> "628".equals(row.accountingCode()))
            .filteredOn(row -> "P_AND_L".equals(row.sectionKind()) && row.includedInDrivers())
            .hasSize(12)
            .allMatch(BudgetService.RowAudit::includedInPnL)
            .allMatch(BudgetService.RowAudit::includedInDrivers);
        assertThat(driverAmountFor(firstInsights.topDrivers(), "628")).isEqualByComparingTo("36000.00");
        assertThat(analysis.rowAudits())
            .filteredOn(row -> forbiddenDriverLabels().contains(normalizeLabel(row.originalLabel())))
            .allMatch(row -> !row.includedInDrivers());

        BudgetService.BudgetPdfBundle bundle = budgetService.latestBudgetPdfBundle(COMPANY_ID);
        assertThat(bundle.summary().analysisVersion()).isEqualTo(firstSummary.analysisVersion());
        assertThat(bundle.longInsights().analysisVersion()).isEqualTo(firstSummary.analysisVersion());
        assertThat(bundle.summary().sourceImportId()).isEqualTo(firstSummary.sourceImportId());

        byte[] firstPdf = readPdf(getAuthorized("/api/companies/{companyId}/budget/report.pdf", accessToken, COMPANY_ID));
        assertThat(firstPdf).isNotEmpty();
        assertThat(new String(Arrays.copyOf(firstPdf, 5), StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        uploadAnnualWorkbook(accessToken, annualWorkbook());
        UniversalImport secondImport = latestImport();
        BudgetSummaryDto secondSummary = readJson(
            getAuthorized("/api/companies/{companyId}/budget/summary", accessToken, COMPANY_ID),
            BudgetSummaryDto.class
        );
        BudgetLongInsightsDto secondInsights = readJson(
            getAuthorized("/api/companies/{companyId}/budget/long/insights", accessToken, COMPANY_ID),
            BudgetLongInsightsDto.class
        );

        assertThat(secondImport.getId()).isNotEqualTo(firstImport.getId());
        assertThat(secondSummary.sourceImportId()).isEqualTo(secondImport.getId());
        assertThat(secondInsights.sourceImportId()).isEqualTo(secondImport.getId());
        assertThat(secondSummary.analysisVersion()).isEqualTo(secondInsights.analysisVersion());
        assertThat(secondSummary.analysisVersion()).isNotEqualTo(firstSummary.analysisVersion());
        assertThat(secondSummary.totalIncome()).isEqualByComparingTo(EXPECTED_INCOME);
        assertThat(secondSummary.totalExpense()).isCloseTo(EXPECTED_OPEX, within(new BigDecimal("0.01")));
        assertThat(secondSummary.netResult()).isCloseTo(EXPECTED_NET, within(new BigDecimal("0.01")));
    }

    @Test
    void synthetic_workbook_keeps_cashflow_out_of_pnl_and_drivers() throws Exception {
        String accessToken = login(USER_EMAIL, USER_PASSWORD).body().get("accessToken").toString();

        uploadAnnualWorkbook(accessToken, annualWorkbook());
        UniversalImport imported = latestImport();

        BudgetSummaryDto summary = readJson(
            getAuthorized("/api/companies/{companyId}/budget/summary", accessToken, COMPANY_ID),
            BudgetSummaryDto.class
        );
        CashflowSummaryDto cashflow = readJson(
            getAuthorized("/api/companies/{companyId}/budget/cashflow", accessToken, COMPANY_ID),
            CashflowSummaryDto.class
        );
        BudgetLongInsightsDto insights = readJson(
            getAuthorized("/api/companies/{companyId}/budget/long/insights", accessToken, COMPANY_ID),
            BudgetLongInsightsDto.class
        );

        assertThat(summary.sourceImportId()).isEqualTo(imported.getId());
        assertThat(cashflow.sourceImportId()).isEqualTo(imported.getId());
        assertThat(insights.sourceImportId()).isEqualTo(imported.getId());

        byte[] normalizedBytes = universalImportFileService.normalizedCsv(COMPANY_ID, imported.getId());
        BudgetLongNormalizer.Result longResult = BudgetLongNormalizer.normalizeToLongCsv(
            normalizedBytes,
            String.valueOf(COMPANY_ID),
            50_000,
            50
        );
        assertThat(longResult.requiresConfirmation()).isFalse();

        BudgetService.CanonicalBudgetAnalysis analysis = budgetService.parseCanonicalBudgetAnalysis(
            longResult.longCsvBytes(),
            imported.getFilename(),
            null
        );
        String financialSeries = analysis.source() == null ? "no-source" : analysis.source().plannedFinancialResultByMonth().toString();
        String longFinancialRows = debugLongFinancialRows(longResult.longCsvBytes());
        String includedFinancialAudit = debugIncludedFinancialAudit(analysis.rowAudits());
        String auditSlice = debugAuditSlice(
            analysis.rowAudits(),
            Set.of("607", "621", "628", "640", "642", "66", "67", "76"),
            List.of("gastos explotacion", "otros gastos explotacion", "personal", "seg social", "financier", "amort", "resultado financiero")
        );

        assertThat(summary.totalIncome()).isCloseTo(EXPECTED_INCOME, within(new BigDecimal("0.02")));
        assertThat(summary.totalExpense())
            .withFailMessage("%s%n%s", analysis.diagnostics(), auditSlice)
            .isCloseTo(EXPECTED_OPEX, within(new BigDecimal("0.01")));
        assertThat(summary.totalMargin()).isCloseTo(EXPECTED_EBITDA, within(new BigDecimal("0.01")));
        assertThat(summary.totalCapex()).isEqualByComparingTo(EXPECTED_CAPEX);
        assertThat(summary.totalDepreciation()).isEqualByComparingTo(EXPECTED_DEPRECIATION);
        assertThat(summary.totalEbit()).isCloseTo(EXPECTED_EBIT, within(new BigDecimal("0.01")));
        assertThat(summary.financialResult())
            .withFailMessage(
                "%s%nfinancial-series=%s%nlong-financial-rows=%s%nincluded-financial=%s%n%s",
                analysis.diagnostics(),
                financialSeries,
                longFinancialRows,
                includedFinancialAudit,
                auditSlice
            )
            .isEqualByComparingTo(EXPECTED_FINANCIAL);
        assertThat(summary.netResult()).isCloseTo(EXPECTED_NET, within(new BigDecimal("0.01")));
        assertThat(cashflow.endingBalance()).isEqualByComparingTo(EXPECTED_CLOSING);
        assertMonthSeriesClose(
            summary.months().stream().map(month -> month.income().setScale(2)).toList(),
            EXPECTED_PNL_INCOME_BY_MONTH,
            "income"
        );
        assertMonthSeriesClose(
            summary.months().stream().map(month -> month.expense().setScale(2)).toList(),
            EXPECTED_PNL_OPEX_BY_MONTH,
            "expense"
        );

        assertDriverDtoIsCanonical(insights.topDrivers());
        assertThat(insights.topDrivers()).extracting(BudgetItemInsightDto::canonicalIdentity).doesNotHaveDuplicates();
        assertThat(insights.topDrivers()).filteredOn(item -> "607".equals(item.code())).hasSize(1);
        assertThat(insights.topDrivers()).filteredOn(item -> "628".equals(item.code())).hasSize(1);
        assertThat(insights.topDrivers()).noneMatch(item -> forbiddenDriverLabels().contains(normalizeLabel(item.label())));
        assertThat(insights.accountingAdjustments()).anyMatch(item -> "OPERATING_ADJUSTMENT".equals(item.financialNature()));
        assertThat(insights.accountingAdjustments()).anyMatch(item -> "INVENTORY_VARIATION".equals(item.semanticKind()));
        assertThat(insights.topDrivers()).noneMatch(item -> "INVENTORY_VARIATION".equals(item.semanticKind()));
        assertThat(insights.zeroHeavyItems()).noneMatch(item -> "INVENTORY_VARIATION".equals(item.semanticKind()));

        BudgetItemInsightDto supplies = insights.topDrivers().stream().filter(item -> "628".equals(item.code())).findFirst().orElseThrow();
        BudgetItemDetailDto suppliesDetail = readJson(
            getAuthorized(
                "/api/companies/{companyId}/budget/long/detail?canonicalRowId={canonicalRowId}",
                accessToken,
                COMPANY_ID,
                supplies.canonicalRowId()
            ),
            BudgetItemDetailDto.class
        );
        assertThat(suppliesDetail.code()).isEqualTo("628");
        assertThat(suppliesDetail.computedAnnualTotal()).isEqualByComparingTo("36000.00");
        assertThat(suppliesDetail.sourceRowCount()).isGreaterThan(0);
        assertThat(suppliesDetail.months()).hasSize(12);

        BudgetItemInsightDto payroll = insights.topDrivers().stream().filter(item -> "640".equals(item.code())).findFirst().orElseThrow();
        BudgetItemDetailDto payrollDetail = readJson(
            getAuthorized(
                "/api/companies/{companyId}/budget/long/detail?canonicalRowId={canonicalRowId}",
                accessToken,
                COMPANY_ID,
                payroll.canonicalRowId()
            ),
            BudgetItemDetailDto.class
        );
        assertThat(payrollDetail.code()).isEqualTo("640");
        assertThat(payrollDetail.computedAnnualTotal()).isEqualByComparingTo("240000.00");
        assertThat(payrollDetail.months()).hasSize(12);

        BudgetItemInsightDto serviceIncome = insights.topDrivers().stream().filter(item -> "700".equals(item.code())).findFirst().orElseThrow();
        BudgetItemDetailDto serviceIncomeDetail = readJson(
            getAuthorized(
                "/api/companies/{companyId}/budget/long/detail?canonicalRowId={canonicalRowId}",
                accessToken,
                COMPANY_ID,
                serviceIncome.canonicalRowId()
            ),
            BudgetItemDetailDto.class
        );
        assertThat(serviceIncomeDetail.code()).isEqualTo("700");
        assertThat(serviceIncomeDetail.computedAnnualTotal()).isEqualByComparingTo("600000.00");
        assertThat(serviceIncomeDetail.months()).hasSize(12);

        BudgetItemInsightDto inventoryVariation = insights.accountingAdjustments().stream()
            .filter(item -> "INVENTORY_VARIATION".equals(item.semanticKind()))
            .findFirst()
            .orElseThrow();
        BudgetItemDetailDto inventoryDetail = readJson(
            getAuthorized(
                "/api/companies/{companyId}/budget/long/detail?canonicalRowId={canonicalRowId}",
                accessToken,
                COMPANY_ID,
                inventoryVariation.canonicalRowId()
            ),
            BudgetItemDetailDto.class
        );
        assertThat(inventoryDetail.semanticKind()).isEqualTo("INVENTORY_VARIATION");
        assertThat(inventoryDetail.financialNature()).isEqualTo("OPERATING_ADJUSTMENT");
        assertThat(inventoryDetail.computedAnnualTotal()).isEqualByComparingTo("60000.00");
        assertThat(inventoryDetail.months()).hasSize(12);

        assertThat(analysis.diagnostics().revenueTotal()).isCloseTo(EXPECTED_REVENUE, within(new BigDecimal("0.01")));
        assertThat(analysis.diagnostics().opexTotal()).isCloseTo(EXPECTED_OPEX, within(new BigDecimal("0.01")));
        assertThat(analysis.diagnostics().ebitdaTotal()).isCloseTo(EXPECTED_EBITDA, within(new BigDecimal("0.01")));
        assertThat(analysis.diagnostics().ebitTotal()).isCloseTo(EXPECTED_EBIT, within(new BigDecimal("0.01")));
        assertThat(analysis.diagnostics().financialResultTotal()).isEqualByComparingTo(EXPECTED_FINANCIAL);
        assertThat(analysis.diagnostics().netResultTotal()).isCloseTo(EXPECTED_NET, within(new BigDecimal("0.01")));
        assertThat(analysis.diagnostics().endingBalanceTotal()).isEqualByComparingTo(EXPECTED_CLOSING);

        assertThat(analysis.rowAudits())
            .filteredOn(row -> "COBROS CLIENTES".equalsIgnoreCase(row.originalLabel()))
            .isNotEmpty()
            .allMatch(row -> "CASHFLOW".equals(row.sectionKind()))
            .allMatch(row -> "CASH_INFLOW".equals(row.cashflowNature()))
            .allMatch(row -> !row.includedInPnL())
            .allMatch(row -> row.includedInCashflow())
            .allMatch(row -> !row.includedInDrivers());
        assertThat(analysis.rowAudits())
            .filteredOn(row -> "607".equals(row.accountingCode()))
            .filteredOn(row -> "CASHFLOW".equals(row.sectionKind()))
            .isNotEmpty()
            .allMatch(row -> !row.includedInPnL())
            .allMatch(row -> !row.includedInDrivers());
        assertThat(analysis.rowAudits())
            .filteredOn(row -> "681".equals(row.accountingCode()))
            .isNotEmpty()
            .allMatch(row -> "DEPRECIATION_AMORTIZATION".equals(row.financialNature()))
            .allMatch(row -> "P_AND_L".equals(row.sectionKind()))
            .allMatch(BudgetService.RowAudit::includedInPnL)
            .allMatch(row -> !row.includedInCashflow())
            .allMatch(row -> !row.includedInDrivers());
    }

    @Test
    void desavio_http_import_keeps_cashflow_out_of_pnl_and_drivers() throws Exception {
        String accessToken = login(USER_EMAIL, USER_PASSWORD).body().get("accessToken").toString();

        uploadAnnualWorkbook(accessToken, desavioAnnualWorkbook());
        UniversalImport imported = latestImport();

        BudgetAnalysisBundleDto bundle = readJson(
            getAuthorized("/api/companies/{companyId}/budget/analysis", accessToken, COMPANY_ID),
            BudgetAnalysisBundleDto.class
        );
        BudgetSummaryDto summary = readJson(
            getAuthorized("/api/companies/{companyId}/budget/summary", accessToken, COMPANY_ID),
            BudgetSummaryDto.class
        );
        CashflowSummaryDto cashflow = readJson(
            getAuthorized("/api/companies/{companyId}/budget/cashflow", accessToken, COMPANY_ID),
            CashflowSummaryDto.class
        );
        BudgetLongInsightsDto insights = readJson(
            getAuthorized("/api/companies/{companyId}/budget/long/insights", accessToken, COMPANY_ID),
            BudgetLongInsightsDto.class
        );

        byte[] normalizedBytes = universalImportFileService.normalizedCsv(COMPANY_ID, imported.getId());
        BudgetLongNormalizer.Result longResult = BudgetLongNormalizer.normalizeToLongCsv(
            normalizedBytes,
            String.valueOf(COMPANY_ID),
            50_000,
            50
        );
        assertThat(longResult.requiresConfirmation()).isFalse();

        BudgetService.CanonicalBudgetAnalysis analysis = budgetService.parseCanonicalBudgetAnalysis(
            longResult.longCsvBytes(),
            imported.getFilename(),
            null
        );
        String trackedRowsTable = debugTrackedAnnualRowsTable(analysis.rowAudits(), DESAVIO_TRACKED_LABELS);

        assertThat(bundle.summary().sourceImportId()).isEqualTo(imported.getId());
        assertThat(bundle.cashflow().sourceImportId()).isEqualTo(imported.getId());
        assertThat(bundle.insights().sourceImportId()).isEqualTo(imported.getId());
        assertThat(bundle.summary().analysisVersion()).isEqualTo(summary.analysisVersion());
        assertThat(bundle.cashflow().analysisVersion()).isEqualTo(cashflow.analysisVersion());
        assertThat(bundle.insights().analysisVersion()).isEqualTo(insights.analysisVersion());

        assertThat(summary.totalIncome())
            .withFailMessage("%s%n%s", analysis.diagnostics(), trackedRowsTable)
            .isEqualByComparingTo(EXPECTED_DESAVIO_INCOME);
        assertThat(summary.totalExpense())
            .withFailMessage("%s%n%s", analysis.diagnostics(), trackedRowsTable)
            .isEqualByComparingTo(EXPECTED_DESAVIO_OPEX);
        assertThat(summary.totalMargin())
            .withFailMessage("%s%n%s", analysis.diagnostics(), trackedRowsTable)
            .isEqualByComparingTo(EXPECTED_DESAVIO_EBITDA);
        assertThat(summary.totalEbit())
            .withFailMessage("%s%n%s", analysis.diagnostics(), trackedRowsTable)
            .isEqualByComparingTo(EXPECTED_DESAVIO_EBIT);
        assertThat(summary.netResult())
            .withFailMessage("%s%n%s", analysis.diagnostics(), trackedRowsTable)
            .isEqualByComparingTo(EXPECTED_DESAVIO_NET);
        assertThat(cashflow.endingBalance())
            .withFailMessage("%s%n%s", analysis.diagnostics(), trackedRowsTable)
            .isEqualByComparingTo(EXPECTED_DESAVIO_CLOSING);

        assertThat(analysis.rowAudits())
            .filteredOn(row -> "CASHFLOW".equals(row.sectionKind()))
            .allMatch(row -> !row.includedInPnL())
            .allMatch(row -> !row.includedInDrivers());

        assertThat(insights.topDrivers()).noneMatch(item -> Set.of(
            "cobros de ventas",
            "pagos de personal",
            "pagos de alquiler luz y agua",
            "compra de neveras y equipos",
            "prestamo recibido",
            "cash neto del mes",
            "saldo final del mes"
        ).contains(normalizeLabel(item.label())));
    }

    private void assertDriverDtoIsCanonical(List<BudgetItemInsightDto> items) {
        assertThat(items).allSatisfy(item -> {
            assertThat(item.rowType()).isEqualTo("DETAIL");
            assertThat(item.sectionKind()).isEqualTo("P_AND_L");
            assertThat(item.mappingStatus()).isNotEqualTo("REVIEW");
            assertThat(item.sourceRow()).isNotNull();
            assertThat(item.blockId()).isNotBlank();
            assertThat(item.exclusionReason()).isNull();
            assertThat(item.canonicalIdentity()).isNotBlank();
        });
    }

    private void assertMonthSeriesClose(List<BigDecimal> actual, List<BigDecimal> expected, String label) {
        assertThat(actual).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            assertThat(actual.get(i))
                .as("%s month index %s", label, i)
                .isCloseTo(expected.get(i), within(new BigDecimal("0.01")));
        }
    }

    private static String debugAuditSlice(List<BudgetService.RowAudit> audits,
                                          Set<String> codes,
                                          List<String> labelFragments) {
        StringBuilder out = new StringBuilder("=== ANNUAL AUDIT SLICE ===\n");
        for (BudgetService.RowAudit audit : audits) {
            String code = audit.accountingCode() == null ? "" : audit.accountingCode().trim().toUpperCase(Locale.ROOT);
            String label = normalizeLabel(audit.originalLabel());
            boolean codeMatch = codes.stream().anyMatch(code::startsWith);
            boolean labelMatch = labelFragments.stream().map(BudgetAnnualWorkflowEndpointIntegrationTest::normalizeLabel).anyMatch(label::contains);
            if (!codeMatch && !labelMatch) {
                continue;
            }
            out.append("%s | row=%s | code=%s | label=%s | section=%s | fin=%s | cash=%s | includedPnL=%s | includedCash=%s | driver=%s | exclusion=%s%n"
                .formatted(
                    audit.sourceRow(),
                    audit.rowType(),
                    audit.accountingCode(),
                    audit.originalLabel(),
                    audit.sectionKind(),
                    audit.financialNature(),
                    audit.cashflowNature(),
                    audit.includedInPnL(),
                    audit.includedInCashflow(),
                    audit.includedInDrivers(),
                    audit.exclusionReason()
                ));
            out.append("   month=%s | planned=%s | agg=%s%n"
                .formatted(
                    audit.monthKey(),
                    audit.plannedAmount(),
                    audit.aggregationPolicy()
                ));
        }
        return out.toString();
    }

    private static String debugIncludedFinancialAudit(List<BudgetService.RowAudit> audits) {
        return audits.stream()
            .filter(BudgetService.RowAudit::includedInPnL)
            .filter(row -> "FINANCING".equalsIgnoreCase(row.financialNature()))
            .map(row -> "%s | code=%s | label=%s | month=%s | planned=%s | section=%s | row=%s | sourceRow=%s | block=%s"
                .formatted(
                    row.canonicalIdentity(),
                    row.accountingCode(),
                    row.originalLabel(),
                    row.monthKey(),
                    row.plannedAmount(),
                    row.sectionKind(),
                    row.rowType(),
                    row.sourceRow(),
                    row.blockId()
                ))
            .collect(Collectors.joining("\n"));
    }

    private void uploadAnnualWorkbook(String accessToken, MockMultipartFile file) throws Exception {
        mockMvc.perform(
                multipart("/api/companies/{companyId}/universal/imports", COMPANY_ID)
                    .file(file)
                    .header("Authorization", "Bearer " + accessToken)
            )
            .andExpect(status().isOk());
    }

    private MvcResult getAuthorized(String urlTemplate, String accessToken, Object... uriVars) throws Exception {
        return mockMvc.perform(
                get(urlTemplate, uriVars)
                    .header("Authorization", "Bearer " + accessToken)
            )
            .andExpect(status().isOk())
            .andReturn();
    }

    private <T> T readJson(MvcResult result, Class<T> type) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), type);
    }

    private byte[] readPdf(MvcResult result) {
        assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
        return result.getResponse().getContentAsByteArray();
    }

    private UniversalImport latestImport() {
        return universalImportRepository.findFirstByCompanyIdOrderByCreatedAtDesc(COMPANY_ID).orElseThrow();
    }

    private LoginResult login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
            .andExpect(status().isOk())
            .andReturn();

        Map<String, Object> body = objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        return new LoginResult(body, setCookie, cookiePair(setCookie, "enterpriseiq_refresh"));
    }

    private static String cookiePair(String setCookie, String cookieName) {
        assertThat(setCookie).isNotNull();
        String prefix = cookieName + "=";
        int start = setCookie.indexOf(prefix);
        assertThat(start).isGreaterThanOrEqualTo(0);
        int valueStart = start + prefix.length();
        int end = setCookie.indexOf(';', valueStart);
        if (end < 0) {
            end = setCookie.length();
        }
        return cookieName + "=" + setCookie.substring(valueStart, end);
    }

    @SuppressWarnings("unused")
    private static Cookie cookieFromPair(String cookiePair, String cookieName) {
        String prefix = cookieName + "=";
        int start = cookiePair.indexOf(prefix);
        assertThat(start).isGreaterThanOrEqualTo(0);
        String value = cookiePair.substring(start + prefix.length());
        return new Cookie(cookieName, value);
    }

    private static MockMultipartFile annualWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Escenario 2025 Normal");
            sheet.createRow(0).createCell(0).setCellValue("PRESUPUESTO ANUAL SINTETICO");
            sheet.createRow(1);

            var header = sheet.createRow(2);
            header.createCell(0).setCellValue("Concepto");
            List<String> monthHeaders = List.of("Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre");
            for (int i = 0; i < monthHeaders.size(); i++) {
                header.createCell(i + 1).setCellValue(monthHeaders.get(i));
            }

            int rowIndex = 3;
            for (RowData rowData : annualRows()) {
                var row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(rowData.label());
                for (int i = 0; i < 12; i++) {
                    row.createCell(i + 1).setCellValue(rowData.months()[i]);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return new MockMultipartFile(
                "file",
                "synthetic-annual-budget.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                out.toByteArray()
            );
        }
    }

    private static MockMultipartFile desavioAnnualWorkbook() throws IOException {
        String resourcePath = "/fixtures/budget/small-retail-annual-accounts-2026.xlsx";
        try (InputStream input = BudgetAnnualWorkflowEndpointIntegrationTest.class.getResourceAsStream(resourcePath)) {
            assertThat(input).as(resourcePath).isNotNull();
            return new MockMultipartFile(
                "file",
                "small-retail-annual-accounts-2026.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                StreamUtils.copyToByteArray(input)
            );
        }
    }

    private static void debugImportedRows(UniversalRowsDto dto, int fromRow, int toRow) {
        if (dto == null || dto.rows() == null || dto.headers() == null) {
            return;
        }
        int col1 = dto.headers().indexOf("col_1");
        int total = dto.headers().indexOf("TOTAL PRESUPUESTADO");
        int enero = dto.headers().indexOf("ENERO");
        int febrero = dto.headers().indexOf("FEBRERO");
        int marzo = dto.headers().indexOf("MARZO");
        int abril = dto.headers().indexOf("ABRIL");
        for (int i = Math.max(0, fromRow - 1); i < dto.rows().size() && i < toRow; i++) {
            List<String> row = dto.rows().get(i);
            System.out.println("IMPORTED ROW " + (i + 1)
                + " col_1=" + safeCell(row, col1)
                + " total=" + safeCell(row, total)
                + " ene=" + safeCell(row, enero)
                + " feb=" + safeCell(row, febrero)
                + " mar=" + safeCell(row, marzo)
                + " abr=" + safeCell(row, abril));
        }
    }

    private static String debugLongFinancialRows(byte[] longCsvBytes) throws IOException {
        StringBuilder out = new StringBuilder();
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .build()
            .parse(new java.io.InputStreamReader(new java.io.ByteArrayInputStream(longCsvBytes), StandardCharsets.UTF_8))) {
            for (CSVRecord record : parser) {
                String code = record.get("code");
                if (!Set.of("760", "762", "669").contains(code)) {
                    continue;
                }
                out.append(code)
                    .append("|month=").append(record.get("month_key"))
                    .append("|amount=").append(record.get("budget_amount"))
                    .append("|label=").append(record.get("label"))
                    .append('\n');
            }
        }
        return out.toString();
    }

    private static String debugTrackedAnnualRowsTable(List<BudgetService.RowAudit> audits, List<String> rawLabels) {
        Map<String, BudgetTrackedRow> grouped = new LinkedHashMap<>();
        Set<String> targets = rawLabels.stream()
            .map(BudgetAnnualWorkflowEndpointIntegrationTest::normalizeLabel)
            .collect(Collectors.toSet());
        for (BudgetService.RowAudit audit : audits) {
            String normalized = normalizeLabel(audit.originalLabel());
            if (!targets.contains(normalized)) {
                continue;
            }
            String key = String.join("|",
                safe(audit.originalLabel()),
                safe(audit.sectionKind()),
                safe(audit.rowType()),
                safe(audit.financialNature()),
                safe(audit.cashflowNature()),
                String.valueOf(audit.includedInPnL()),
                String.valueOf(audit.includedInDrivers())
            );
            BudgetTrackedRow row = grouped.computeIfAbsent(
                key,
                ignored -> new BudgetTrackedRow(
                    audit.originalLabel(),
                    audit.sectionKind(),
                    audit.rowType(),
                    audit.financialNature(),
                    audit.cashflowNature(),
                    audit.includedInPnL(),
                    audit.includedInDrivers()
                )
            );
            if (audit.plannedAmount() != null) {
                row.annualTotal = row.annualTotal.add(audit.plannedAmount());
            }
        }

        List<String> lines = new ArrayList<>();
        lines.add("rawLabel | section | rowRole | financialNature | cashflowNature | eligibleForPAndL | eligibleForDrivers | annualTotal");
        grouped.values().forEach(row -> lines.add("%s | %s | %s | %s | %s | %s | %s | %s".formatted(
            row.rawLabel,
            row.section,
            row.rowRole,
            row.financialNature,
            row.cashflowNature,
            row.eligibleForPnL,
            row.eligibleForDrivers,
            row.annualTotal.setScale(2)
        )));
        return String.join(System.lineSeparator(), lines);
    }

    private static String safeCell(List<String> row, int index) {
        if (row == null || index < 0 || index >= row.size()) {
            return "";
        }
        return row.get(index);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class BudgetTrackedRow {
        private final String rawLabel;
        private final String section;
        private final String rowRole;
        private final String financialNature;
        private final String cashflowNature;
        private final boolean eligibleForPnL;
        private final boolean eligibleForDrivers;
        private BigDecimal annualTotal = BigDecimal.ZERO;

        private BudgetTrackedRow(String rawLabel,
                                 String section,
                                 String rowRole,
                                 String financialNature,
                                 String cashflowNature,
                                 boolean eligibleForPnL,
                                 boolean eligibleForDrivers) {
            this.rawLabel = rawLabel;
            this.section = section;
            this.rowRole = rowRole;
            this.financialNature = financialNature;
            this.cashflowNature = cashflowNature;
            this.eligibleForPnL = eligibleForPnL;
            this.eligibleForDrivers = eligibleForDrivers;
        }
    }

    private static List<RowData> annualRows() {
        return List.of(
            row("Ingresos de explotacion", 55000, 55000, 55000, 55000, 55000, 55000, 55000, 55000, 55000, 55000, 55000, 55000),
            row("700 Servicios profesionales", 50000, 50000, 50000, 50000, 50000, 50000, 50000, 50000, 50000, 50000, 50000, 50000),
            row("710 Variacion de existencias", 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000),
            row("GASTOS EXPLOTACION", 38000, 38000, 38000, 38000, 38000, 38000, 38000, 38000, 38000, 38000, 38000, 38000),
            row("OTROS GASTOS EXPLOTACION", 18000, 18000, 18000, 18000, 18000, 18000, 18000, 18000, 18000, 18000, 18000, 18000),
            row("3. Gastos personal", 20000, 20000, 20000, 20000, 20000, 20000, 20000, 20000, 20000, 20000, 20000, 20000),
            row("640-642 Personal y seg social", 20000, 20000, 20000, 20000, 20000, 20000, 20000, 20000, 20000, 20000, 20000, 20000),
            row("607 Servicios externos", 10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000),
            row("621 Arrendamientos y canones", 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000),
            row("628 Suministros", 3000, 3000, 3000, 3000, 3000, 3000, 3000, 3000, 3000, 3000, 3000, 3000),
            row("640 Sueldos y salarios", 20000, 20000, 20000, 20000, 20000, 20000, 20000, 20000, 20000, 20000, 20000, 20000),
            row("TOTAL COMPRAS", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 120000),
            row("TOTAL GASTOS", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 456000),
            row("TOTAL COMPRAS MAS GASTOS", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 456000),
            row("602-0 Compras otros aprovisionamientos", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            row("MARGEN BRUTO", 45000, 45000, 45000, 45000, 45000, 45000, 45000, 45000, 45000, 45000, 45000, 45000),
            row("EBITDA", 17000, 17000, 17000, 17000, 17000, 17000, 17000, 17000, 17000, 17000, 17000, 17000),
            row("EBIT", 15000, 15000, 15000, 15000, 15000, 15000, 15000, 15000, 15000, 15000, 15000, 15000),
            row("Resultado financiero", 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000),
            row("Beneficio neto", 16000, 16000, 16000, 16000, 16000, 16000, 16000, 16000, 16000, 16000, 16000, 16000),
            row("218 Inmovilizado y CAPEX", 4000, 4000, 4000, 4000, 4000, 4000, 4000, 4000, 4000, 4000, 4000, 4000),
            row("681 Amortizaciones", 2000, 2000, 2000, 2000, 2000, 2000, 2000, 2000, 2000, 2000, 2000, 2000),
            row("Tesoreria", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            row("Saldo inicial", 100000, 113000, 126000, 139000, 152000, 165000, 178000, 191000, 204000, 217000, 230000, 243000),
            row("COBROS CLIENTES", 55000, 55000, 55000, 55000, 55000, 55000, 55000, 55000, 55000, 55000, 55000, 55000),
            row("Saldo mensual", 13000, 13000, 13000, 13000, 13000, 13000, 13000, 13000, 13000, 13000, 13000, 13000),
            row("Saldo acumulado de tesoreria", 113000, 126000, 139000, 152000, 165000, 178000, 191000, 204000, 217000, 230000, 243000, 256000),
            row("607 Servicios externos", 10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000),
            row("621 Arrendamientos y canones", 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000),
            row("628 Suministros", 3000, 3000, 3000, 3000, 3000, 3000, 3000, 3000, 3000, 3000, 3000, 3000),
            row("Pagos del periodo", 42000, 42000, 42000, 42000, 42000, 42000, 42000, 42000, 42000, 42000, 42000, 42000),
            row("Saldo final", 113000, 126000, 139000, 152000, 165000, 178000, 191000, 204000, 217000, 230000, 243000, 256000)
        );
    }

    private static RowData row(String label, double... months) {
        return new RowData(label, months);
    }

    private static List<String> forbiddenDriverLabels() {
        return List.of(
            normalizeLabel("COBROS CLIENTES"),
            normalizeLabel("OTROS GASTOS EXPLOTACION"),
            normalizeLabel("GASTOS EXPLOTACION"),
            normalizeLabel("3. Gastos personal"),
            normalizeLabel("640-642 Personal y seg social"),
            normalizeLabel("TOTAL COMPRAS"),
            normalizeLabel("TOTAL GASTOS"),
            normalizeLabel("EBITDA"),
            normalizeLabel("EBIT"),
            normalizeLabel("BENEFICIO NETO"),
            normalizeLabel("Saldo mensual"),
            normalizeLabel("Saldo acumulado de tesoreria")
        );
    }

    private static String normalizeLabel(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .replaceAll("\\s+", " ")
            .trim()
            .toUpperCase();
    }

    private static BigDecimal driverAmountFor(List<BudgetItemInsightDto> items, String code) {
        return items.stream()
            .filter(item -> code.equals(item.code()))
            .findFirst()
            .map(BudgetItemInsightDto::annualTotal)
            .orElseThrow();
    }

    private record LoginResult(Map<String, Object> body, String refreshCookie, String refreshCookiePair) {}
    private record RowData(String label, double[] months) {}
}

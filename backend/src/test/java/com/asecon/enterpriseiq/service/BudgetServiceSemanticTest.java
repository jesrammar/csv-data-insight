package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.UniversalImport;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BudgetServiceSemanticTest {

    private static final List<String> MONTHS = List.of(
        "ENERO", "FEBRERO", "MARZO", "ABRIL", "MAYO", "JUNIO",
        "JULIO", "AGOSTO", "SEPTIEMBRE", "OCTUBRE", "NOVIEMBRE", "DICIEMBRE"
    );

    @Test
    void keeps_capex_out_of_revenue_and_opex_totals() {
        UniversalImportFileService importFileService = mock(UniversalImportFileService.class);
        BudgetService service = new BudgetService(importFileService);

        UniversalImport imp = new UniversalImport();
        imp.setFilename("annual-plan.csv");
        imp.setCreatedAt(Instant.parse("2026-07-22T10:00:00Z"));

        String csv = ""
            + "Posting_Period,Concept_Label,Account_Code,Tipo_Operacion,PlanAmount,Importe_Real,Forecast_Cierre,Currency\n"
            + "2026-01,New ERP rollout,INV-ERP,CAPEX,10000,9500,9800,EUR\n"
            + "2026-01,Monthly retainers,REV-RET,Revenue,30000,29500,30200,EUR\n"
            + "2026-01,Payroll consulting,EXP-PAY,OPEX,12000,11800,12100,EUR\n";

        when(importFileService.latestAnnualBudget(7L)).thenReturn(Optional.of(imp));
        when(importFileService.normalizedCsv(7L, imp.getId())).thenReturn(csv.getBytes());

        BudgetService.LongBudgetSource source = service.latestLongBudgetSource(7L);

        assertThat(source.plannedIncomeByMonth().get("ENERO")).isEqualByComparingTo("30000");
        assertThat(source.plannedExpenseByMonth().get("ENERO")).isEqualByComparingTo("12000");
        assertThat(source.plannedCapexByMonth().get("ENERO")).isEqualByComparingTo("10000");
        assertThat(source.plannedIncomeByMonth().get("ENERO"))
            .isNotEqualByComparingTo(new BigDecimal("40000"));
    }

    @Test
    void keeps_totals_and_balances_out_of_operating_totals() {
        UniversalImportFileService importFileService = mock(UniversalImportFileService.class);
        BudgetService service = new BudgetService(importFileService);

        UniversalImport imp = new UniversalImport();
        imp.setFilename("canonical-budget.csv");
        imp.setCreatedAt(Instant.parse("2026-07-22T10:00:00Z"));

        String csv = ""
            + "row_type,code,label,semantic_kind,section_kind,month_key,month_label,amount,budget_amount\n"
            + "DETAIL,REV-01,Servicio recurrente,REVENUE,P_AND_L,ENERO,Enero,30000,30000\n"
            + "DETAIL,EXP-01,Equipo, OPEX,P_AND_L,ENERO,Enero,12000,12000\n"
            + "TOTAL,TOT-REV,Total ingresos,REVENUE,P_AND_L,ENERO,Enero,99999,99999\n"
            + "TOTAL,SALDO,Saldo final,CLOSING_BALANCE,CASHFLOW,ENERO,Enero,45000,45000\n";

        when(importFileService.latestAnnualBudget(7L)).thenReturn(Optional.of(imp));
        when(importFileService.normalizedCsv(7L, imp.getId())).thenReturn(csv.getBytes());

        BudgetService.LongBudgetSource source = service.latestLongBudgetSource(7L);

        assertThat(source.plannedIncomeByMonth().get("ENERO")).isEqualByComparingTo("30000");
        assertThat(source.plannedExpenseByMonth().get("ENERO")).isEqualByComparingTo("12000");
        assertThat(source.plannedClosingBalanceByMonth().get("ENERO")).isEqualByComparingTo("45000");
    }

    @Test
    void avoids_double_counting_between_profit_and_loss_and_cashflow_blocks() {
        UniversalImportFileService importFileService = mock(UniversalImportFileService.class);
        BudgetService service = new BudgetService(importFileService);

        UniversalImport imp = new UniversalImport();
        imp.setFilename("hoja-presupuestos-anual.xlsx");
        imp.setCreatedAt(Instant.parse("2026-07-24T08:30:00Z"));

        String csv = ""
            + "row_type,code,label,semantic_kind,section_kind,block_id,source_row,month_key,month_label,amount,budget_amount,mapping_status\n"
            + "DETAIL,700-FACT,Servicios profesionales,REVENUE,P_AND_L,ROW-1,1,DICIEMBRE,Diciembre,600000.00,600000.00,CANONICAL\n"
            + "DETAIL,710-VAR,Variacion de existencias,REVENUE,P_AND_L,ROW-2,2,DICIEMBRE,Diciembre,60000.00,60000.00,CANONICAL\n"
            + "DETAIL,607,Servicios externos,OPEX,P_AND_L,ROW-3,3,DICIEMBRE,Diciembre,120000.00,120000.00,CANONICAL\n"
            + "DETAIL,621,Arrendamientos y canones,OPEX,P_AND_L,ROW-4,4,DICIEMBRE,Diciembre,60000.00,60000.00,CANONICAL\n"
            + "DETAIL,628,Suministros,OPEX,P_AND_L,ROW-5,5,DICIEMBRE,Diciembre,36000.00,36000.00,CANONICAL\n"
            + "DETAIL,640,Sueldos y salarios,OPEX,P_AND_L,ROW-6,6,DICIEMBRE,Diciembre,240000.00,240000.00,CANONICAL\n"
            + "DETAIL,669,Gastos financieros operativos,OPEX,P_AND_L,ROW-7,7,DICIEMBRE,Diciembre,0.00,0.00,CANONICAL\n"
            + "DETAIL,629,Otros gastos de explotacion,OPEX,P_AND_L,ROW-8,8,DICIEMBRE,Diciembre,0.00,0.00,CANONICAL\n"
            + "DETAIL,218,Inmovilizado y CAPEX,CAPEX,P_AND_L,ROW-9,9,DICIEMBRE,Diciembre,48000.00,48000.00,CANONICAL\n"
            + "DETAIL,681,Amortizaciones,DEPRECIATION_AMORTIZATION,P_AND_L,ROW-9B,9,DICIEMBRE,Diciembre,24000.00,24000.00,CANONICAL\n"
            + "DETAIL,761,Resultado financiero,FINANCING,CASHFLOW,ROW-10,10,DICIEMBRE,Diciembre,12000.00,12000.00,CANONICAL\n"
            + "DETAIL,COBROS,Cobros del periodo,CASH_INFLOW,CASHFLOW,ROW-11,11,DICIEMBRE,Diciembre,660000.00,660000.00,CANONICAL\n"
            + "TOTAL,PAGOS,Pagos del periodo,CASH_OUTFLOW,CASHFLOW,ROW-12,12,DICIEMBRE,Diciembre,504000.00,504000.00,CANONICAL\n"
            + "TOTAL,SALDO-INI,Saldo inicial,OPENING_BALANCE,CASHFLOW,ROW-13,13,DICIEMBRE,Diciembre,100000.00,100000.00,CANONICAL\n"
            + "TOTAL,SALDO-FIN,Saldo final,CLOSING_BALANCE,CASHFLOW,ROW-14,14,DICIEMBRE,Diciembre,256000.00,256000.00,CANONICAL\n"
            + "SUBTOTAL,GASTOS,GASTOS EXPLOTACION,OPEX,P_AND_L,ROW-15,15,DICIEMBRE,Diciembre,120000.00,120000.00,CANONICAL\n"
            + "SUBTOTAL,OTROS,OTROS GASTOS EXPLOTACION,OPEX,P_AND_L,ROW-16,16,DICIEMBRE,Diciembre,336000.00,336000.00,CANONICAL\n"
            + "SUBTOTAL,640-642,640-642 Personal y seg social,OPEX,P_AND_L,ROW-17,17,DICIEMBRE,Diciembre,240000.00,240000.00,CANONICAL\n"
            + "DETAIL,607,Servicios externos,CASH_OUTFLOW,CASHFLOW,ROW-18,18,DICIEMBRE,Diciembre,120000.00,120000.00,CANONICAL\n"
            + "DETAIL,621,Arrendamientos y canones,CASH_OUTFLOW,CASHFLOW,ROW-19,19,DICIEMBRE,Diciembre,60000.00,60000.00,CANONICAL\n"
            + "DETAIL,628,Suministros,CASH_OUTFLOW,CASHFLOW,ROW-20,20,DICIEMBRE,Diciembre,36000.00,36000.00,CANONICAL\n"
            + "DETAIL,700-ZERO,Linea estacional cero,REVENUE,P_AND_L,ROW-21,21,ENERO,Enero,0.00,0.00,CANONICAL\n"
            + "DETAIL,700-ZERO,Linea estacional cero,REVENUE,P_AND_L,ROW-21,21,FEBRERO,Febrero,0.00,0.00,CANONICAL\n"
            + "DETAIL,700-ZERO,Linea estacional cero,REVENUE,P_AND_L,ROW-21,21,MARZO,Marzo,0.00,0.00,CANONICAL\n"
            + "DETAIL,700-ZERO,Linea estacional cero,REVENUE,P_AND_L,ROW-21,21,ABRIL,Abril,0.00,0.00,CANONICAL\n"
            + "DETAIL,700-ZERO,Linea estacional cero,REVENUE,P_AND_L,ROW-21,21,MAYO,Mayo,0.00,0.00,CANONICAL\n"
            + "DETAIL,700-ZERO,Linea estacional cero,REVENUE,P_AND_L,ROW-21,21,JUNIO,Junio,0.00,0.00,CANONICAL\n"
            + "DETAIL,700-ZERO,Linea estacional cero,REVENUE,P_AND_L,ROW-21,21,JULIO,Julio,0.00,0.00,CANONICAL\n"
            + "DETAIL,700-ZERO,Linea estacional cero,REVENUE,P_AND_L,ROW-21,21,AGOSTO,Agosto,0.00,0.00,CANONICAL\n"
            + "DETAIL,700-ZERO,Linea estacional cero,REVENUE,P_AND_L,ROW-21,21,SEPTIEMBRE,Septiembre,0.00,0.00,CANONICAL\n"
            + "DETAIL,700-ZERO,Linea estacional cero,REVENUE,P_AND_L,ROW-21,21,OCTUBRE,Octubre,0.00,0.00,CANONICAL\n"
            + "DETAIL,700-ZERO,Linea estacional cero,REVENUE,P_AND_L,ROW-21,21,NOVIEMBRE,Noviembre,0.00,0.00,CANONICAL\n"
            + "DETAIL,700-ZERO,Linea estacional cero,REVENUE,P_AND_L,ROW-21,21,DICIEMBRE,Diciembre,0.00,0.00,CANONICAL\n";

        when(importFileService.latestAnnualBudget(7L)).thenReturn(Optional.of(imp));
        when(importFileService.normalizedCsv(7L, imp.getId())).thenReturn(csv.getBytes());

        BudgetService.LongBudgetSource source = service.latestLongBudgetSource(7L);
        var cashflow = service.latestCashflow(7L);
        var insights = BudgetInsightsCalculator.compute(imp.getFilename(), imp.getCreatedAt(), csv.getBytes(), 10_000);

        BigDecimal ingresos = total(source.plannedIncomeByMonth().values());
        BigDecimal gastos = total(source.plannedExpenseByMonth().values());
        BigDecimal capex = total(source.plannedCapexByMonth().values());
        BigDecimal depreciation = total(source.plannedDepreciationByMonth().values());
        BigDecimal financiero = total(source.plannedFinancingByMonth().values());
        BigDecimal ebitda = ingresos.subtract(gastos);
        BigDecimal ebit = ebitda.subtract(depreciation);
        BigDecimal beneficioNeto = ebit.add(financiero);

        assertThat(ingresos).isEqualByComparingTo("660000.00");
        assertThat(gastos).isEqualByComparingTo("456000.00");
        assertThat(capex).isEqualByComparingTo("48000.00");
        assertThat(depreciation).isEqualByComparingTo("24000.00");
        assertThat(ebitda).isEqualByComparingTo("204000.00");
        assertThat(ebit).isEqualByComparingTo("180000.00");
        assertThat(financiero).isEqualByComparingTo("12000.00");
        assertThat(beneficioNeto).isEqualByComparingTo("192000.00");
        assertThat(cashflow.endingBalance()).isEqualByComparingTo("256000.00");
        assertThat(source.plannedExpenseByMonth().get("DICIEMBRE")).isEqualByComparingTo("456000.00");
        assertThat(source.plannedCashOutflowByMonth().get("DICIEMBRE")).isEqualByComparingTo("216000.00");
        assertThat(insights.topDrivers()).noneMatch(item -> List.of("GASTOS EXPLOTACION", "OTROS GASTOS EXPLOTACION", "640-642 Personal y seg social").contains(item.label()));
        assertThat(insights.topDrivers()).anyMatch(item -> "628".equals(item.code()) && item.annualTotal().compareTo(new BigDecimal("36000.00")) == 0);
        assertThat(insights.zeroHeavyItems()).noneMatch(item -> "700-ZERO".equals(item.code()));
    }

    @ParameterizedTest
    @MethodSource("wideBudgetVariants")
    void keeps_financial_classification_stable_across_products_codes_order_and_aggregate_labels(String mainPurchaseLabel,
                                                                                                 String purchaseCodeA,
                                                                                                 String purchaseCodeB,
                                                                                                 String purchaseCodeC,
                                                                                                 String aggregateLabelA,
                                                                                                 String aggregateLabelB,
                                                                                                 String aggregateLabelC,
                                                                                                 boolean reorderRows) {
        UniversalImportFileService importFileService = mock(UniversalImportFileService.class);
        BudgetService service = new BudgetService(importFileService);

        UniversalImport imp = new UniversalImport();
        imp.setFilename("any-annual-input.xlsx");
        imp.setCreatedAt(Instant.parse("2026-07-24T09:15:00Z"));

        String csv = buildWideAnnualSource(
            mainPurchaseLabel,
            purchaseCodeA,
            purchaseCodeB,
            purchaseCodeC,
            aggregateLabelA,
            aggregateLabelB,
            aggregateLabelC,
            reorderRows
        );

        when(importFileService.latestAnnualBudget(7L)).thenReturn(Optional.of(imp));
        when(importFileService.normalizedCsv(7L, imp.getId())).thenReturn(csv.getBytes());

        BudgetService.LongBudgetSource source = service.latestLongBudgetSource(7L);
        var insights = service.latestBudgetLongInsights(7L);

        assertThat(total(source.plannedIncomeByMonth().values())).isEqualByComparingTo("2400");
        assertThat(total(source.plannedExpenseByMonth().values())).isEqualByComparingTo("720");
        assertThat(source.plannedIncomeByMonth().get("DICIEMBRE")).isEqualByComparingTo("200");
        assertThat(source.plannedExpenseByMonth().get("DICIEMBRE")).isEqualByComparingTo("60");
        assertThat(insights.topDrivers()).noneMatch(item ->
            List.of(aggregateLabelA, aggregateLabelB, aggregateLabelC).stream()
                .map(String::toUpperCase)
                .anyMatch(aggregate -> aggregate.equals(item.label().toUpperCase()))
        );
        assertThat(insights.topDrivers()).anyMatch(item -> canonicalExpectedCode(purchaseCodeA).equals(item.code()) && item.annualTotal().compareTo(new BigDecimal("120")) == 0);
        assertThat(insights.topDrivers()).anyMatch(item -> canonicalExpectedCode(purchaseCodeB).equals(item.code()) && item.annualTotal().compareTo(new BigDecimal("240")) == 0);
        assertThat(insights.topDrivers()).anyMatch(item -> canonicalExpectedCode(purchaseCodeC).equals(item.code()) && item.annualTotal().compareTo(new BigDecimal("360")) == 0);
    }

    private static Stream<Arguments> wideBudgetVariants() {
        return Stream.of(
            Arguments.of("Compras Café", "602-1", "621-OPER", "640_A", "Costes operativos", "Gastos generales", "Total de costes", false),
            Arguments.of("Compras Harina", "700-1", "710-OPER", "720_A", "Costes operativos", "Gastos generales", "Total de costes", true),
            Arguments.of("Compras Componentes", "810-DET", "811-GEN", "812_VAR", "Costes operativos", "Gastos generales", "Total de costes", false)
        );
    }

    private static String buildWideAnnualSource(String mainPurchaseLabel,
                                                String purchaseCodeA,
                                                String purchaseCodeB,
                                                String purchaseCodeC,
                                                String aggregateLabelA,
                                                String aggregateLabelB,
                                                String aggregateLabelC,
                                                boolean reorderRows) {
        String header = "Concepto," + String.join(",", MONTHS) + "\n";
        List<String> revenueRows = List.of(
            monthlyRow("Ingresos de explotación", "200"),
            monthlyRow("700-VENT Ventas recurrentes", "120"),
            monthlyRow("701-SERV Servicios proyecto", "80")
        );
        List<String> expenseRowsDefault = List.of(
            monthlyRow(aggregateLabelA, "999"),
            monthlyRow(purchaseCodeA + " " + mainPurchaseLabel, "10"),
            monthlyRow(purchaseCodeB + " Compras Oficina", "20"),
            monthlyRow(purchaseCodeC + " Compras Licencias", "30"),
            monthlyRow(aggregateLabelB, "888"),
            monthlyRow(aggregateLabelC, "777")
        );
        List<String> expenseRowsReordered = List.of(
            monthlyRow(aggregateLabelA, "999"),
            monthlyRow(purchaseCodeC + " Compras Licencias", "30"),
            monthlyRow(aggregateLabelB, "888"),
            monthlyRow(purchaseCodeA + " " + mainPurchaseLabel, "10"),
            monthlyRow(aggregateLabelC, "777"),
            monthlyRow(purchaseCodeB + " Compras Oficina", "20")
        );
        StringBuilder out = new StringBuilder(header);
        for (String row : revenueRows) out.append(row);
        for (String row : (reorderRows ? expenseRowsReordered : expenseRowsDefault)) out.append(row);
        return out.toString();
    }

    private static String monthlyRow(String label, String monthValue) {
        StringBuilder row = new StringBuilder(label);
        for (int i = 0; i < MONTHS.size(); i++) {
            row.append(',').append(monthValue);
        }
        row.append('\n');
        return row.toString();
    }

    private static BigDecimal total(Iterable<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            if (value != null) {
                total = total.add(value);
            }
        }
        return total;
    }

    private static String canonicalExpectedCode(String rawCode) {
        if (rawCode == null) {
            return null;
        }
        String trimmed = rawCode.trim();
        int split = trimmed.indexOf('-');
        if (split > 0) {
            String tail = trimmed.substring(split + 1);
            if (!tail.isBlank() && Character.isLetter(tail.charAt(0))) {
                return trimmed.substring(0, split);
            }
        }
        split = trimmed.indexOf('_');
        if (split > 0) {
            String tail = trimmed.substring(split + 1);
            if (!tail.isBlank() && Character.isLetter(tail.charAt(0))) {
                return trimmed.substring(0, split);
            }
        }
        return trimmed;
    }
}

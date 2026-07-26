package com.asecon.enterpriseiq.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetLongNormalizerTest {
    private static final List<String> MONTHS = List.of(
        "ENERO", "FEBRERO", "MARZO", "ABRIL", "MAYO", "JUNIO",
        "JULIO", "AGOSTO", "SEPTIEMBRE", "OCTUBRE", "NOVIEMBRE", "DICIEMBRE"
    );
    @Test
    void normalizes_wide_months_to_long_rows_and_extracts_code() {
        String csv = ""
            + "Concepto,ENERO,FEBRERO,MARZO,ABRIL,MAYO,JUNIO,JULIO,AGOSTO,SEPTIEMBRE,OCTUBRE,NOVIEMBRE,DICIEMBRE\n"
            + "700-8 Ingresos Trigo,10,0,5,0,0,0,0,0,0,0,0,0\n"
            + "GASTOS DE EXPLOTACION,1,2,3,0,0,0,0,0,0,0,0,0\n";

        var result = BudgetLongNormalizer.normalizeToLongCsv(csv.getBytes(StandardCharsets.UTF_8), 1000, 50);
        assertThat(result.longCsvBytes()).isNotEmpty();
        assertThat(result.labelHeader()).isEqualTo("Concepto");
        assertThat(result.totalRowsProduced()).isGreaterThan(0);

        assertThat(result.sampleRows().get(0).code()).isEqualTo("700-8");
        assertThat(result.sampleRows().get(0).label()).isEqualTo("Ingresos Trigo");
        assertThat(result.sampleRows().get(0).monthKey()).isEqualTo("ENERO");
    }

    @Test
    void normalizes_long_monthly_budget_source_to_canonical_long_rows() {
        String csv = ""
            + "mes_numero,mes_nombre,plan_item_id,tipo_partida,categoria,subcategoria,concepto,plan_mes_eur\n"
            + "1,Enero,ING-ERP,INGRESO,Ingresos,ERP,Consultoria ERP,73500.0\n"
            + "1,Enero,GTO-PERS,GASTO,Personal,Nominas,Equipo consultoria,42500.0\n"
            + "2,Febrero,ING-ERP,INGRESO,Ingresos,ERP,Consultoria ERP,75600.0\n";

        var result = BudgetLongNormalizer.normalizeToLongCsv(csv.getBytes(StandardCharsets.UTF_8), 1000, 50);

        assertThat(result.longCsvBytes()).isNotEmpty();
        assertThat(result.labelHeader()).isEqualTo("concepto");
        assertThat(result.monthKeys()).containsExactly("ENERO", "FEBRERO");
        assertThat(result.totalRowsProduced()).isEqualTo(3);
        assertThat(result.sampleRows().get(0).code()).isEqualTo("ING-ERP");
        assertThat(result.sampleRows().get(0).label()).isEqualTo("Consultoria ERP");
        assertThat(result.sampleRows().get(0).monthKey()).isEqualTo("ENERO");
        assertThat(result.sampleRows().get(0).blockId()).isEqualTo("ROW-1");
        assertThat(result.sampleRows().get(0).sourceRow()).isEqualTo(1);
        assertThat(new String(result.longCsvBytes(), StandardCharsets.UTF_8)).contains("block_id,source_row");
    }

    @Test
    void recognizes_mixed_headers_and_separates_capex_semantics() {
        String csv = ""
            + "Posting_Period,Concept_Label,Account_Code,Tipo_Operacion,PlanAmount,Importe_Real,Forecast_Cierre,Desviacion_vs_Budget,Currency\n"
            + "2026-01,New ERP rollout,INV-ERP,CAPEX,10000,9500,9800,-500,EUR\n"
            + "2026-01,Monthly retainers,REV-RET,Revenue,30000,29500,30200,-500,EUR\n"
            + "2026-01,Payroll consulting,EXP-PAY,OPEX,12000,11800,12100,-200,EUR\n";

        var result = BudgetLongNormalizer.normalizeToLongCsv(csv.getBytes(StandardCharsets.UTF_8), "7", 1000, 20);

        assertThat(result.longCsvBytes()).isNotEmpty();
        assertThat(result.labelHeader()).isEqualTo("Concept_Label");
        assertThat(result.monthKeys()).containsExactly("ENERO");
        assertThat(result.sampleRows()).extracting(BudgetLongNormalizer.LongRow::semanticKind)
            .containsExactly("CAPEX", "REVENUE", "OPEX");
        assertThat(new String(result.longCsvBytes(), StandardCharsets.UTF_8)).contains("semantic_kind");
    }

    @Test
    void keeps_annual_wide_sources_usable_when_detail_is_inferred_from_section_context() {
        String csv = ""
            + "Concepto,ENERO,FEBRERO,MARZO,ABRIL,MAYO,JUNIO,JULIO,AGOSTO,SEPTIEMBRE,OCTUBRE,NOVIEMBRE,DICIEMBRE\n"
            + "TOTAL INGRESOS DE EXPLOTACION,100,100,100,100,100,100,100,100,100,100,100,100\n"
            + "Campaña Guisantes,0,0,0,0,0,0,0,0,20,0,0,0\n"
            + "GASTOS EXPLOTACION,80,80,80,80,80,80,80,80,80,80,80,80\n"
            + "602-1 Compras Semillas,10,0,0,0,5,0,0,0,0,0,0,12\n";

        var result = BudgetLongNormalizer.normalizeToLongCsv(csv.getBytes(StandardCharsets.UTF_8), 1000, 50);

        assertThat(result.longCsvBytes()).isNotEmpty();
        assertThat(result.requiresConfirmation()).isFalse();
        assertThat(result.sampleRows()).extracting(BudgetLongNormalizer.LongRow::mappingStatus)
            .contains("INFERRED");
        assertThat(result.sampleRows()).filteredOn(row -> "Campaña Guisantes".equals(row.label()))
            .extracting(BudgetLongNormalizer.LongRow::semanticKind)
            .containsOnly("REVENUE");
    }

    @Test
    void does_not_treat_generic_words_as_account_codes() {
        String csv = ""
            + "Concepto,ENERO,FEBRERO,MARZO,ABRIL,MAYO,JUNIO,JULIO,AGOSTO,SEPTIEMBRE,OCTUBRE,NOVIEMBRE,DICIEMBRE\n"
            + "OTROS GASTOS EXPLOTACION,10,10,10,10,10,10,10,10,10,10,10,10\n"
            + "628 Suministros,5,5,5,5,5,5,5,5,5,5,5,5\n";

        var result = BudgetLongNormalizer.normalizeToLongCsv(csv.getBytes(StandardCharsets.UTF_8), 1000, 20);

        assertThat(result.sampleRows()).filteredOn(row -> row.label().contains("OTROS GASTOS"))
            .allMatch(row -> row.code() == null);
        assertThat(result.sampleRows()).filteredOn(row -> row.label().contains("Suministros"))
            .allMatch(row -> "628".equals(row.code()));
    }

    @Test
    void normalizes_accounting_code_variants_before_building_canonical_identity() {
        String csv = ""
            + "Concepto,ENERO,FEBRERO,MARZO,ABRIL,MAYO,JUNIO,JULIO,AGOSTO,SEPTIEMBRE,OCTUBRE,NOVIEMBRE,DICIEMBRE\n"
            + "607 Trabajos realizados otras empresas,10,0,0,0,0,0,0,0,0,0,0,0\n"
            + "607.TRABAJOS REALIZADOS OTRAS EMPRESAS,20,0,0,0,0,0,0,0,0,0,0,0\n"
            + "607. Trabajos realizados otras empresas,30,0,0,0,0,0,0,0,0,0,0,0\n";

        var result = BudgetLongNormalizer.normalizeToLongCsv(csv.getBytes(StandardCharsets.UTF_8), 10_000, 40);

        assertThat(result.sampleRows())
            .filteredOn(row -> "ENERO".equals(row.monthKey()))
            .extracting(BudgetLongNormalizer.LongRow::code)
            .containsOnly("607");
        assertThat(result.sampleRows())
            .filteredOn(row -> "ENERO".equals(row.monthKey()))
            .extracting(row -> row.label().toUpperCase())
            .containsOnly("TRABAJOS REALIZADOS OTRAS EMPRESAS");
    }

    @Test
    void treats_structural_headings_and_code_ranges_as_aggregates_in_annual_wide_sources() {
        String csv = ""
            + "Concepto,ENERO,FEBRERO,MARZO,ABRIL,MAYO,JUNIO,JULIO,AGOSTO,SEPTIEMBRE,OCTUBRE,NOVIEMBRE,DICIEMBRE\n"
            + "GASTOS EXPLOTACION,0,0,0,0,0,0,0,0,0,0,0,120\n"
            + "3. Gastos personal,0,0,0,0,0,0,0,0,0,0,0,60\n"
            + "640-642 Personal y seg social,0,0,0,0,0,0,0,0,0,0,0,60\n"
            + "640 Sueldos y salarios,5,5,5,5,5,5,5,5,5,5,5,5\n"
            + "629 Otros gastos de explotacion,5,5,5,5,5,5,5,5,5,5,5,5\n";

        var result = BudgetLongNormalizer.normalizeToLongCsv(csv.getBytes(StandardCharsets.UTF_8), 10_000, 200);

        assertThat(result.requiresConfirmation()).isFalse();
        assertThat(result.sampleRows()).filteredOn(row -> "GASTOS EXPLOTACION".equals(row.label()))
            .allMatch(row -> row.rowType() == BudgetLongNormalizer.RowType.SUBTOTAL);
        assertThat(result.sampleRows()).filteredOn(row -> "3. Gastos personal".equals(row.label()))
            .allMatch(row -> row.rowType() == BudgetLongNormalizer.RowType.SUBTOTAL);
        assertThat(result.sampleRows()).filteredOn(row -> "Personal y seg social".equals(row.label()))
            .allMatch(row -> row.rowType() == BudgetLongNormalizer.RowType.SUBTOTAL);
        assertThat(result.sampleRows()).filteredOn(row -> "Sueldos y salarios".equals(row.label()))
            .allMatch(row -> row.rowType() == BudgetLongNormalizer.RowType.DETAIL)
            .allMatch(row -> "OPEX".equals(row.semanticKind()));
    }

    @Test
    void keeps_financial_result_as_financing_total_in_annual_sources() {
        String csv = ""
            + "Concepto,ENERO,FEBRERO,MARZO,ABRIL,MAYO,JUNIO,JULIO,AGOSTO,SEPTIEMBRE,OCTUBRE,NOVIEMBRE,DICIEMBRE\n"
            + "Resultado financiero,10,10,10,10,10,10,10,10,10,10,10,10\n";

        var result = BudgetLongNormalizer.normalizeToLongCsv(csv.getBytes(StandardCharsets.UTF_8), "7", 10_000, 50);

        assertThat(result.sampleRows()).filteredOn(row -> "Resultado financiero".equals(row.label()))
            .allMatch(row -> row.rowType() == BudgetLongNormalizer.RowType.TOTAL)
            .allMatch(row -> "FINANCING".equals(row.semanticKind()))
            .allMatch(row -> "P_AND_L".equals(row.sectionKind()));
    }

    @ParameterizedTest
    @MethodSource("annualDomainVariants")
    void normalizes_plan_anual_across_sectors_code_systems_and_vocabulary(String revenueDetailLabel,
                                                                          String purchaseDetailLabel,
                                                                          String serviceDetailLabel,
                                                                          String revenueDetailCode,
                                                                          String purchaseDetailCode,
                                                                          String serviceDetailCode,
                                                                          String payrollCode,
                                                                          String depreciationCode,
                                                                          String financialIncomeCode,
                                                                          String financialExpenseCode,
                                                                          String revenueAggregateLabel,
                                                                          String opexAggregateLabel,
                                                                          String cashflowPaymentLabel,
                                                                          boolean reorderRows) {
        String csv = buildAnnualDomainCsv(
            revenueDetailLabel,
            purchaseDetailLabel,
            serviceDetailLabel,
            revenueDetailCode,
            purchaseDetailCode,
            serviceDetailCode,
            payrollCode,
            depreciationCode,
            financialIncomeCode,
            financialExpenseCode,
            revenueAggregateLabel,
            opexAggregateLabel,
            cashflowPaymentLabel,
            reorderRows
        );

        var result = BudgetLongNormalizer.normalizeToLongCsv(csv.getBytes(StandardCharsets.UTF_8), "7", 10_000, 500);

        assertThat(result.longCsvBytes()).isNotEmpty();
        assertThat(result.requiresConfirmation()).isFalse();
        assertThat(result.sampleRows()).filteredOn(row -> row.label().equals(revenueDetailLabel))
            .allMatch(row -> row.rowType() == BudgetLongNormalizer.RowType.DETAIL)
            .allMatch(row -> "REVENUE".equals(row.semanticKind()));
        assertThat(result.sampleRows()).filteredOn(row -> row.label().equals(purchaseDetailLabel))
            .allMatch(row -> row.rowType() == BudgetLongNormalizer.RowType.DETAIL)
            .allMatch(row -> "OPEX".equals(row.semanticKind()));
        assertThat(result.sampleRows()).filteredOn(row -> row.label().equals(serviceDetailLabel))
            .allMatch(row -> row.rowType() == BudgetLongNormalizer.RowType.DETAIL)
            .allMatch(row -> "OPEX".equals(row.semanticKind()));
        assertThat(result.sampleRows()).filteredOn(row -> row.label().equals("Personal de estructura"))
            .allMatch(row -> "OPEX".equals(row.semanticKind()));
        assertThat(result.sampleRows()).filteredOn(row -> row.label().equals("Amortización inmovilizado"))
            .allMatch(row -> "DEPRECIATION_AMORTIZATION".equals(row.semanticKind()));
        assertThat(result.sampleRows()).filteredOn(row -> row.label().equals("Ingresos financieros"))
            .allMatch(row -> "FINANCING".equals(row.semanticKind()));
        assertThat(result.sampleRows()).filteredOn(row -> row.label().equals("Gastos financieros"))
            .allMatch(row -> "FINANCING".equals(row.semanticKind()));
        assertThat(result.sampleRows()).filteredOn(row -> row.label().equals(revenueAggregateLabel) || row.label().equals(opexAggregateLabel))
            .allMatch(row -> row.rowType() != BudgetLongNormalizer.RowType.DETAIL);
    }

    @Test
    void marks_ambiguous_rows_for_review_when_text_and_context_are_insufficient() {
        String csv = ""
            + "mes_nombre,concepto,importe\n"
            + "Enero,Elemento miscelaneo,100\n"
            + "Febrero,Elemento miscelaneo,120\n";

        var result = BudgetLongNormalizer.normalizeToLongCsv(csv.getBytes(StandardCharsets.UTF_8), "7", 1_000, 20);

        assertThat(result.sampleRows()).isNotEmpty();
        assertThat(result.sampleRows()).allMatch(row -> "REVIEW".equals(row.mappingStatus()));
    }

    @Test
    void keeps_revenue_detail_rows_as_detail_when_their_code_looks_internal() {
        String csv = ""
            + "Concepto,ENERO,FEBRERO,MARZO,ABRIL,MAYO,JUNIO,JULIO,AGOSTO,SEPTIEMBRE,OCTUBRE,NOVIEMBRE,DICIEMBRE\n"
            + "Ingresos de explotacion,200,200,200,200,200,200,200,200,200,200,200,200\n"
            + "700-VENT Ventas recurrentes,120,120,120,120,120,120,120,120,120,120,120,120\n"
            + "701-SERV Servicios proyecto,80,80,80,80,80,80,80,80,80,80,80,80\n";

        var result = BudgetLongNormalizer.normalizeToLongCsv(csv.getBytes(StandardCharsets.UTF_8), "7", 10_000, 200);

        assertThat(result.sampleRows()).filteredOn(row -> "Servicios proyecto".equals(row.label()))
            .allMatch(row -> row.rowType() == BudgetLongNormalizer.RowType.DETAIL)
            .allMatch(row -> "REVENUE".equals(row.semanticKind()))
            .allMatch(row -> "INFERRED".equals(row.mappingStatus()) || "CANONICAL".equals(row.mappingStatus()));
    }

    private static Stream<Arguments> annualDomainVariants() {
        return Stream.of(
            Arguments.of("Ventas de trigo", "Compras de semillas", "Servicios de recolección", "700", "602", "621", "640", "681", "760", "669", "Facturación", "Gastos operativos", "Pagos proveedores", false),
            Arguments.of("Ventas de mantecados", "Materias primas de harina y azúcar", "Servicios de envasado", "REV-01", "MAT-02", "PACK-03", "PAY-03", "DEP-04", "FININC-05", "FINEXP-06", "Revenue", "Operating expenses", "Cash payments", true),
            Arguments.of("Ventas de productos", "Consumo de mercaderías", "Servicios logísticos", "", "", "", "", "", "", "", "Sales", "Total operating costs", "Supplier payments", false),
            Arguments.of("Ingresos por suscripciones", "Infraestructura cloud", "Servicios subcontratados", "REV-SUB", "CLOUD-04", "SERV-05", "PEOPLE-06", "DEP-07", "FIN-08", "INT-09", "Ingresos por servicios", "Costes operacionales", "Payments", true)
        );
    }

    private static String buildAnnualDomainCsv(String revenueDetailLabel,
                                               String purchaseDetailLabel,
                                               String serviceDetailLabel,
                                               String revenueDetailCode,
                                               String purchaseDetailCode,
                                               String serviceDetailCode,
                                               String payrollCode,
                                               String depreciationCode,
                                               String financialIncomeCode,
                                               String financialExpenseCode,
                                               String revenueAggregateLabel,
                                               String opexAggregateLabel,
                                               String cashflowPaymentLabel,
                                               boolean reorderRows) {
        StringBuilder out = new StringBuilder("Concepto,ENERO,FEBRERO,MARZO,ABRIL,MAYO,JUNIO,JULIO,AGOSTO,SEPTIEMBRE,OCTUBRE,NOVIEMBRE,DICIEMBRE\n");
        List<String> ordered = reorderRows
            ? List.of(
                monthlyRow("Saldo final", "50"),
                monthlyRow(opexAggregateLabel, "80"),
                monthlyRow(withOptionalCode(serviceDetailCode, serviceDetailLabel), "20"),
                monthlyRow("Tesorería", "0"),
                monthlyRow(cashflowPaymentLabel, "80"),
                monthlyRow(withOptionalCode(revenueDetailCode, revenueDetailLabel), "150"),
                monthlyRow("Cobros clientes", "150"),
                monthlyRow(withOptionalCode(payrollCode, "Personal de estructura"), "25"),
                monthlyRow(revenueAggregateLabel, "150"),
                monthlyRow(withOptionalCode(depreciationCode, "Amortización inmovilizado"), "10"),
                monthlyRow(withOptionalCode(financialIncomeCode, "Ingresos financieros"), "4"),
                monthlyRow(withOptionalCode(purchaseDetailCode, purchaseDetailLabel), "35"),
                monthlyRow(withOptionalCode(financialExpenseCode, "Gastos financieros"), "3")
            )
            : List.of(
                monthlyRow(revenueAggregateLabel, "150"),
                monthlyRow(withOptionalCode(revenueDetailCode, revenueDetailLabel), "150"),
                monthlyRow(opexAggregateLabel, "80"),
                monthlyRow(withOptionalCode(purchaseDetailCode, purchaseDetailLabel), "35"),
                monthlyRow(withOptionalCode(serviceDetailCode, serviceDetailLabel), "20"),
                monthlyRow(withOptionalCode(payrollCode, "Personal de estructura"), "25"),
                monthlyRow(withOptionalCode(depreciationCode, "Amortización inmovilizado"), "10"),
                monthlyRow(withOptionalCode(financialIncomeCode, "Ingresos financieros"), "4"),
                monthlyRow(withOptionalCode(financialExpenseCode, "Gastos financieros"), "3"),
                monthlyRow("Tesorería", "0"),
                monthlyRow("Cobros clientes", "150"),
                monthlyRow(cashflowPaymentLabel, "80"),
                monthlyRow("Saldo final", "50")
            );
        for (String row : ordered) out.append(row);
        return out.toString();
    }

    private static String monthlyRow(String label, String value) {
        StringBuilder row = new StringBuilder(label);
        for (int i = 0; i < MONTHS.size(); i++) {
            row.append(',').append(value);
        }
        row.append('\n');
        return row.toString();
    }

    private static String withOptionalCode(String code, String label) {
        if (code == null || code.isBlank()) return label;
        return code + " " + label;
    }
}

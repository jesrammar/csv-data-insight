package com.asecon.enterpriseiq.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
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
        assertThat(result.analysisStatus()).isEqualTo("AUTOMATIC_ACCEPTED");
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
        assertThat(result.sampleRows().get(0).blockId()).startsWith("P_AND_L-");
        assertThat(result.sampleRows().get(0).sourceRow()).isEqualTo(1);
        assertThat(new String(result.longCsvBytes(), StandardCharsets.UTF_8)).contains("block_id,source_row");
        assertThat(result.analysisStatus()).isEqualTo("AUTOMATIC_ACCEPTED");
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
    void inherits_opex_from_plain_gastos_block_headings_without_literal_operating_suffix() {
        String csv = ""
            + "Concepto,ENERO,FEBRERO,MARZO,ABRIL,MAYO,JUNIO,JULIO,AGOSTO,SEPTIEMBRE,OCTUBRE,NOVIEMBRE,DICIEMBRE\n"
            + "INGRESOS,0,0,0,0,0,0,0,0,0,0,0,0\n"
            + "Ventas mostrador,10,10,10,10,10,10,10,10,10,10,10,10\n"
            + "GASTOS,0,0,0,0,0,0,0,0,0,0,0,0\n"
            + "Sueldos y Seguridad Social,5,5,5,5,5,5,5,5,5,5,5,5\n"
            + "Alquiler del local,2,2,2,2,2,2,2,2,2,2,2,2\n"
            + "Luz,1,1,1,1,1,1,1,1,1,1,1,1\n";

        var result = BudgetLongNormalizer.normalizeToLongCsv(csv.getBytes(StandardCharsets.UTF_8), "7", 10_000, 120);

        assertThat(result.analysisStatus()).isEqualTo("AUTOMATIC_ACCEPTED");
        assertThat(result.sampleRows()).filteredOn(row -> "GASTOS".equals(row.label()))
            .allMatch(row -> row.rowType() == BudgetLongNormalizer.RowType.SUBTOTAL)
            .allMatch(row -> "OPEX".equals(row.semanticKind()))
            .allMatch(row -> "P_AND_L".equals(row.sectionKind()));
        assertThat(result.sampleRows()).filteredOn(row -> "Sueldos y Seguridad Social".equals(row.label()))
            .allMatch(row -> row.rowType() == BudgetLongNormalizer.RowType.DETAIL)
            .allMatch(row -> "OPEX".equals(row.semanticKind()))
            .allMatch(row -> !"REVIEW".equals(row.mappingStatus()));
        assertThat(result.sampleRows()).filteredOn(row -> "Alquiler del local".equals(row.label()) || "Luz".equals(row.label()))
            .allMatch(row -> row.rowType() == BudgetLongNormalizer.RowType.DETAIL)
            .allMatch(row -> "OPEX".equals(row.semanticKind()))
            .allMatch(row -> "P_AND_L".equals(row.sectionKind()));
    }

    @Test
    void inherits_revenue_from_block_heading_even_when_heading_lives_in_code_column() {
        String csv = ""
            + "Codigo,Concepto,ENERO,FEBRERO,MARZO,ABRIL,MAYO,JUNIO,JULIO,AGOSTO,SEPTIEMBRE,OCTUBRE,NOVIEMBRE,DICIEMBRE,Total anual\n"
            + "INGRESOS,,0,0,0,0,0,0,0,0,0,0,0,0,0\n"
            + "700.11,Ventas de fruta fresca,100,100,100,100,100,100,100,100,100,100,100,100,1200\n"
            + "700.37,Productos gourmet de temporada,50,50,50,50,50,50,50,50,50,50,50,50,600\n"
            + "700.82,Zumos y batidos frescos,30,30,30,30,30,30,30,30,30,30,30,30,360\n"
            + "759,Ingresos accesorios,20,20,20,20,20,20,20,20,20,20,20,20,240\n"
            + ",TOTAL INGRESOS,200,200,200,200,200,200,200,200,200,200,200,200,2400\n";

        var result = BudgetLongNormalizer.normalizeToLongCsv(csv.getBytes(StandardCharsets.UTF_8), "17", 10_000, 200);

        assertThat(result.analysisStatus()).isEqualTo("AUTOMATIC_ACCEPTED");
        assertThat(result.sampleRows())
            .filteredOn(row -> List.of(
                "Ventas de fruta fresca",
                "Productos gourmet de temporada",
                "Zumos y batidos frescos",
                "Ingresos accesorios"
            ).contains(row.label()))
            .allMatch(row -> row.rowType() == BudgetLongNormalizer.RowType.DETAIL)
            .allMatch(row -> "REVENUE".equals(row.semanticKind()))
            .allMatch(row -> "P_AND_L".equals(row.sectionKind()))
            .allMatch(row -> !"REVIEW".equals(row.mappingStatus()));
    }

    @Test
    void classifies_tax_detail_rows_as_tax_inside_profit_and_loss_blocks() {
        String csv = ""
            + "Codigo,Concepto,ENERO,FEBRERO,MARZO,ABRIL,MAYO,JUNIO,JULIO,AGOSTO,SEPTIEMBRE,OCTUBRE,NOVIEMBRE,DICIEMBRE,Total anual\n"
            + "GASTOS,,0,0,0,0,0,0,0,0,0,0,0,0,0\n"
            + "640,Sueldos y salarios,100,100,100,100,100,100,100,100,100,100,100,100,1200\n"
            + "630,Impuesto sobre beneficios estimado,10,10,10,10,10,10,10,10,10,10,10,10,120\n";

        var result = BudgetLongNormalizer.normalizeToLongCsv(csv.getBytes(StandardCharsets.UTF_8), "17", 10_000, 120);

        assertThat(result.sampleRows())
            .filteredOn(row -> "Impuesto sobre beneficios estimado".equals(row.label()))
            .isNotEmpty()
            .allMatch(row -> row.rowType() == BudgetLongNormalizer.RowType.DETAIL)
            .allMatch(row -> "TAX".equals(row.semanticKind()))
            .allMatch(row -> "P_AND_L".equals(row.sectionKind()))
            .allMatch(row -> !"REVIEW".equals(row.mappingStatus()));
    }

    @Test
    void classifier_prioritizes_explicit_tax_labels_over_generic_opex_reading() {
        var classification = BudgetCanonicalClassifier.classify(
            "17",
            "Impuesto sobre beneficios estimado",
            "630",
            "Impuesto sobre beneficios estimado",
            List.of(BigDecimal.TEN, BigDecimal.TEN),
            new BudgetCanonicalClassifier.SectionContext("OPEX", "P_AND_L")
        );

        assertThat(classification.rowType()).isEqualTo(BudgetLongNormalizer.RowType.DETAIL);
        assertThat(classification.semanticKind()).isEqualTo("TAX");
        assertThat(classification.sectionKind()).isEqualTo("P_AND_L");
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
        assertThat(result.analysisStatus()).isEqualTo("GUIDED_REVIEW_REQUIRED");
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

    @Test
    void detects_shifted_header_rows_using_header_score_and_month_columns() {
        String csv = ""
            + "Informe anual de gestion,,,,,,,,,,,,,,\n"
            + "Escenario base,,,,,,,,,,,,,,\n"
            + "Clasificacion,Codigo,Concepto,Ene,Feb,Mar,Abr,May,Jun,Jul,Ago,Sep,Oct,Nov,Dic\n"
            + "Revenue,REV-01,Ingresos recurrentes,100,100,100,100,100,100,100,100,100,100,100,100\n"
            + "Opex,EXP-01,Costes de plataforma,40,40,40,40,40,40,40,40,40,40,40,40\n";

        var result = BudgetLongNormalizer.normalizeToLongCsv(csv.getBytes(StandardCharsets.UTF_8), "7", 10_000, 50);

        assertThat(result.headerRow1Based()).isEqualTo(3);
        assertThat(result.headerScore()).isGreaterThan(0.70d);
        assertThat(result.monthKeys()).containsExactly(MONTHS.toArray(String[]::new));
        assertThat(result.sampleRows()).extracting(BudgetLongNormalizer.LongRow::semanticKind)
            .contains("REVENUE", "OPEX");
        assertThat(result.analysisStatus()).isEqualTo("AUTOMATIC_ACCEPTED");
    }

    @Test
    void prioritizes_explicit_semantic_columns_over_free_text_labels() {
        String csv = ""
            + "financial nature,row type,account,description,jan,feb,mar,apr,may,jun,jul,aug,sep,oct,nov,dec\n"
            + "CAPEX,detail,INV-01,Recurring hosting investment,10,10,10,10,10,10,10,10,10,10,10,10\n"
            + "REVENUE,detail,REV-01,Costes operativos,30,30,30,30,30,30,30,30,30,30,30,30\n";

        var result = BudgetLongNormalizer.normalizeToLongCsv(csv.getBytes(StandardCharsets.UTF_8), "7", 10_000, 50);

        assertThat(result.sampleRows()).filteredOn(row -> "Recurring hosting investment".equals(row.label()))
            .allMatch(row -> "CAPEX".equals(row.semanticKind()));
        assertThat(result.sampleRows()).filteredOn(row -> "Costes operativos".equals(row.label()))
            .allMatch(row -> "REVENUE".equals(row.semanticKind()));
        assertThat(result.analysisStatus()).isEqualTo("AUTOMATIC_ACCEPTED");
    }

    @Test
    void accepts_long_annual_plan_with_period_concept_and_structural_semantic_columns() {
        String csv = ""
            + "Periodo,Seccion,Cuenta,Descripcion,Clase de registro,Grupo financiero,Importe EUR,Sentido,Criterio de agregacion\n"
            + "2026-01-01,Explotacion,REV-01,Cuotas abonados,Detalle,Ventas,12000,Ingreso,SUM\n"
            + "2026-01-01,Explotacion,OPE-01,Suministros tienda,Detalle,OPEX,-4500,Salida,SUM\n"
            + "2026-01-01,Explotacion,EBITDA,EBITDA,Indicador,Resultado,-1500,Resultado,DERIVED\n"
            + "2026-01-01,Caja y bancos,,Cash neto,Indicador,Cash neto,-900,Resultado,DERIVED\n"
            + "2026-01-01,Caja y bancos,,Saldo final,Saldo,Saldo final,18000,Saldo,LAST_VALUE\n"
            + "2026-02-01,Explotacion,REV-01,Cuotas abonados,Detalle,Ventas,12100,Ingreso,SUM\n"
            + "2026-02-01,Explotacion,OPE-01,Suministros tienda,Detalle,OPEX,-4300,Salida,SUM\n"
            + "2026-02-01,Explotacion,EBITDA,EBITDA,Indicador,Resultado,-1200,Resultado,DERIVED\n"
            + "2026-02-01,Caja y bancos,,Cash neto,Indicador,Cash neto,-750,Resultado,DERIVED\n"
            + "2026-02-01,Caja y bancos,,Saldo final,Saldo,Saldo final,17250,Saldo,LAST_VALUE\n";

        var result = BudgetLongNormalizer.normalizeToLongCsv(csv.getBytes(StandardCharsets.UTF_8), "7", 10_000, 80);

        assertThat(result.analysisStatus()).isEqualTo("AUTOMATIC_ACCEPTED");
        assertThat(result.headerRow1Based()).isEqualTo(1);
        assertThat(result.headerScore()).isGreaterThan(0.70d);
        assertThat(result.monthKeys()).containsExactly("ENERO", "FEBRERO");
        assertThat(result.sampleRows()).filteredOn(row -> "Cuotas abonados".equals(row.label()))
            .allMatch(row -> row.rowType() == BudgetLongNormalizer.RowType.DETAIL)
            .allMatch(row -> "REVENUE".equals(row.semanticKind()))
            .allMatch(row -> "P_AND_L".equals(row.sectionKind()))
            .allMatch(row -> row.budgetAmount() != null);
        assertThat(result.sampleRows()).filteredOn(row -> "Suministros tienda".equals(row.label()))
            .allMatch(row -> row.rowType() == BudgetLongNormalizer.RowType.DETAIL)
            .allMatch(row -> "OPEX".equals(row.semanticKind()));
        assertThat(result.sampleRows()).filteredOn(row -> "Cash neto".equals(row.label()))
            .allMatch(row -> row.rowType() == BudgetLongNormalizer.RowType.DERIVED_KPI)
            .allMatch(row -> "CASHFLOW".equals(row.sectionKind()));
        assertThat(result.sampleRows()).filteredOn(row -> "Saldo final".equals(row.label()))
            .allMatch(row -> row.rowType() == BudgetLongNormalizer.RowType.DERIVED_KPI)
            .allMatch(row -> "CASHFLOW".equals(row.sectionKind()));
    }

    @Test
    void accepts_explicit_nature_enums_and_repeated_headers_without_literal_totals() {
        String csv = ""
            + "Documento anual,,,,,,,,,,,,,,,,\n"
            + "Escenario base,,,,,,,,,,,,,,,,\n"
            + "Codigo,Concepto,Naturaleza,Enero,Febrero,Marzo,Abril,Mayo,Junio,Julio,Agosto,Septiembre,Octubre,Noviembre,Diciembre,Total anual\n"
            + "700.1,Ventas sala,REVENUE,10,10,10,10,10,10,10,10,10,10,10,10,120\n"
            + ",Subvencion digital,OTHER_OPERATING_INCOME,0,0,0,0,0,0,0,0,12,0,0,0,12\n"
            + ",TOTAL INGRESOS,SUBTOTAL_REVENUE,10,10,10,10,10,10,10,10,22,10,10,10,132\n"
            + ",Variacion stock,OPERATING_ADJUSTMENT,0,0,-2,0,0,0,0,0,0,0,0,1,-1\n"
            + "640,Sueldos,OPEX,5,5,5,5,5,5,5,5,5,5,5,5,60\n"
            + ",EBITDA,DERIVED_KPI,5,5,3,5,5,5,5,5,17,5,5,6,71\n"
            + "681,Amortizacion,DEPRECIATION_AMORTIZATION,1,1,1,1,1,1,1,1,1,1,1,1,12\n"
            + ",RESULTADO NETO,TOTAL_NET_RESULT,4,4,2,4,4,4,4,4,16,4,4,5,59\n"
            + ",,,,,,,,,,,,,,,\n"
            + "Codigo,Concepto,Naturaleza,Enero,Febrero,Marzo,Abril,Mayo,Junio,Julio,Agosto,Septiembre,Octubre,Noviembre,Diciembre,Total anual\n"
            + ",Saldo inicial,OPENING_BALANCE,20,24,28,32,36,40,44,48,52,56,60,64,504\n"
            + ",Cobros clientes,CASHFLOW_INFLOW,10,10,10,10,10,10,10,10,10,10,10,10,120\n"
            + ",IVA trimestral,CASHFLOW_TAX,0,0,-2,0,0,-2,0,0,-2,0,0,-2,-8\n"
            + ",Prestamo recibido,FINANCING_INFLOW,15,0,0,0,0,0,0,0,0,0,0,0,15\n"
            + ",Cuota prestamo,FINANCING_OUTFLOW,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-12\n"
            + ",Saldo final,CLOSING_BALANCE,24,28,32,36,40,44,48,52,56,60,64,68,68\n";

        var result = BudgetLongNormalizer.normalizeToLongCsv(csv.getBytes(StandardCharsets.UTF_8), "7", 10_000, 200);

        assertThat(result.headerRow1Based()).isEqualTo(3);
        assertThat(result.requiresConfirmation()).isFalse();
        assertThat(result.analysisStatus()).isEqualTo("AUTOMATIC_ACCEPTED");
        assertThat(result.sampleRows()).filteredOn(row -> "Subvencion digital".equals(row.label()))
            .allMatch(row -> "REVENUE".equals(row.semanticKind()));
        assertThat(result.sampleRows()).filteredOn(row -> "TOTAL INGRESOS".equals(row.label()))
            .allMatch(row -> row.rowType() == BudgetLongNormalizer.RowType.SUBTOTAL)
            .allMatch(row -> "REVENUE".equals(row.semanticKind()));
        assertThat(result.sampleRows()).filteredOn(row -> "Variacion stock".equals(row.label()))
            .allMatch(row -> "OPERATING_ADJUSTMENT".equals(row.semanticKind()))
            .allMatch(row -> "P_AND_L".equals(row.sectionKind()));
        assertThat(result.sampleRows()).filteredOn(row -> "Prestamo recibido".equals(row.label()))
            .allMatch(row -> "FINANCING_INFLOW".equals(row.semanticKind()))
            .allMatch(row -> "CASHFLOW".equals(row.sectionKind()));
        assertThat(result.sampleRows()).filteredOn(row -> "Cuota prestamo".equals(row.label()))
            .allMatch(row -> "FINANCING_OUTFLOW".equals(row.semanticKind()))
            .allMatch(row -> "CASHFLOW".equals(row.sectionKind()));
        assertThat(result.sampleRows()).filteredOn(row -> "IVA trimestral".equals(row.label()))
            .allMatch(row -> "CASHFLOW_TAX".equals(row.semanticKind()))
            .allMatch(row -> "CASHFLOW".equals(row.sectionKind()));
        assertThat(result.sampleRows()).noneMatch(row -> "Concepto".equals(row.label()));
    }

    @Test
    void keeps_cash_neto_as_cashflow_derived_kpi_in_wide_annual_sources() {
        String csv = ""
            + "Codigo,Concepto,Naturaleza,Enero,Febrero,Marzo,Abril,Mayo,Junio,Julio,Agosto,Septiembre,Octubre,Noviembre,Diciembre\n"
            + ",TOTAL GASTOS OPERATIVOS,SUBTOTAL_OPEX,20,20,20,20,20,20,20,20,20,20,20,20\n"
            + ",RESULTADO NETO,TOTAL_NET_RESULT,10,10,10,10,10,10,10,10,10,10,10,10\n"
            + ",,,,,,,,,,,,,,\n"
            + ",CASH NETO,DERIVED_KPI,5,4,3,2,1,0,-1,-2,-3,-4,-5,-6\n"
            + ",Saldo final,CLOSING_BALANCE,25,29,32,34,35,35,34,32,29,25,20,14\n";

        var result = BudgetLongNormalizer.normalizeToLongCsv(csv.getBytes(StandardCharsets.UTF_8), "7", 10_000, 120);

        assertThat(result.sampleRows()).filteredOn(row -> "CASH NETO".equals(row.label()))
            .allMatch(row -> row.rowType() == BudgetLongNormalizer.RowType.DERIVED_KPI)
            .allMatch(row -> "CASHFLOW".equals(row.sectionKind()))
            .allMatch(row -> "UNKNOWN".equals(row.semanticKind()));
    }

    @Test
    void segments_profit_and_loss_and_cashflow_blocks_without_literal_section_titles() {
        String csv = ""
            + "classification,account,label,enero,febrero,marzo,abril,mayo,junio,julio,agosto,septiembre,octubre,noviembre,diciembre\n"
            + "Revenue,REV-01,Ingresos servicio,100,100,100,100,100,100,100,100,100,100,100,100\n"
            + "Opex,EXP-01,Sueldos equipo,40,40,40,40,40,40,40,40,40,40,40,40\n"
            + "Opening balance,,Caja arranque,50,90,130,170,210,250,290,330,370,410,450,490\n"
            + "Cashflow inflow,,Cobros clientes,100,100,100,100,100,100,100,100,100,100,100,100\n"
            + "Cashflow outflow,,Pagos proveedores,-60,-60,-60,-60,-60,-60,-60,-60,-60,-60,-60,-60\n"
            + "Closing balance,,Caja cierre,90,130,170,210,250,290,330,370,410,450,490,530\n";

        var result = BudgetLongNormalizer.normalizeToLongCsv(csv.getBytes(StandardCharsets.UTF_8), "7", 10_000, 80);

        assertThat(result.sampleRows()).filteredOn(row -> "Ingresos servicio".equals(row.label()))
            .allMatch(row -> "P_AND_L".equals(row.sectionKind()));
        assertThat(result.sampleRows()).filteredOn(row -> "Cobros clientes".equals(row.label()) || "Pagos proveedores".equals(row.label()) || "Caja cierre".equals(row.label()))
            .allMatch(row -> "CASHFLOW".equals(row.sectionKind()));
        assertThat(result.sampleRows()).extracting(BudgetLongNormalizer.LongRow::blockId)
            .contains("P_AND_L-2", "CASHFLOW-3");
    }

    @Test
    void falls_back_to_derived_detail_logic_when_no_literal_totals_exist() {
        String csv = ""
            + "tipo,codigo,partida,jan,feb,mar,apr,may,jun,jul,aug,sep,oct,nov,dec\n"
            + "ingreso,RV-01,Licencias,100,100,100,100,100,100,100,100,100,100,100,100\n"
            + "gasto,OP-01,Infraestructura,35,35,35,35,35,35,35,35,35,35,35,35\n"
            + "amortizacion,DA-01,Depreciation,10,10,10,10,10,10,10,10,10,10,10,10\n";

        var result = BudgetLongNormalizer.normalizeToLongCsv(csv.getBytes(StandardCharsets.UTF_8), "7", 10_000, 50);

        assertThat(result.requiresConfirmation()).isFalse();
        assertThat(result.sampleRows()).filteredOn(row -> "Licencias".equals(row.label()))
            .allMatch(row -> row.rowType() == BudgetLongNormalizer.RowType.DETAIL)
            .allMatch(row -> "REVENUE".equals(row.semanticKind()));
        assertThat(result.sampleRows()).filteredOn(row -> "Infraestructura".equals(row.label()))
            .allMatch(row -> row.rowType() == BudgetLongNormalizer.RowType.DETAIL)
            .allMatch(row -> "OPEX".equals(row.semanticKind()));
        assertThat(result.sampleRows()).filteredOn(row -> "Depreciation".equals(row.label()))
            .allMatch(row -> "DEPRECIATION_AMORTIZATION".equals(row.semanticKind()));
    }

    @Test
    void keeps_guided_review_when_structure_exists_but_critical_nature_is_ambiguous() {
        String csv = ""
            + "categoria,codigo,detalle,enero,febrero,marzo,abril,mayo,junio,julio,agosto,septiembre,octubre,noviembre,diciembre\n"
            + "mixto,MIX-01,Elemento transversal,10,10,10,10,10,10,10,10,10,10,10,10\n"
            + "mixto,MIX-02,Elemento sin naturaleza clara,12,12,12,12,12,12,12,12,12,12,12,12\n";

        var result = BudgetLongNormalizer.normalizeToLongCsv(csv.getBytes(StandardCharsets.UTF_8), "7", 10_000, 30);

        assertThat(result.monthKeys()).hasSize(12);
        assertThat(result.requiresConfirmation()).isTrue();
        assertThat(result.analysisStatus()).isEqualTo("GUIDED_REVIEW_REQUIRED");
    }

    @Test
    void rejects_incompatible_sources_only_when_monthly_financial_structure_is_missing() {
        String csv = ""
            + "nota,valor\n"
            + "comentario general,ok\n"
            + "escenario,base\n";

        var result = BudgetLongNormalizer.normalizeToLongCsv(csv.getBytes(StandardCharsets.UTF_8), "7", 10_000, 30);

        assertThat(result.analysisStatus()).isEqualTo("INCOMPATIBLE");
        assertThat(result.longCsvBytes()).isEmpty();
    }

    @Test
    void anti_overfit_production_code_does_not_embed_fixture_literals() throws IOException {
        String source = Files.walk(Path.of("src", "main"))
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java") || path.toString().endsWith(".html") || path.toString().endsWith(".css") || path.toString().endsWith(".yml") || path.toString().endsWith(".yaml") || path.toString().endsWith(".json"))
            .map(path -> {
                try {
                    return Files.readString(path, StandardCharsets.UTF_8);
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            })
            .reduce("", (left, right) -> left + "\n" + right);

        assertThat(source)
            .doesNotContain("Compras Cafe Especial")
            .doesNotContain("Compras Harina Premium")
            .doesNotContain("Compras Componentes IoT")
            .doesNotContain("Saldo puente laboratorio")
            .doesNotContain("Cobros satelite demo");
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

package com.asecon.enterpriseiq.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetSemanticResolverTest {

    @ParameterizedTest
    @MethodSource("economicConceptSamples")
    void resolves_core_economic_concepts_without_file_specific_rules(String rawLabel, String expectedConcept) {
        var inference = BudgetSemanticResolver.classifyBusinessNature("7", rawLabel);

        assertThat(inference.concept()).isEqualTo(expectedConcept);
        assertThat(inference.score()).isGreaterThanOrEqualTo(0.58d);
        assertThat(inference.confidence()).isIn("MEDIUM", "HIGH");
    }

    @Test
    void maps_heterogeneous_budget_headers_to_canonical_columns() {
        List<String> headers = List.of(
            "Ejercicio_Mes",
            "Epigrafe",
            "Cuenta_contable",
            "Importe_Ppto_EUR",
            "Importe_Ejecutado_EUR",
            "FCST_Cierre",
            "Desvio_vs_Ppto",
            "Centro_Analitico",
            "Moneda"
        );
        List<Map<String, String>> sampleRows = List.of(
            Map.of(
                "Ejercicio_Mes", "2026-01",
                "Epigrafe", "Servicios recurrentes",
                "Cuenta_contable", "705.10",
                "Importe_Ppto_EUR", "12000",
                "Importe_Ejecutado_EUR", "11850",
                "FCST_Cierre", "12100",
                "Desvio_vs_Ppto", "-150",
                "Centro_Analitico", "Operaciones",
                "Moneda", "EUR"
            ),
            Map.of(
                "Ejercicio_Mes", "2026-02",
                "Epigrafe", "Subvencion explotacion",
                "Cuenta_contable", "740.20",
                "Importe_Ppto_EUR", "600",
                "Importe_Ejecutado_EUR", "580",
                "FCST_Cierre", "590",
                "Desvio_vs_Ppto", "-20",
                "Centro_Analitico", "Direccion",
                "Moneda", "EUR"
            )
        );

        var resolution = BudgetSemanticResolver.resolve(headers, sampleRows, "7");

        assertThat(resolution.headerFor("PERIOD")).isEqualTo("Ejercicio_Mes");
        assertThat(resolution.headerFor("CONCEPT_NAME")).isEqualTo("Epigrafe");
        assertThat(resolution.headerFor("CONCEPT_CODE")).isEqualTo("Cuenta_contable");
        assertThat(resolution.headerFor("BUDGET_AMOUNT")).isEqualTo("Importe_Ppto_EUR");
        assertThat(resolution.headerFor("ACTUAL_AMOUNT")).isEqualTo("Importe_Ejecutado_EUR");
        assertThat(resolution.headerFor("FORECAST_AMOUNT")).isEqualTo("FCST_Cierre");
        assertThat(resolution.headerFor("VARIANCE")).isEqualTo("Desvio_vs_Ppto");
        assertThat(resolution.headerFor("COST_CENTER")).isEqualTo("Centro_Analitico");
        assertThat(resolution.headerFor("CURRENCY")).isEqualTo("Moneda");
        assertThat(resolution.requiresConfirmation()).isFalse();
    }

    @Test
    void normalizes_common_spanish_budget_abbreviations_before_matching() {
        String normalized = BudgetSemanticResolver.normalize("Ppto mensual / Desvio vs budget / Seg Soc / Subv / Amort / P&L / COGS / RRHH / FX");

        assertThat(normalized)
            .contains("presupuesto")
            .contains("desviacion")
            .contains("seguridad social")
            .contains("subvencion")
            .contains("amortizacion")
            .contains("profit loss")
            .contains("cost of goods sold")
            .contains("personal")
            .contains("exchange");
    }

    @Test
    void detects_explicit_financial_nature_header_in_long_budget_formats() {
        List<String> headers = List.of("period_key", "financial_nature", "label", "budget_amount");
        List<Map<String, String>> sampleRows = List.of(
            Map.of(
                "period_key", "2026-01",
                "financial_nature", "REVENUE",
                "label", "Cuotas recurrentes",
                "budget_amount", "15000"
            ),
            Map.of(
                "period_key", "2026-02",
                "financial_nature", "CASHFLOW_OUTFLOW",
                "label", "Pago de alquiler",
                "budget_amount", "4200"
            ),
            Map.of(
                "period_key", "2026-03",
                "financial_nature", "CAPEX",
                "label", "Equipo informatico",
                "budget_amount", "3200"
            )
        );

        var inference = BudgetSemanticResolver.detectNatureHeader(headers, sampleRows);

        assertThat(inference).isNotNull();
        assertThat(inference.header()).isEqualTo("financial_nature");
        assertThat(inference.score()).isGreaterThanOrEqualTo(0.65d);
    }

    @Test
    void does_not_confuse_recarga_commissions_with_opex_due_to_embedded_gas_token() {
        var inference = BudgetSemanticResolver.classifyBusinessNature("7", "Comisiones de recargas, loterias y paqueteria");

        assertThat(inference.concept()).isNotEqualTo("OPEX");
        assertThat(inference.concept()).isNotEqualTo("FINANCIAL_EXPENSE");
    }

    private static Stream<Arguments> economicConceptSamples() {
        return Stream.of(
            Arguments.of("Ventas suscripciones", "REVENUE"),
            Arguments.of("Facturacion servicios recurrentes", "REVENUE"),
            Arguments.of("Cuotas de abonados", "REVENUE"),
            Arguments.of("Managed service revenue", "REVENUE"),
            Arguments.of("Commission income", "REVENUE"),
            Arguments.of("Subvencion explotacion", "OTHER_OPERATING_INCOME"),
            Arguments.of("Otros ingresos operativos", "OTHER_OPERATING_INCOME"),
            Arguments.of("Supplier rebate operativo", "OTHER_OPERATING_INCOME"),
            Arguments.of("Sueldos y seguridad social", "OPEX"),
            Arguments.of("Servicios exteriores y mantenimiento", "OPEX"),
            Arguments.of("Servicios subcontratados", "OPEX"),
            Arguments.of("Infraestructura cloud", "OPEX"),
            Arguments.of("Electricidad, agua e internet", "OPEX"),
            Arguments.of("Wages and social charges", "OPEX"),
            Arguments.of("Merchant fees and card processing", "OPEX"),
            Arguments.of("Travel expense and software license", "OPEX"),
            Arguments.of("Compra maquinaria y equipos", "CAPEX"),
            Arguments.of("Reforma instalaciones local", "CAPEX"),
            Arguments.of("Leasehold improvements", "CAPEX"),
            Arguments.of("Hardware purchase", "CAPEX"),
            Arguments.of("Regularizacion de stock", "OPERATING_ADJUSTMENT"),
            Arguments.of("Dotacion amortizacion software", "DEPRECIATION_AMORTIZATION"),
            Arguments.of("Intereses cobrados deposito", "FINANCIAL_INCOME"),
            Arguments.of("Positive exchange difference", "FINANCIAL_INCOME"),
            Arguments.of("Comision bancaria", "FINANCIAL_EXPENSE"),
            Arguments.of("Negative exchange difference", "FINANCIAL_EXPENSE"),
            Arguments.of("Resultado financiero neto", "FINANCIAL_RESULT"),
            Arguments.of("Rappel proveedor operativo", "OTHER_OPERATING_INCOME"),
            Arguments.of("Canon facturado recurrente", "REVENUE"),
            Arguments.of("Aportacion socios", "FINANCING_INFLOW"),
            Arguments.of("Devolucion prestamo principal", "FINANCING_OUTFLOW"),
            Arguments.of("Cobros de clientes", "CASH_INFLOW"),
            Arguments.of("Customer cash receipts", "CASH_INFLOW"),
            Arguments.of("Pagos a proveedores", "CASH_OUTFLOW"),
            Arguments.of("Payroll payments", "CASH_OUTFLOW"),
            Arguments.of("Pago impuesto sociedades", "CASHFLOW_TAX"),
            Arguments.of("Pago fraccionado impuesto sociedades", "CASHFLOW_TAX"),
            Arguments.of("Impuesto sobre beneficios", "TAX"),
            Arguments.of("Deferred tax expense", "TAX"),
            Arguments.of("Saldo apertura caja", "OPENING_BALANCE"),
            Arguments.of("Saldo cierre banco", "CLOSING_BALANCE"),
            Arguments.of("Crecimiento volumen y ticket medio", "ASSUMPTION"),
            Arguments.of("Occupancy rate and average basket", "ASSUMPTION"),
            Arguments.of("Churn assumption and average order value", "ASSUMPTION")
        );
    }

    @Test
    void does_not_accept_generic_category_headers_as_explicit_nature_without_semantic_values() {
        List<String> headers = List.of("categoria", "codigo", "detalle");
        List<Map<String, String>> sampleRows = List.of(
            Map.of("categoria", "mixto", "codigo", "MIX-01", "detalle", "Elemento transversal"),
            Map.of("categoria", "mixto", "codigo", "MIX-02", "detalle", "Elemento sin naturaleza clara")
        );

        var inference = BudgetSemanticResolver.detectNatureHeader(headers, sampleRows);

        assertThat(inference).isNull();
    }
}

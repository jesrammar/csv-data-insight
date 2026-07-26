package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.UniversalSummaryDto;
import com.asecon.enterpriseiq.metrics.ErrorTagger;
import com.asecon.enterpriseiq.model.Plan;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UniversalIntakeDiagnosisServiceTest {

    @Test
    void classifies_accounting_ledger_as_universal_accounting_flow() throws Exception {
        UniversalSummaryDto summary = service().analyzePreview(
            "dataset_contable_consultoria_ficticia_2025.csv",
            UniversalCsvServiceSemanticTest.buildAccountingDataset().getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8,
            Plan.PLATINUM,
            Instant.parse("2026-07-22T09:00:00Z")
        );

        assertThat(summary.intakeDiagnosis()).isNotNull();
        assertThat(summary.intakeDiagnosis().kind()).isEqualTo("ACCOUNTING_LEDGER");
        assertThat(summary.intakeDiagnosis().recommendedRoute()).isEqualTo("/universal");
        assertThat(summary.intakeDiagnosis().headline()).contains("mayor contable");
    }

    @Test
    void classifies_mixed_budget_headers_as_annual_budget() throws Exception {
        String csv = ""
            + "Posting_Period,Concept_Label,Account_Code,Tipo_Operacion,PlanAmount,Importe_Real,Forecast_Cierre,Desviacion_vs_Budget,Currency\n"
            + "2026-01,New ERP rollout,INV-ERP,CAPEX,10000,9500,9800,-500,EUR\n"
            + "2026-02,Monthly retainers,REV-RET,Revenue,30000,29500,30200,-500,EUR\n"
            + "2026-03,Payroll consulting,EXP-PAY,OPEX,12000,11800,12100,-200,EUR\n"
            + "2026-04,Monthly retainers,REV-RET,Revenue,31000,30000,30900,-100,EUR\n"
            + "2026-05,Payroll consulting,EXP-PAY,OPEX,12500,12300,12600,-200,EUR\n"
            + "2026-06,Cloud migration,INV-CLD,CAPEX,8000,7800,7900,-100,EUR\n";

        UniversalSummaryDto summary = service().analyzePreview(
            "plan_anual_consultoria_ficticio_2026.csv",
            csv.getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8,
            Plan.PLATINUM,
            Instant.parse("2026-07-22T09:00:00Z")
        );

        assertThat(summary.intakeDiagnosis()).isNotNull();
        assertThat(summary.intakeDiagnosis().kind()).isEqualTo("ANNUAL_BUDGET");
        assertThat(summary.intakeDiagnosis().recommendedRoute()).isEqualTo("/budget");
        assertThat(summary.intakeDiagnosis().canonicalStatus()).isIn("READY", "NEEDS_CONFIRMATION");
    }

    @Test
    void classifies_cash_movements_as_transactions_flow() throws Exception {
        String csv = ""
            + "fecha_valor,importe,descripcion,contraparte,saldo_final\n"
            + "2026-04-01,1250.00,Cobro factura 123,Cliente X,15000.50\n"
            + "2026-04-02,-85.40,Pago proveedor,Proveedor Y,14915.10\n"
            + "2026-04-03,-640.00,Nomina abril,Equipo,14275.10\n";

        UniversalSummaryDto summary = service().analyzePreview(
            "extracto_caja_2026_04.csv",
            csv.getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8,
            Plan.GOLD,
            Instant.parse("2026-07-22T09:00:00Z")
        );

        assertThat(summary.intakeDiagnosis()).isNotNull();
        assertThat(summary.intakeDiagnosis().kind()).isEqualTo("CASH_TRANSACTIONS");
        assertThat(summary.intakeDiagnosis().recommendedRoute()).isEqualTo("/imports?mode=transactions");
        assertThat(summary.intakeDiagnosis().canonicalDetail()).contains("Caja");
    }

    private static UniversalCsvService service() {
        return new UniversalCsvService(
            null,
            null,
            null,
            null,
            null,
            50_000,
            15_000,
            50_000,
            100_000,
            25,
            15,
            25,
            45,
            new SimpleMeterRegistry(),
            new ErrorTagger(40, "other")
        );
    }
}

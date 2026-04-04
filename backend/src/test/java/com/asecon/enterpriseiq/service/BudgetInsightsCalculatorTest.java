package com.asecon.enterpriseiq.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetInsightsCalculatorTest {
    @Test
    void computes_top_drivers_and_zero_months() {
        String csv = ""
            + "Concepto,ENERO,FEBRERO,MARZO,ABRIL,MAYO,JUNIO,JULIO,AGOSTO,SEPTIEMBRE,OCTUBRE,NOVIEMBRE,DICIEMBRE\n"
            + "700-8 Ingresos Trigo,10,0,5,0,0,0,0,0,0,0,0,0\n"
            + "700-2 Ingresos Girasol,20,20,20,20,20,20,20,20,20,20,20,20\n"
            + "640 Sueldos y salarios,5,5,5,5,5,5,5,5,5,5,5,5\n"
            + "TOTAL INGRESOS DE EXPLOTACION,999,999,999,999,999,999,999,999,999,999,999,999\n";

        var insights = BudgetInsightsCalculator.compute("x.csv", Instant.now(), csv.getBytes(StandardCharsets.UTF_8), 1000);
        assertThat(insights.itemCount()).isEqualTo(3); // totals row excluded
        assertThat(insights.topDrivers()).isNotEmpty();
        assertThat(insights.topDrivers().get(0).code()).isEqualTo("700-2");
        assertThat(insights.zeroHeavyItems().stream().anyMatch(i -> "700-8".equals(i.code()))).isTrue();
        assertThat(insights.monthTotals()).isNotEmpty();
    }
}

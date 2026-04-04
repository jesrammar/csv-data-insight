package com.asecon.enterpriseiq.service;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetLongNormalizerTest {
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
}

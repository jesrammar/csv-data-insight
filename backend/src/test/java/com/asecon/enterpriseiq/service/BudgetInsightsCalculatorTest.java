package com.asecon.enterpriseiq.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetInsightsCalculatorTest {
    @Test
    void computes_insights_from_canonical_long_budget_csv() {
        String csv = ""
            + "row_type,code,label,semantic_kind,section_kind,block_id,source_row,month_key,month_label,amount,mapping_status\n"
            + "DETAIL,ING-ERP,Consultoria ERP,REVENUE,P_AND_L,ROW-1,1,ENERO,Enero,73500.0,CANONICAL\n"
            + "DETAIL,GTO-PERS,Equipo consultoria,OPEX,P_AND_L,ROW-2,2,ENERO,Enero,42500.0,CANONICAL\n"
            + "DETAIL,ING-ERP,Consultoria ERP,REVENUE,P_AND_L,ROW-3,3,FEBRERO,Febrero,75600.0,CANONICAL\n"
            + "DETAIL,GTO-PERS,Equipo consultoria,OPEX,P_AND_L,ROW-4,4,FEBRERO,Febrero,43000.0,CANONICAL\n"
            + "DETAIL,628,Suministros,OPEX,CASHFLOW,ROW-5,5,FEBRERO,Febrero,999999.0,CANONICAL\n"
            + "DETAIL,700-VOID,Linea sin actividad,REVENUE,P_AND_L,ROW-6,6,ENERO,Enero,0.0,CANONICAL\n"
            + "DETAIL,700-VOID,Linea sin actividad,REVENUE,P_AND_L,ROW-6,6,FEBRERO,Febrero,0.0,CANONICAL\n"
            + "TOTAL,SALDO,Saldo acumulado de tesoreria,CLOSING_BALANCE,CASHFLOW,ROW-7,7,FEBRERO,Febrero,999999.0,CANONICAL\n";

        var result = BudgetInsightsCalculator.compute("plan.csv", Instant.parse("2026-07-22T10:00:00Z"), csv.getBytes(StandardCharsets.UTF_8), 1000);

        assertThat(result.itemCount()).isEqualTo(3);
        assertThat(result.monthTotals()).hasSize(2);
        assertThat(result.topDrivers()).isNotEmpty();
        assertThat(result.topDrivers().get(0).code()).isEqualTo("ING-ERP");
        assertThat(result.topDrivers()).allMatch(item -> !"CLOSING_BALANCE".equals(item.semanticKind()));
        assertThat(result.topDrivers()).allMatch(item -> !"628".equals(item.code()));
    }
}

package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.DashboardInsightDto;
import com.asecon.enterpriseiq.dto.DashboardMetricDto;
import com.asecon.enterpriseiq.dto.MacroMetricDto;
import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.repo.KpiMonthlyRepository;
import com.asecon.enterpriseiq.repo.TransactionRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;

@Service
public class PowerBiExportService {
    private final KpiMonthlyRepository kpiRepository;
    private final TransactionRepository transactionRepository;
    private final DashboardMetricsService dashboardMetricsService;
    private final MacroDataService macroDataService;

    public PowerBiExportService(KpiMonthlyRepository kpiRepository,
                                TransactionRepository transactionRepository,
                                DashboardMetricsService dashboardMetricsService,
                                MacroDataService macroDataService) {
        this.kpiRepository = kpiRepository;
        this.transactionRepository = transactionRepository;
        this.dashboardMetricsService = dashboardMetricsService;
        this.macroDataService = macroDataService;
    }

    public void writeZip(ZipOutputStream zos, Company company, String from, String to) throws IOException {
        String safeFrom = normalizePeriod(from);
        String safeTo = normalizePeriod(to);

        List<String> periods = periodsBetween(safeFrom, safeTo);

        writeTextEntry(zos, "README_POWERBI.txt", readme(company, safeFrom, safeTo));
        writeCompany(zos, company);
        writePeriods(zos, company, periods);
        writeKpis(zos, company, safeFrom, safeTo);
        writeDashboardMetrics(zos, company, safeFrom, safeTo);
        writeMacroContext(zos, company, periods);

        if (company.getPlan() != null && company.getPlan().isAtLeast(Plan.PLATINUM)) {
            writeTransactions(zos, company, safeFrom, safeTo);
        } else {
            writeTextEntry(zos, "transactions__NOT_INCLUDED.txt", "Plan requerido: PLATINUM.\n");
        }
    }

    private void writeCompany(ZipOutputStream zos, Company company) throws IOException {
        putEntry(zos, "company.csv");
        writeLine(zos, "company_id,name,plan\n");
        writeLine(zos, csv(company.getId()) + "," + csv(company.getName()) + "," + csv(company.getPlan() == null ? "" : company.getPlan().name()) + "\n");
        zos.closeEntry();
    }

    private void writePeriods(ZipOutputStream zos, Company company, List<String> periods) throws IOException {
        putEntry(zos, "dim_period.csv");
        writeLine(zos, "company_id,period,year,month\n");
        for (String p : periods) {
            YearMonth ym = YearMonth.parse(p);
            writeLine(zos, csv(company.getId()) + "," + csv(p) + "," + csv(ym.getYear()) + "," + csv(ym.getMonthValue()) + "\n");
        }
        zos.closeEntry();
    }

    private void writeKpis(ZipOutputStream zos, Company company, String from, String to) throws IOException {
        var kpis = kpiRepository.findByCompanyIdAndPeriodBetweenOrderByPeriodAsc(company.getId(), from, to);
        putEntry(zos, "fact_kpi_monthly.csv");
        writeLine(zos, "company_id,period,inflows,outflows,net_flow,ending_balance\n");
        for (var k : kpis) {
            writeLine(zos,
                csv(company.getId()) + "," +
                    csv(k.getPeriod()) + "," +
                    csv(k.getInflows()) + "," +
                    csv(k.getOutflows()) + "," +
                    csv(k.getNetFlow()) + "," +
                    csv(k.getEndingBalance()) + "\n"
            );
        }
        zos.closeEntry();
    }

    private void writeDashboardMetrics(ZipOutputStream zos, Company company, String from, String to) throws IOException {
        var kpis = kpiRepository.findByCompanyIdAndPeriodBetweenOrderByPeriodAsc(company.getId(), from, to);
        var computed = dashboardMetricsService.compute(kpis);
        List<DashboardMetricDto> metrics = computed.metrics() == null ? List.of() : computed.metrics();
        List<DashboardInsightDto> insights = computed.insights() == null ? List.of() : computed.insights();

        putEntry(zos, "dashboard_metrics.csv");
        writeLine(zos, "company_id,key,label,value,tier\n");
        for (DashboardMetricDto m : metrics) {
            writeLine(zos,
                csv(company.getId()) + "," +
                    csv(m.getKey()) + "," +
                    csv(m.getLabel()) + "," +
                    csv(m.getValue()) + "," +
                    csv(m.getTier()) + "\n"
            );
        }
        zos.closeEntry();

        putEntry(zos, "dashboard_insights.csv");
        writeLine(zos, "company_id,title,detail,severity,tier\n");
        for (DashboardInsightDto i : insights) {
            writeLine(zos,
                csv(company.getId()) + "," +
                    csv(i.getTitle()) + "," +
                    csv(i.getDetail()) + "," +
                    csv(i.getSeverity()) + "," +
                    csv(i.getTier()) + "\n"
            );
        }
        zos.closeEntry();
    }

    private void writeMacroContext(ZipOutputStream zos, Company company, List<String> periods) throws IOException {
        putEntry(zos, "macro_context.csv");
        writeLine(zos, "company_id,period,inflation_yoy_pct,euribor_1y_pct,usd_eur\n");
        for (String p : periods) {
            var ctx = macroDataService.context(p);
            String ipc = metricValue(ctx.inflationYoyPct());
            String eur = metricValue(ctx.euribor1yPct());
            String usd = metricValue(ctx.usdPerEur());
            writeLine(zos,
                csv(company.getId()) + "," +
                    csv(p) + "," +
                    csv(ipc) + "," +
                    csv(eur) + "," +
                    csv(usd) + "\n"
            );
        }
        zos.closeEntry();
    }

    private void writeTransactions(ZipOutputStream zos, Company company, String from, String to) throws IOException {
        var rows = transactionRepository.findByCompanyIdAndPeriodBetweenOrderByPeriodAscTxnDateAsc(company.getId(), from, to);
        putEntry(zos, "fact_transactions.csv");
        writeLine(zos, "id,company_id,period,txn_date,amount,description,counterparty\n");
        for (var tx : rows) {
            writeLine(zos,
                csv(tx.getId()) + "," +
                    csv(company.getId()) + "," +
                    csv(tx.getPeriod()) + "," +
                    csv(tx.getTxnDate()) + "," +
                    csv(tx.getAmount()) + "," +
                    csv(tx.getDescription()) + "," +
                    csv(tx.getCounterparty()) + "\n"
            );
        }
        zos.closeEntry();
    }

    private static void putEntry(ZipOutputStream zos, String name) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
    }

    private static void writeTextEntry(ZipOutputStream zos, String name, String content) throws IOException {
        putEntry(zos, name);
        writeLine(zos, content == null ? "" : content);
        if (!content.endsWith("\n")) writeLine(zos, "\n");
        zos.closeEntry();
    }

    private static void writeLine(ZipOutputStream zos, String s) throws IOException {
        zos.write(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String readme(Company company, String from, String to) {
        String plan = company.getPlan() == null ? "UNKNOWN" : company.getPlan().name();
        return """
            EnterpriseIQ - Export para Power BI Desktop
            =========================================

            Empresa: %s (id=%s)
            Plan: %s
            Rango: %s -> %s

            Archivos incluidos:
            - company.csv (dimensión compañía)
            - dim_period.csv (dimensión periodo YYYY-MM)
            - fact_kpi_monthly.csv (KPIs mensuales)
            - dashboard_metrics.csv (métricas derivadas del dashboard)
            - dashboard_insights.csv (insights derivados del dashboard)
            - macro_context.csv (IPC YoY, Euribor 1 año, USD/EUR)
            - fact_transactions.csv (solo si el plan es PLATINUM)

            Cómo usarlo (rápido):
            1) Abre Power BI Desktop.
            2) "Obtener datos" -> "Texto/CSV" e importa todos los CSV.
            3) Relaciones recomendadas:
               - company[company_id] 1-* fact_kpi_monthly[company_id]
               - company[company_id] 1-* fact_transactions[company_id] (si existe)
               - dim_period[period] 1-* fact_kpi_monthly[period]
               - dim_period[period] 1-* macro_context[period]

            Notas:
            - Si no ves fact_transactions.csv, sube el plan a PLATINUM para exportar detalle.
            - Para gráficos tipo Power BI dentro de EnterpriseIQ, usa los dashboards web (ECharts).
            """.formatted(
            company.getName() == null ? "" : company.getName(),
            String.valueOf(company.getId()),
            plan,
            from,
            to
        );
    }

    private static String normalizePeriod(String p) {
        if (p == null) throw new IllegalArgumentException("Missing period");
        String s = p.trim();
        if (s.length() < 7) throw new IllegalArgumentException("Invalid period");
        return s.substring(0, 7);
    }

    private static List<String> periodsBetween(String from, String to) {
        YearMonth a = YearMonth.parse(from);
        YearMonth b = YearMonth.parse(to);
        if (b.isBefore(a)) {
            YearMonth tmp = a;
            a = b;
            b = tmp;
        }
        List<String> out = new ArrayList<>();
        YearMonth cur = a;
        while (!cur.isAfter(b)) {
            out.add(cur.toString());
            cur = cur.plusMonths(1);
        }
        return out;
    }

    private static String metricValue(MacroMetricDto metric) {
        if (metric == null || metric.value() == null) return null;
        return metric.value().toPlainString();
    }

    private static String csv(Object value) {
        if (value == null) return "";
        String s = String.valueOf(value);
        boolean needsQuotes = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (!needsQuotes) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}

package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.BudgetLongInsightsDto;
import com.asecon.enterpriseiq.dto.BudgetMonthDto;
import com.asecon.enterpriseiq.dto.BudgetSummaryDto;
import com.asecon.enterpriseiq.dto.CashflowSummaryDto;
import com.asecon.enterpriseiq.model.Company;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.text.NumberFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class BudgetReportService {
    private static final Logger log = LoggerFactory.getLogger(BudgetReportService.class);
    private static final String BUDGET_REPORT_TEMPLATE = loadClasspathResource("reports/budget-report-template.html");
    private static final String BUDGET_REPORT_CSS = loadClasspathResource("reports/budget-report.css");
    private static final String BRAND_IMAGE_DATA_URI = "data:image/png;base64," + loadClasspathResource("reports/enterpriseiq-image-base64.txt").replaceAll("\\s+", "");
    private static final Locale REPORT_LOCALE = Locale.forLanguageTag("es-ES");

    private final ReportService reportService;

    public BudgetReportService(ReportService reportService) {
        this.reportService = reportService;
    }

    public byte[] renderBudgetPdf(Company company, BudgetService.BudgetPdfBundle bundle) {
        BudgetTraceLogger.log(log, "budget-pdf-render", BudgetTraceLogger.fields(
            "companyId", company == null ? null : company.getId(),
            "sourceFilename", bundle == null || bundle.meta() == null ? null : bundle.meta().sourceFilename(),
            "planImportId", bundle == null || bundle.meta() == null ? null : bundle.meta().sourceImportId(),
            "analysisVersion", bundle == null || bundle.meta() == null ? null : bundle.meta().analysisVersion(),
            "processingRoute", "BudgetReportService.renderBudgetPdf",
            "income", bundle == null || bundle.summary() == null ? null : BudgetTraceLogger.fmt(bundle.summary().totalIncome()),
            "opex", bundle == null || bundle.summary() == null ? null : BudgetTraceLogger.fmt(bundle.summary().totalExpense()),
            "ebitda", bundle == null || bundle.summary() == null ? null : BudgetTraceLogger.fmt(bundle.summary().totalMargin()),
            "ebit", bundle == null || bundle.summary() == null ? null : BudgetTraceLogger.fmt(bundle.summary().totalEbit()),
            "net", bundle == null || bundle.summary() == null ? null : BudgetTraceLogger.fmt(bundle.summary().netResult()),
            "endingBalance", bundle == null || bundle.cashflow() == null ? null : BudgetTraceLogger.fmt(bundle.cashflow().endingBalance())
        ));
        String html = buildBudgetReportHtml(company, bundle);
        return reportService.renderPdfFromHtml(html);
    }

    public String buildBudgetReportHtml(Company company, BudgetService.BudgetPdfBundle bundle) {
        BudgetSummaryDto summary = bundle == null ? null : bundle.summary();
        CashflowSummaryDto cashflow = bundle == null ? null : bundle.cashflow();
        BudgetLongInsightsDto longInsights = bundle == null ? null : bundle.longInsights();
        String companyName = company == null || company.getName() == null ? "Empresa" : company.getName();
        String filename = bundle != null && bundle.meta() != null && bundle.meta().sourceFilename() != null
            ? safe(bundle.meta().sourceFilename())
            : summary == null ? "" : safe(summary.sourceFilename());
        String generatedAt = ZonedDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        BigDecimal totalIncome = summary == null ? null : summary.totalIncome();
        BigDecimal totalExpense = summary == null ? null : summary.totalExpense();
        BigDecimal totalMargin = summary == null ? null : summary.totalMargin();
        BigDecimal totalEbit = summary == null ? null : summary.totalEbit();
        BigDecimal totalNet = summary == null ? null : summary.netResult();
        BigDecimal endingBalance = cashflow == null ? null : cashflow.endingBalance();
        String bestMonth = summary == null ? null : summary.bestMonth();
        String worstMonth = summary == null ? null : summary.worstMonth();

        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("REPORT_CSS", BUDGET_REPORT_CSS);
        placeholders.put("BRAND_IMAGE_SRC", BRAND_IMAGE_DATA_URI);
        placeholders.put("COMPANY_NAME", escape(companyName));
        placeholders.put("SOURCE_FILENAME", escape(filename));
        placeholders.put("GENERATED_AT", escape(generatedAt));
        placeholders.put("TOTAL_INCOME", escape(fmtMoney(totalIncome)));
        placeholders.put("TOTAL_EXPENSE", escape(fmtMoney(totalExpense)));
        placeholders.put("TOTAL_MARGIN", escape(fmtMoney(totalMargin)));
        placeholders.put("TOTAL_EBIT", escape(fmtMoney(totalEbit)));
        placeholders.put("TOTAL_NET", escape(fmtMoney(totalNet)));
        placeholders.put("TOTAL_CLOSING", escape(fmtMoney(endingBalance)));
        placeholders.put("COVER_KPI_HTML", buildCoverKpis(totalIncome, totalExpense, totalMargin, totalEbit, totalNet, endingBalance));
        placeholders.put("EXECUTIVE_HTML", buildExecutive(summary, cashflow, longInsights));
        placeholders.put("MINI_TOTALS_HTML", buildMiniMonthTotals(longInsights));
        placeholders.put("BEST_MONTH", escape(bestMonth == null ? "-" : bestMonth));
        placeholders.put("WORST_MONTH", escape(worstMonth == null ? "-" : worstMonth));
        placeholders.put("RECOMMENDATIONS_HTML", buildRecommendations(summary, longInsights));
        placeholders.put("ZERO_HEAVY_HTML", buildZeroHeavyList(longInsights));
        placeholders.put("MONTH_TABLE_HTML", buildMonthTable(summary));
        placeholders.put("TOP_DRIVERS_HTML", buildTopDriversTable(longInsights));
        return applyTemplate(BUDGET_REPORT_TEMPLATE, placeholders);
    }

    private String buildCoverKpis(BigDecimal totalIncome,
                                  BigDecimal totalExpense,
                                  BigDecimal totalMargin,
                                  BigDecimal totalEbit,
                                  BigDecimal totalNet,
                                  BigDecimal endingBalance) {
        return """
          <table class='cover-kpi-table'>
            <tr>
              <td>
                <div class='cover-kpi-card'>
                  <div class='cover-kpi-label'>Ingresos</div>
                  <div class='cover-kpi-value'>%s</div>
                </div>
              </td>
              <td>
                <div class='cover-kpi-card'>
                  <div class='cover-kpi-label'>OPEX</div>
                  <div class='cover-kpi-value'>%s</div>
                </div>
              </td>
              <td>
                <div class='cover-kpi-card'>
                  <div class='cover-kpi-label'>EBITDA</div>
                  <div class='cover-kpi-value'>%s</div>
                </div>
              </td>
            </tr>
            <tr>
              <td>
                <div class='cover-kpi-card'>
                  <div class='cover-kpi-label'>EBIT</div>
                  <div class='cover-kpi-value'>%s</div>
                </div>
              </td>
              <td>
                <div class='cover-kpi-card'>
                  <div class='cover-kpi-label'>Resultado neto</div>
                  <div class='cover-kpi-value'>%s</div>
                </div>
              </td>
              <td>
                <div class='cover-kpi-card'>
                  <div class='cover-kpi-label'>Saldo final</div>
                  <div class='cover-kpi-value'>%s</div>
                </div>
              </td>
            </tr>
          </table>
        """.formatted(
            escape(fmtMoney(totalIncome)),
            escape(fmtMoney(totalExpense)),
            escape(fmtMoney(totalMargin)),
            escape(fmtMoney(totalEbit)),
            escape(fmtMoney(totalNet)),
            escape(fmtMoney(endingBalance))
        );
    }

    private String buildExecutive(BudgetSummaryDto summary, CashflowSummaryDto cashflow, BudgetLongInsightsDto longInsights) {
        if (summary == null) {
            return "<div class='muted'>Sin datos de presupuesto.</div>";
        }

        String margin = fmtMoney(summary.totalMargin());
        String income = fmtMoney(summary.totalIncome());
        String expense = fmtMoney(summary.totalExpense());
        String closing = cashflow == null ? "-" : fmtMoney(cashflow.endingBalance());
        BigDecimal concentration = longInsights == null ? null : longInsights.concentrationTop3AbsPct();
        String concentrationText = concentration == null
            ? "-"
            : concentration.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";

        return """
          <div class='small'>
            <b>Lectura anual:</b> EBITDA <b>%s</b> con ingresos de <b>%s</b> y gastos operativos de <b>%s</b>.
            <div class='muted top-gap'>
              Saldo final previsto: <b>%s</b>.
            </div>
            <div class='muted top-gap'>
              La concentracion del top 3 de drivers operativos queda en <b>%s</b>.
              %s
            </div>
          </div>
        """.formatted(
            escape(margin),
            escape(income),
            escape(expense),
            escape(closing),
            escape(concentrationText),
            buildTop3DriversInline(longInsights)
        );
    }

    private String buildRecommendations(BudgetSummaryDto summary, BudgetLongInsightsDto longInsights) {
        List<BudgetMonthDto> months = summary == null ? List.of() : summary.months();
        BudgetMonthDto worst = months.stream()
            .filter(m -> m != null && m.margin() != null)
            .min(Comparator.comparing(BudgetMonthDto::margin))
            .orElse(null);

        String worstText = worst == null ? "-" : safe(worst.label()) + " (" + fmtMoney(worst.margin()) + ")";
        MonthExtremes totalsExt = computeMonthExtremes(longInsights);
        String activityLine = totalsExt == null
            ? "Sin lectura clara de actividad agregada."
            : "Pico de actividad: <b>%s</b> (%s). Valle de actividad: <b>%s</b> (%s).".formatted(
                escape(totalsExt.bestMonthLabel),
                escape(fmtMoney(totalsExt.bestTotal)),
                escape(totalsExt.worstMonthLabel),
                escape(fmtMoney(totalsExt.worstTotal))
            );

        BigDecimal concentration = longInsights == null ? null : longInsights.concentrationTop3AbsPct();
        boolean highConcentration = concentration != null && concentration.compareTo(new BigDecimal("55")) >= 0;
        String concentrationText = concentration == null
            ? "-"
            : concentration.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";

        return """
          <div class='small recommendation-block'>
            <span class='badge impact-high'>Impacto alto</span>
            <b class='recommendation-title'>Blindar el mes mas debil</b>
            <div class='muted top-gap'>
              %s Peor mes por margen: <b>%s</b>.
            </div>
          </div>

          <div class='small recommendation-block'>
            <span class='badge %s'>Impacto medio</span>
            <b class='recommendation-title'>Reducir dependencia de pocas partidas</b>
            <div class='muted top-gap'>
              El top 3 concentra <b>%s</b> del peso absoluto analizado. %s %s
            </div>
          </div>

          <div class='small recommendation-block'>
            <span class='badge impact-low'>Impacto bajo</span>
            <b class='recommendation-title'>Cerrar mejor la base anual</b>
            <div class='muted top-gap'>
              %s Mantener una sola tabla, cabeceras limpias y meses completos reduce lecturas parciales.
            </div>
          </div>
        """.formatted(
            activityLine,
            escape(worstText),
            highConcentration ? "impact-med" : "impact-low",
            escape(concentrationText),
            highConcentration
                ? "Conviene revisar supuestos de precio, volumen y escenario alternativo."
                : "Conviene revisar supuestos al menos una vez por trimestre.",
            buildTop3DriversInline(longInsights),
            buildZeroHeavyInline(longInsights)
        );
    }

    private String buildMonthTable(BudgetSummaryDto summary) {
        if (summary == null || summary.months() == null || summary.months().isEmpty()) {
            return "<div class='muted small'>Sin tabla mensual.</div>";
        }

        String rows = summary.months().stream()
            .filter(m -> m != null && m.monthKey() != null)
            .map(m -> """
              <tr>
                <td>%s</td>
                <td class='right'>%s</td>
                <td class='right'>%s</td>
                <td class='right'><b>%s</b></td>
              </tr>
            """.formatted(
                escape(m.label()),
                escape(fmtMoney(m.income())),
                escape(fmtMoney(m.expense())),
                escape(fmtMoney(m.margin()))
            ))
            .reduce("", (a, b) -> a + b);

        return """
          <table class='table'>
            <thead>
              <tr>
                <th>Mes</th>
                <th class='right'>Ingresos</th>
                <th class='right'>Gastos</th>
                <th class='right'>EBITDA</th>
              </tr>
            </thead>
            <tbody>%s</tbody>
          </table>
        """.formatted(rows);
    }

    private String buildTopDriversTable(BudgetLongInsightsDto longInsights) {
        if (longInsights == null || longInsights.topDrivers() == null || longInsights.topDrivers().isEmpty()) {
            return "<div class='muted small'>Sin drivers detectables.</div>";
        }

        String rows = longInsights.topDrivers().stream()
            .limit(10)
            .map(d -> """
              <tr>
                <td>%s</td>
                <td>%s</td>
                <td class='right'>%s</td>
                <td class='right'>%s%%</td>
                <td class='right'>%s</td>
              </tr>
            """.formatted(
                escape(safe(d.code())),
                escape(safe(d.label())),
                escape(fmtMoney(d.annualTotal())),
                escape(d.shareAbsPct() == null ? "0" : d.shareAbsPct().setScale(2, RoundingMode.HALF_UP).toPlainString()),
                escape(String.valueOf(d.zeroMonths()))
            ))
            .reduce("", (a, b) -> a + b);

        return """
          <table class='table'>
            <thead>
              <tr>
                <th>Codigo</th>
                <th>Partida</th>
                <th class='right'>Total anual</th>
                <th class='right'>Peso abs.</th>
                <th class='right'>Meses a 0</th>
              </tr>
            </thead>
            <tbody>%s</tbody>
          </table>
        """.formatted(rows);
    }

    private String buildZeroHeavyList(BudgetLongInsightsDto longInsights) {
        if (longInsights == null || longInsights.zeroHeavyItems() == null || longInsights.zeroHeavyItems().isEmpty()) {
            return """
              <div class='small muted'>
                No se detectan partidas con meses a cero que requieran una revision prioritaria.
              </div>
            """;
        }

        String items = longInsights.zeroHeavyItems().stream()
            .limit(8)
            .map(i -> "<li><b>%s</b> - %s · %s meses a 0</li>".formatted(
                escape(safe(i.code())),
                escape(safe(i.label())),
                escape(String.valueOf(i.zeroMonths()))
            ))
            .reduce("", (a, b) -> a + b);

        return """
          <div class='small'>
            <b>Partidas a revisar por huecos mensuales</b>
            <div class='muted top-gap'>
              Esta lista no implica error automatico. Sirve para distinguir estacionalidad real de posibles faltas de dato.
            </div>
            <ul class='small compact-list'>%s</ul>
          </div>
        """.formatted(items);
    }

    private String buildMiniMonthTotals(BudgetLongInsightsDto longInsights) {
        if (longInsights == null || longInsights.monthTotals() == null || longInsights.monthTotals().isEmpty()) {
            return "";
        }
        var months = longInsights.monthTotals();
        var take = months.size() <= 6 ? months : months.subList(0, 6);

        String rows = take.stream()
            .map(m -> """
              <tr>
                <td>%s</td>
                <td class='right'>%s</td>
              </tr>
            """.formatted(
                escape(safe(m.monthLabel())),
                escape(fmtMoney(m.total()))
            ))
            .reduce("", (a, b) -> a + b);

        return """
          <div class='mini-totals'>
            <div class='small muted'>Mini resumen de control por mes</div>
            <table class='table compact-table'>
              <thead>
                <tr><th>Mes</th><th class='right'>Total</th></tr>
              </thead>
              <tbody>%s</tbody>
            </table>
          </div>
        """.formatted(rows);
    }

    private String buildTop3DriversInline(BudgetLongInsightsDto longInsights) {
        if (longInsights == null || longInsights.topDrivers() == null || longInsights.topDrivers().isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (var driver : longInsights.topDrivers().stream().limit(3).toList()) {
            String code = safe(driver.code());
            String label = safe(driver.label());
            String share = driver.shareAbsPct() == null
                ? ""
                : driver.shareAbsPct().setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
            String name = (code.isBlank() ? "" : code + " · ") + (label.isBlank() ? "Partida" : label);
            parts.add(name + (share.isBlank() ? "" : " (" + share + ")"));
        }
        if (parts.isEmpty()) {
            return "";
        }
        return "<div class='inline-note'>Top 3 actual: <b>%s</b></div>".formatted(escape(String.join(" · ", parts)));
    }

    private String buildZeroHeavyInline(BudgetLongInsightsDto longInsights) {
        if (longInsights == null || longInsights.zeroHeavyItems() == null || longInsights.zeroHeavyItems().isEmpty()) {
            return "No se detectan huecos de completitud relevantes.";
        }
        var first = longInsights.zeroHeavyItems().get(0);
        String code = safe(first.code());
        String label = safe(first.label());
        String name = (code.isBlank() ? "" : code + " · ") + (label.isBlank() ? "Partida" : label);
        return "La primera revision sugerida es <b>%s</b>.".formatted(escape(name));
    }

    private MonthExtremes computeMonthExtremes(BudgetLongInsightsDto longInsights) {
        if (longInsights == null || longInsights.monthTotals() == null || longInsights.monthTotals().isEmpty()) {
            return null;
        }
        var best = longInsights.monthTotals().stream()
            .max(Comparator.comparing(m -> m.total() == null ? BigDecimal.ZERO : m.total()))
            .orElse(null);
        var worst = longInsights.monthTotals().stream()
            .min(Comparator.comparing(m -> m.total() == null ? BigDecimal.ZERO : m.total()))
            .orElse(null);
        if (best == null || worst == null) {
            return null;
        }
        return new MonthExtremes(
            safe(best.monthLabel()),
            best.total() == null ? BigDecimal.ZERO : best.total(),
            safe(worst.monthLabel()),
            worst.total() == null ? BigDecimal.ZERO : worst.total()
        );
    }

    private static String applyTemplate(String template, Map<String, String> placeholders) {
        String resolved = template;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            resolved = resolved.replace("{{" + entry.getKey() + "}}", entry.getValue() == null ? "" : entry.getValue());
        }
        return resolved;
    }

    private static String loadClasspathResource(String location) {
        try (InputStream input = BudgetReportService.class.getClassLoader().getResourceAsStream(location)) {
            if (input == null) {
                throw new IllegalStateException("No se pudo cargar el recurso de presupuesto: " + location);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo leer el recurso de presupuesto: " + location, ex);
        }
    }

    private static String fmtMoney(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        NumberFormat format = NumberFormat.getNumberInstance(REPORT_LOCALE);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        format.setGroupingUsed(true);
        return format.format(value.setScale(2, RoundingMode.HALF_UP)) + " €";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private static final class MonthExtremes {
        final String bestMonthLabel;
        final BigDecimal bestTotal;
        final String worstMonthLabel;
        final BigDecimal worstTotal;

        private MonthExtremes(String bestMonthLabel, BigDecimal bestTotal, String worstMonthLabel, BigDecimal worstTotal) {
            this.bestMonthLabel = bestMonthLabel;
            this.bestTotal = bestTotal;
            this.worstMonthLabel = worstMonthLabel;
            this.worstTotal = worstTotal;
        }
    }
}

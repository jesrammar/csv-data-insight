package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.BudgetLongInsightsDto;
import com.asecon.enterpriseiq.dto.BudgetMonthDto;
import com.asecon.enterpriseiq.dto.BudgetSummaryDto;
import com.asecon.enterpriseiq.model.Company;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class BudgetReportService {
    private final ReportService reportService;

    public BudgetReportService(ReportService reportService) {
        this.reportService = reportService;
    }

    public byte[] renderBudgetPdf(Company company, BudgetSummaryDto summary, BudgetLongInsightsDto longInsights) {
        String html = buildBudgetReportHtml(company, summary, longInsights);
        return reportService.renderPdfFromHtml(html);
    }

    public String buildBudgetReportHtml(Company company, BudgetSummaryDto summary, BudgetLongInsightsDto longInsights) {
        String companyName = company == null || company.getName() == null ? "Empresa" : company.getName();
        String filename = summary == null ? "" : safe(summary.sourceFilename());
        String generatedAt = ZonedDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        BigDecimal totalIncome = summary == null ? null : summary.totalIncome();
        BigDecimal totalExpense = summary == null ? null : summary.totalExpense();
        BigDecimal totalMargin = summary == null ? null : summary.totalMargin();

        String bestMonth = summary == null ? null : summary.bestMonth();
        String worstMonth = summary == null ? null : summary.worstMonth();

        String topDriversHtml = buildTopDriversTable(longInsights);
        String zeroHeavyHtml = buildZeroHeavyList(longInsights);
        String monthTableHtml = buildMonthTable(summary);
        String executiveHtml = buildExecutive(summary, longInsights);
        String recommendationsHtml = buildRecommendations(summary, longInsights);
        String miniTotalsHtml = buildMiniMonthTotals(longInsights);

        return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset='utf-8'/>
          <title>Presupuesto · %s</title>
          <style>
            @page {
              size: A4;
              margin: 18mm 16mm 18mm;
              @bottom-right { content: counter(page) " / " counter(pages); font-size: 10px; color: #64748b; }
            }
            :root {
              --ink: #0b1220;
              --muted: #475569;
              --muted2: #64748b;
              --bg: #ffffff;
              --soft: #f8fafc;
              --border: #e2e8f0;
              --navy: #0b1220;
              --navy2: #0f1b33;
              --accent: #14b8a6;
              --warn: #f59e0b;
              --danger: #fb7185;
              --success: #22c55e;
            }
            body { font-family: Arial, sans-serif; color: var(--ink); background: var(--bg); }
            .muted { color: var(--muted); font-size: 12px; }
            .small { font-size: 12px; }
            .cover {
              padding: 28px 28px 32px;
              border-radius: 16px;
              background: linear-gradient(135deg, var(--navy) 0%%, var(--navy2) 70%%, #082f49 100%%);
              border: 1px solid #1e293b;
              color: #f8fafc;
              position: relative;
              overflow: hidden;
            }
            .cover::before {
              content: "";
              position: absolute;
              inset: -120px -140px auto auto;
              width: 320px;
              height: 320px;
              border-radius: 999px;
              background: radial-gradient(circle at 30%% 30%%, rgba(20,184,166,0.40), rgba(96,165,250,0.10) 55%%, rgba(0,0,0,0) 70%%);
            }
            .brand { font-size: 12px; letter-spacing: .16em; text-transform: uppercase; opacity: 0.88; }
            .title { margin-top: 18px; font-size: 30px; }
            .subtitle { margin-top: 10px; color: rgba(248,250,252,0.78); line-height: 1.5; }
            .grid { display: table; width: 100%%; margin-top: 14px; }
            .grid .col { display: table-cell; vertical-align: top; }
            .pill {
              display: inline-block;
              padding: 6px 10px;
              border-radius: 999px;
              border: 1px solid rgba(248,250,252,0.18);
              background: rgba(15,23,42,0.35);
              font-size: 11px;
              letter-spacing: .06em;
              margin-right: 8px;
            }
            .section { margin-top: 18px; }
            .section-title { font-size: 14px; font-weight: 700; color: #0f172a; margin: 16px 0 8px; }
            .card {
              border: 1px solid var(--border);
              background: var(--soft);
              border-radius: 14px;
              padding: 12px 14px;
            }
            .kpi-row { display: table; width: 100%%; margin-top: 10px; }
            .kpi { display: table-cell; width: 33%%; padding-right: 10px; }
            .kpi .k { color: var(--muted2); font-size: 11px; text-transform: uppercase; letter-spacing: .08em; }
            .kpi .v { font-size: 18px; font-weight: 800; margin-top: 6px; }
            .table { width: 100%%; border-collapse: collapse; margin-top: 10px; }
            .table th { text-align: left; font-size: 11px; color: var(--muted2); border-bottom: 1px solid var(--border); padding: 8px 6px; }
            .table td { font-size: 12px; border-bottom: 1px solid var(--border); padding: 8px 6px; }
            .right { text-align: right; }
            .badge { font-size: 11px; padding: 3px 8px; border-radius: 999px; display: inline-block; border: 1px solid var(--border); background: #fff; }
            .impact-high { border-color: rgba(245,158,11,0.35); background: rgba(245,158,11,0.10); }
            .impact-med { border-color: rgba(96,165,250,0.35); background: rgba(96,165,250,0.10); }
            .impact-low { border-color: rgba(34,197,94,0.35); background: rgba(34,197,94,0.10); }
            .hr { height: 1px; background: var(--border); margin: 14px 0; }
          </style>
        </head>
        <body>
          <div class='cover'>
            <div class='brand'>EnterpriseIQ · Presupuestos</div>
            <div class='title'>Cuenta de explotación · Presupuesto</div>
            <div class='subtitle'>
              %s<br/>
              <span class='muted' style='color: rgba(248,250,252,0.72);'>Fuente: %s · Generado: %s</span>
            </div>
            <div class='grid'>
              <div class='col'>
                <span class='pill'>Ingresos: %s</span>
                <span class='pill'>Gastos: %s</span>
                <span class='pill'>Margen: %s</span>
              </div>
            </div>
          </div>

          <div class='section'>
            <div class='section-title'>Mini resumen</div>
            <div class='card'>
              %s
              %s
              <div class='hr'></div>
              <div class='small muted'>
                Mejor mes (margen): <b>%s</b> · Peor mes (margen): <b>%s</b>
              </div>
            </div>
          </div>

          <div class='section'>
            <div class='section-title'>Insights accionables</div>
            <div class='card'>
              %s
              <div class='hr'></div>
              %s
            </div>
          </div>

          <div class='section'>
            <div class='section-title'>Tabla resumen (mes)</div>
            %s
          </div>

          <div class='section'>
            <div class='section-title'>Top drivers (partidas)</div>
            %s
          </div>

          <div class='section'>
            <div class='section-title'>Notas de calidad</div>
            <div class='card small muted'>
              Este informe se genera automáticamente desde un XLSX subido en el módulo Universal. Si faltan meses o las cabeceras no están en la fila correcta,
              usa el modo guiado (selección de hoja y fila de cabecera) y vuelve a subir el fichero.
            </div>
          </div>
        </body>
        </html>
        """.formatted(
            escape(companyName),
            escape(companyName),
            escape(filename),
            escape(generatedAt),
            escape(fmtMoney(totalIncome)),
            escape(fmtMoney(totalExpense)),
            escape(fmtMoney(totalMargin)),
            executiveHtml,
            miniTotalsHtml,
            escape(bestMonth == null ? "—" : bestMonth),
            escape(worstMonth == null ? "—" : worstMonth),
            recommendationsHtml,
            zeroHeavyHtml,
            monthTableHtml,
            topDriversHtml
        );
    }

    private String buildExecutive(BudgetSummaryDto summary, BudgetLongInsightsDto longInsights) {
        if (summary == null) return "<div class='muted'>Sin datos de presupuesto.</div>";

        String margin = fmtMoney(summary.totalMargin());
        String income = fmtMoney(summary.totalIncome());
        String expense = fmtMoney(summary.totalExpense());
        BigDecimal conc = longInsights == null ? null : longInsights.concentrationTop3AbsPct();

        String concText = conc == null ? "—" : (conc.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%");
        String top3 = buildTop3DriversInline(longInsights);

        return """
          <div class='small'>
            <b>Margen anual:</b> %s · <b>Ingresos:</b> %s · <b>Gastos:</b> %s
            <div class='muted' style='margin-top:8px;'>
              Concentración (top 3 partidas, por peso absoluto): <b>%s</b>
              %s
            </div>
          </div>
        """.formatted(escape(margin), escape(income), escape(expense), escape(concText), top3);
    }

    private String buildRecommendations(BudgetSummaryDto summary, BudgetLongInsightsDto longInsights) {
        List<BudgetMonthDto> months = summary == null ? List.of() : summary.months();
        BudgetMonthDto worst = months.stream()
            .filter(m -> m != null && m.margin() != null)
            .min(Comparator.comparing(BudgetMonthDto::margin))
            .orElse(null);

        String worstText = worst == null ? "—" : (safe(worst.label()) + " (" + fmtMoney(worst.margin()) + ")");
        MonthExtremes totalsExt = computeMonthExtremes(longInsights);
        String seasonalityLine = totalsExt == null
            ? "Foco: estacionalidad y concentración."
            : "Pico (total partidas): <b>%s</b> (%s) · Valle: <b>%s</b> (%s).".formatted(
                escape(totalsExt.bestMonthLabel),
                escape(fmtMoney(totalsExt.bestTotal)),
                escape(totalsExt.worstMonthLabel),
                escape(fmtMoney(totalsExt.worstTotal))
            );

        String c1 = """
          <div class='small'>
            <span class='badge impact-high'>Impacto alto</span>
            <b style='margin-left:8px;'>Plan de acción sobre el peor mes</b>
            <div class='muted' style='margin-top:6px;'>
              %s
              Identifica las 3 partidas con más peso y revisa si su distribución mensual es realista. Peor mes (margen): <b>%s</b>.
            </div>
          </div>
        """.formatted(seasonalityLine, escape(worstText));

        BigDecimal conc = longInsights == null ? null : longInsights.concentrationTop3AbsPct();
        boolean highConc = conc != null && conc.compareTo(new BigDecimal("55")) >= 0;
        String top3Inline = buildTop3DriversInline(longInsights);
        String c2 = """
          <div class='small' style='margin-top:12px;'>
            <span class='badge %s'>Impacto medio</span>
            <b style='margin-left:8px;'>Reducir dependencia de pocas partidas</b>
            <div class='muted' style='margin-top:6px;'>
              La concentración del top 3 está en <b>%s</b>. %s %s
            </div>
          </div>
        """.formatted(
            highConc ? "impact-med" : "impact-low",
            escape(conc == null ? "—" : conc.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%"),
            highConc ? "Revisa supuestos (precio/volumen) y prepara escenario alternativo." : "Mantén trazabilidad de supuestos y revisa trimestralmente.",
            top3Inline
        );

        String zeroHint = buildZeroHeavyInline(longInsights);
        String c3 = """
          <div class='small' style='margin-top:12px;'>
            <span class='badge impact-low'>Impacto bajo</span>
            <b style='margin-left:8px;'>Higiene y completitud</b>
            <div class='muted' style='margin-top:6px;'>
              %s
              Asegura que el XLSX tiene una sola tabla, cabeceras correctas y meses ENERO..DICIEMBRE. Esto evita lecturas parciales y errores de interpretación.
            </div>
          </div>
        """.formatted(zeroHint);

        return c1 + c2 + c3;
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
                <th class='right'>Margen</th>
              </tr>
            </thead>
            <tbody>%s</tbody>
          </table>
        """.formatted(rows);
    }

    private String buildTopDriversTable(BudgetLongInsightsDto longInsights) {
        if (longInsights == null || longInsights.topDrivers() == null || longInsights.topDrivers().isEmpty()) {
            return "<div class='muted small'>Sin drivers detectables (partidas).</div>";
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
                <th>Código</th>
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
                No se detectan partidas con muchos meses a cero (bien: el presupuesto parece completo).
              </div>
            """;
        }
        String items = longInsights.zeroHeavyItems().stream()
            .limit(8)
            .map(i -> "<li><b>%s</b> — %s · %s meses a 0</li>".formatted(
                escape(safe(i.code())),
                escape(safe(i.label())),
                escape(String.valueOf(i.zeroMonths()))
            ))
            .reduce("", (a, b) -> a + b);

        return """
          <div class='small'>
            <b>Partidas con muchos meses a 0</b>
            <div class='muted' style='margin-top:6px;'>
              Suele indicar estacionalidad fuerte o celdas sin rellenar. Revisa estas líneas:
            </div>
            <ul class='small' style='margin:8px 0 0 16px;'>%s</ul>
          </div>
        """.formatted(items);
    }

    private String buildMiniMonthTotals(BudgetLongInsightsDto longInsights) {
        if (longInsights == null || longInsights.monthTotals() == null || longInsights.monthTotals().isEmpty()) return "";

        // Compact mini-table: first 6 months present in the dataset (columns).
        var months = longInsights.monthTotals();
        var take = months.size() <= 6 ? months : months.subList(0, 6);

        String rows = take.stream()
            .map(m -> """
              <tr>
                <td>%s</td>
                <td class='right'>%s</td>
              </tr>
            """.formatted(escape(safe(m.monthLabel())), escape(fmtMoney(m.total()))))
            .reduce("", (a, b) -> a + b);

        return """
          <div style='margin-top:10px;'>
            <div class='small muted'>Mini tabla (total agregado por mes, primeras columnas)</div>
            <table class='table' style='margin-top:6px;'>
              <thead>
                <tr><th>Mes</th><th class='right'>Total</th></tr>
              </thead>
              <tbody>%s</tbody>
            </table>
          </div>
        """.formatted(rows);
    }

    private String buildTop3DriversInline(BudgetLongInsightsDto longInsights) {
        if (longInsights == null || longInsights.topDrivers() == null || longInsights.topDrivers().isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (var d : longInsights.topDrivers().stream().limit(3).toList()) {
            String code = safe(d.code());
            String label = safe(d.label());
            String share = d.shareAbsPct() == null ? "" : d.shareAbsPct().setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
            String name = (code.isBlank() ? "" : code + " · ") + (label.isBlank() ? "Partida" : label);
            parts.add(name + (share.isBlank() ? "" : " (" + share + ")"));
        }
        if (parts.isEmpty()) return "";
        return "<div style='margin-top:6px;'>Top 3: <b>%s</b></div>".formatted(escape(String.join(" · ", parts)));
    }

    private String buildZeroHeavyInline(BudgetLongInsightsDto longInsights) {
        if (longInsights == null || longInsights.zeroHeavyItems() == null || longInsights.zeroHeavyItems().isEmpty()) return "";
        var first = longInsights.zeroHeavyItems().get(0);
        String code = safe(first.code());
        String label = safe(first.label());
        String name = (code.isBlank() ? "" : code + " · ") + (label.isBlank() ? "Partida" : label);
        return "Partidas con muchos meses a 0 detectadas (ej.: <b>%s</b>).".formatted(escape(name));
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

    private MonthExtremes computeMonthExtremes(BudgetLongInsightsDto longInsights) {
        if (longInsights == null || longInsights.monthTotals() == null || longInsights.monthTotals().isEmpty()) return null;
        var best = longInsights.monthTotals().stream()
            .max(Comparator.comparing(m -> m.total() == null ? BigDecimal.ZERO : m.total()))
            .orElse(null);
        var worst = longInsights.monthTotals().stream()
            .min(Comparator.comparing(m -> m.total() == null ? BigDecimal.ZERO : m.total()))
            .orElse(null);
        if (best == null || worst == null) return null;
        return new MonthExtremes(
            safe(best.monthLabel()),
            best.total() == null ? BigDecimal.ZERO : best.total(),
            safe(worst.monthLabel()),
            worst.total() == null ? BigDecimal.ZERO : worst.total()
        );
    }

    private static String fmtMoney(BigDecimal v) {
        if (v == null) return "—";
        BigDecimal x = v.setScale(2, RoundingMode.HALF_UP);
        String s = x.toPlainString();
        return s + " €";
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}

package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.WorkforceGestorDto;
import com.asecon.enterpriseiq.dto.WorkforceHistoryDto;
import com.asecon.enterpriseiq.dto.WorkforceHistoryPointDto;
import com.asecon.enterpriseiq.dto.WorkforceImportDto;
import com.asecon.enterpriseiq.dto.WorkforceInsightsDto;
import com.asecon.enterpriseiq.dto.WorkforceKpiDto;
import com.asecon.enterpriseiq.dto.WorkforceLaborCostsDto;
import com.asecon.enterpriseiq.dto.WorkforceMonthlyCostDto;
import com.asecon.enterpriseiq.dto.WorkforceSummaryDto;
import com.asecon.enterpriseiq.model.Company;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import org.springframework.stereotype.Service;

@Service
public class WorkforceReportService {
    private static final String WORKFORCE_REPORT_TEMPLATE = loadClasspathResource("reports/workforce-report-template.html");
    private static final String WORKFORCE_REPORT_CSS = loadClasspathResource("reports/workforce-report.css");
    private static final String BRAND_IMAGE_DATA_URI =
        "data:image/png;base64," + loadClasspathResource("reports/enterpriseiq-image-base64.txt").replaceAll("\\s+", "");
    private static final Locale REPORT_LOCALE = Locale.forLanguageTag("es-ES");
    private static final DateTimeFormatter REPORT_DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter SHORT_DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String EMPTY = "—";

    private final ReportService reportService;

    public WorkforceReportService(ReportService reportService) {
        this.reportService = reportService;
    }

    public byte[] renderWorkforcePdf(Company company,
                                     WorkforceSummaryDto summary,
                                     WorkforceImportDto workforceImport,
                                     WorkforceImportDto laborCostsImport) {
        return reportService.renderPdfFromHtml(buildWorkforceReportHtml(company, summary, workforceImport, laborCostsImport));
    }

    public String buildWorkforceReportHtml(Company company,
                                           WorkforceSummaryDto summary,
                                           WorkforceImportDto workforceImport,
                                           WorkforceImportDto laborCostsImport) {
        WorkforceSummaryDto safeSummary = ensureInsights(summary == null ? emptySummary() : summary);
        WorkforceKpiDto kpis = safeSummary.kpis();
        WorkforceLaborCostsDto laborCosts = safeSummary.laborCosts();
        String companyName = company == null || company.getName() == null ? "Empresa" : company.getName();

        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("REPORT_CSS", WORKFORCE_REPORT_CSS);
        placeholders.put("BRAND_IMAGE_SRC", BRAND_IMAGE_DATA_URI);
        placeholders.put("COMPANY_NAME", escape(companyName));
        placeholders.put("GENERATED_AT", escape(ZonedDateTime.now(ZoneId.systemDefault()).format(REPORT_DATE_FMT)));
        placeholders.put("WORKFORCE_SOURCE", escape(workforceImport == null || workforceImport.filename() == null ? EMPTY : workforceImport.filename()));
        placeholders.put("LABOR_SOURCE", escape(laborCostsImport == null || laborCostsImport.filename() == null ? EMPTY : laborCostsImport.filename()));
        placeholders.put("WORKFORCE_STATUS", workforceImport == null ? "Pendiente" : "Importado");
        placeholders.put("LABOR_STATUS", laborCostsImport == null ? "No disponible" : "Activo");
        placeholders.put("COVER_KPI_HTML", buildCoverKpis(kpis, laborCosts));
        placeholders.put("EXECUTIVE_HTML", buildExecutivePage(safeSummary));
        placeholders.put("OPERATIONS_HTML", buildOperationsSection(safeSummary));
        placeholders.put("COSTS_HTML", buildCostsSection(safeSummary));
        placeholders.put("COST_ACTIVITY_HTML", buildCostActivitySection(safeSummary));
        placeholders.put("RISKS_HTML", buildRisksSection(safeSummary));
        placeholders.put("RECOMMENDATIONS_HTML", buildRecommendationsSection(safeSummary));
        return applyTemplate(WORKFORCE_REPORT_TEMPLATE, placeholders);
    }

    private String buildCoverKpis(WorkforceKpiDto kpis, WorkforceLaborCostsDto laborCosts) {
        return gridTable(
            2,
            List.of(
                coverKpiCard("Clientes activos", fmtInteger(kpis == null ? 0 : kpis.activeClients())),
                coverKpiCard("Gestores", fmtInteger(kpis == null ? 0 : kpis.totalGestores())),
                coverKpiCard("Minutos", fmtNumber(kpis == null ? null : kpis.totalMinutas())),
                coverKpiCard("Coste laboral total", laborCosts == null ? EMPTY : fmtCurrency(laborCosts.totalCosteLaboralAnual()))
            ),
            "cover-kpi-table"
        );
    }

    private String buildExecutivePage(WorkforceSummaryDto summary) {
        WorkforceKpiDto kpis = summary.kpis();
        WorkforceInsightsDto insights = insights(summary);
        String overview = gridTable(
            4,
            List.of(
                statCard("Clientes totales", fmtInteger(kpis == null ? 0 : kpis.totalClients()), "Base actual"),
                statCard("Clientes activos", fmtInteger(kpis == null ? 0 : kpis.activeClients()), "Excluye bajas"),
                statCard("Clientes baja", fmtInteger(kpis == null ? 0 : kpis.inactiveClients()), "Seguimiento"),
                statCard("Volumen de asientos", fmtNumber(kpis == null ? null : kpis.totalVolumenAsientos()), "Acumulado")
            ),
            "stat-grid stat-grid-4"
        );

        String findings = listCard("Hallazgos clave", insights.executiveReadings(), "Sin hallazgos comparables en esta carga.");
        String priorities = toneCard("Que revisar primero", insights.priorityReview(),
            "No se detectan prioridades urgentes aparte del seguimiento operativo habitual.");
        String snapshot = buildSnapshotEvolutionBlock(summary.history());

        return overview
            + twoColumnRow(findings, priorities, "row-table")
            + snapshot;
    }

    private String buildOperationsSection(WorkforceSummaryDto summary) {
        List<WorkforceGestorDto> gestores = safeGestores(summary);
        if (gestores.isEmpty()) {
            return mutedCard("Sin gestores cargados.");
        }

        String charts = twoColumnGrid(List.of(
            chartCard("Clientes activos", "Reparto operativo activo", svgBarChart(namedValues(gestores, row -> (double) row.activeClients()), ValueKind.INTEGER, "#22d3ee", 258, 188)),
            chartCard("Minutos por gestor", "Intensidad temporal", svgBarChart(namedValues(gestores, WorkforceGestorDto::totalMinutas), ValueKind.NUMBER, "#60a5fa", 258, 188)),
            chartCard("Carga total por gestor", "Carga acumulada", svgBarChart(namedValues(gestores, WorkforceGestorDto::totalCarga), ValueKind.NUMBER, "#38bdf8", 258, 188)),
            chartCard("Volumen de asientos", "N AS / promedio agregado", svgBarChart(namedValues(gestores, WorkforceGestorDto::totalVolumenAsientos), ValueKind.NUMBER, "#818cf8", 258, 188))
        ));

        String annualHistory = wideChartCard(
            "Histórico N AS YYYY",
            "Serie propia del fichero actual, distinta de la evolución entre importaciones.",
            svgBarChart(namedYearValues(aggregateAnnualSeats(gestores)), ValueKind.INTEGER, "#22d3ee", 540, 160)
        );

        return charts + annualHistory + buildOperationsTable(gestores);
    }

    private String buildCostsSection(WorkforceSummaryDto summary) {
        WorkforceLaborCostsDto laborCosts = summary.laborCosts();
        if (laborCosts == null) {
            return mutedCard("Costes laborales no disponibles. Carga un fichero para activar este análisis.");
        }

        String cards = gridTable(
            4,
            List.of(
                statCard("Coste personal", fmtCurrency(laborCosts.totalCostePersonalAnual()), "Anual"),
                statCard("SS empresa", fmtCurrency(laborCosts.totalSsEmpresaAnual()), "Anual"),
                statCard("Coste laboral total", fmtCurrency(laborCosts.totalCosteLaboralAnual()), "Anual"),
                statCard("Coste medio por gestor", fmtCurrency(laborCosts.costeMedioPorGestor()), "Media anual")
            ),
            "stat-grid stat-grid-4"
        );

        String charts = twoColumnRow(
            wideChartCard(
                "Coste personal vs SS empresa",
                "Comparativa mensual agregada.",
                svgMonthlyCosts(laborCosts.monthlyTotals(), 400, 220)
            ),
            chartCard(
                "Coste anual por gestor",
                "Distribución del coste laboral anual.",
                svgHorizontalBarChart(namedValues(safeGestores(summary), WorkforceGestorDto::costeLaboralAnual), ValueKind.CURRENCY, "#22d3ee", 220, 220)
            ),
            "row-table row-table-costs"
        );

        String review = laborCosts.reviewCount() > 0
            ? "<div class='callout warn'>Hay <b>" + escape(fmtInteger(laborCosts.reviewCount()))
                + "</b> fila(s) de costes en REVIEW. Conviene resolverlas antes de interpretar ratios economicos.</div>"
            : "";

        return cards + review + charts + buildCostsTable(safeGestores(summary));
    }

    private String buildCostActivitySection(WorkforceSummaryDto summary) {
        WorkforceLaborCostsDto pairedLaborCosts = summary.pairedLaborCosts();
        if (pairedLaborCosts == null) {
            return mutedCard("Sin lectura coste/actividad para este snapshot. El cruce solo se activa con un periodo compatible.");
        }

        List<WorkforceGestorDto> gestores = safeGestores(summary);
        String scatterMinutes = chartCard(
            "Coste laboral vs minutos",
            "Cada punto representa un gestor.",
            svgScatterChart(
                namedPairs(gestores, WorkforceGestorDto::totalMinutas, WorkforceGestorDto::costeLaboralAnual),
                "Minutos",
                "Coste laboral",
                ValueKind.NUMBER,
                ValueKind.CURRENCY,
                258,
                220
            )
        );
        String scatterSeats = chartCard(
            "Coste laboral vs asientos",
            "Lectura por cuadrantes, no ranking.",
            svgScatterChart(
                namedPairs(gestores, WorkforceGestorDto::totalVolumenAsientos, WorkforceGestorDto::costeLaboralAnual),
                "Asientos",
                "Coste laboral",
                ValueKind.NUMBER,
                ValueKind.CURRENCY,
                258,
                220
            )
        );
        String notes = listCard(
            "Lectura por cuadrantes",
            insights(summary).costActivityReadings(),
            "No se observan diferencias materiales entre coste y actividad en esta carga."
        );

        return twoColumnGrid(List.of(scatterMinutes, scatterSeats))
            + notes
            + buildCostActivityTable(gestores);
    }

    private String buildRisksSection(WorkforceSummaryDto summary) {
        WorkforceInsightsDto insights = insights(summary);
        return gridTable(
            3,
            List.of(
                sectionCard("Hallazgos", bulletList(insights.findings(), "Sin hallazgos diferenciales relevantes.")),
                sectionCard("Puntos a revisar", bulletList(insights.reviewPoints(), "Sin puntos de revisión adicionales.")),
                sectionCard("Limitaciones", bulletList(insights.limitations(), "No se detectan limitaciones adicionales."))
            ),
            "section-grid section-grid-3"
        );
    }

    private String buildRecommendationsSection(WorkforceSummaryDto summary) {
        WorkforceInsightsDto insights = insights(summary);
        String priorities = toneCard("Que revisar primero", insights.priorityReview(),
            "Mantener seguimiento operativo y economico con la misma metodologia.");
        String actions = listCard("Acciones recomendadas", insights.recommendedActions(),
            "Sin acciones adicionales aparte del seguimiento mensual habitual.");
        return twoColumnRow(priorities, actions, "row-table");
    }

    private String buildSnapshotEvolutionBlock(WorkforceHistoryDto history) {
        if (history == null || history.imports() == null || history.imports().size() < 2) {
            return toneCard(
                "Evolución entre cargas",
                List.of("No existe todavía histórico entre importaciones. La serie N AS YYYY del propio fichero sigue disponible en la página operativa."),
                "Aún no hay suficientes snapshots."
            );
        }
        List<WorkforceHistoryPointDto> imports = history.imports();
        WorkforceHistoryPointDto current = imports.get(imports.size() - 1);
        WorkforceHistoryPointDto previous = imports.get(imports.size() - 2);
        String cards = gridTable(
            4,
            List.of(
                deltaCard("Clientes", delta(current.totalClients(), previous.totalClients()), ValueKind.INTEGER),
                deltaCard("Minutos", delta(current.totalMinutas(), previous.totalMinutas()), ValueKind.NUMBER),
                deltaCard("Carga total", delta(current.totalCarga(), previous.totalCarga()), ValueKind.NUMBER),
                deltaCard("Asientos", delta(current.totalVolumenAsientos(), previous.totalVolumenAsientos()), ValueKind.NUMBER)
            ),
            "stat-grid stat-grid-4"
        );
        String table = snapshotTable(imports.stream().skip(Math.max(0, imports.size() - 3)).toList());
        return sectionCard(
            "Evolución entre importaciones",
            "<p class='section-copy'>Comparativa entre " + escape(shortDate(previous.createdAt()))
                + " y " + escape(shortDate(current.createdAt()))
                + ". Este bloque usa snapshots Workforce, no el histórico N AS interno del fichero.</p>"
                + cards + table
        );
    }

    private String buildOperationsTable(List<WorkforceGestorDto> gestores) {
        StringBuilder rows = new StringBuilder();
        for (WorkforceGestorDto row : gestores) {
            rows.append("""
              <tr>
                <td>%s</td>
                <td class='num'>%s / %s</td>
                <td class='num'>%s</td>
                <td class='num'>%s</td>
                <td class='num'>%s</td>
                <td class='num'>%s</td>
                <td class='num'>%s</td>
                <td>%s</td>
              </tr>
            """.formatted(
                escape(row.gestor()),
                escape(fmtInteger(row.activeClients())),
                escape(fmtInteger(row.totalClients())),
                escape(fmtInteger(row.inactiveClients())),
                escape(fmtNumber(row.totalMinutas())),
                escape(fmtNumber(row.totalCarga())),
                escape(fmtNumber(row.cargaMedia())),
                escape(fmtPercent(row.pctContabilidadMedio())),
                escape(servicesLabel(row))
            ));
        }
        return """
          <table class='data-table compact-table'>
            <thead>
              <tr>
                <th>Gestor</th>
                <th>Clientes A/T</th>
                <th>Bajas</th>
                <th>Minutos</th>
                <th>Carga total</th>
                <th>Carga media</th>
                <th>%% contabilidad</th>
                <th>Servicios</th>
              </tr>
            </thead>
            <tbody>%s</tbody>
          </table>
        """.formatted(rows);
    }

    private String buildCostsTable(List<WorkforceGestorDto> gestores) {
        StringBuilder rows = new StringBuilder();
        for (WorkforceGestorDto row : gestores) {
            rows.append("""
              <tr>
                <td>%s</td>
                <td class='num'>%s</td>
                <td class='num'>%s</td>
                <td class='num strong'>%s</td>
                <td class='num'>%s</td>
                <td class='num accent'>%s</td>
              </tr>
            """.formatted(
                escape(row.gestor()),
                escape(fmtCurrency(row.costePersonalAnual())),
                escape(fmtCurrency(row.ssEmpresaAnual())),
                escape(fmtCurrency(row.costeLaboralAnual())),
                escape(fmtCurrency(row.costePorCliente())),
                escape(fmtCurrency(row.costePor1000Asientos()))
            ));
        }
        return """
          <div class='table-note'>Los ratios por cliente usan clientes totales como denominador.</div>
          <table class='data-table compact-table'>
            <thead>
              <tr>
                <th>Gestor</th>
                <th>Coste personal</th>
                <th>SS empresa</th>
                <th>Coste total</th>
                <th>Coste/cliente total</th>
                <th>Coste/1.000 asientos</th>
              </tr>
            </thead>
            <tbody>%s</tbody>
          </table>
        """.formatted(rows);
    }

    private String buildCostActivityTable(List<WorkforceGestorDto> gestores) {
        StringBuilder rows = new StringBuilder();
        for (WorkforceGestorDto row : gestores) {
            rows.append("""
              <tr>
                <td>%s</td>
                <td class='num'>%s</td>
                <td class='num'>%s</td>
                <td class='num'>%s</td>
                <td class='num'>%s</td>
              </tr>
            """.formatted(
                escape(row.gestor()),
                escape(fmtCurrency(row.costePorCliente())),
                escape(fmtCurrency(row.costePor1000Minutas())),
                escape(fmtNumber(row.minutasPor1000Coste())),
                escape(fmtNumber(row.asientosPor1000Coste()))
            ));
        }
        return """
          <div class='table-note'>Los indicadores comparan coste con intensidad operativa; no valoran desempeno personal.</div>
          <table class='data-table compact-table'>
            <thead>
              <tr>
                <th>Gestor</th>
                <th>Coste/cliente total</th>
                <th>Coste/1.000 min</th>
                <th>Min/1.000 EUR</th>
                <th>Asientos/1.000 EUR</th>
              </tr>
            </thead>
            <tbody>%s</tbody>
          </table>
        """.formatted(rows);
    }

    private String snapshotTable(List<WorkforceHistoryPointDto> points) {
        if (points == null || points.isEmpty()) {
            return "";
        }
        StringBuilder rows = new StringBuilder();
        for (WorkforceHistoryPointDto point : points) {
            rows.append("""
              <tr>
                <td>%s</td>
                <td class='num'>%s</td>
                <td class='num'>%s</td>
                <td class='num'>%s</td>
                <td class='num'>%s</td>
              </tr>
            """.formatted(
                escape(shortDate(point.createdAt())),
                escape(fmtInteger(point.totalClients())),
                escape(fmtNumber(point.totalMinutas())),
                escape(fmtNumber(point.totalCarga())),
                escape(fmtNumber(point.totalVolumenAsientos()))
            ));
        }
        return """
          <table class='data-table compact-table snapshot-table'>
            <thead>
              <tr>
                <th>Fecha</th>
                <th>Clientes</th>
                <th>Minutos</th>
                <th>Carga total</th>
                <th>Asientos</th>
              </tr>
            </thead>
            <tbody>%s</tbody>
          </table>
        """.formatted(rows);
    }

    private String sectionCard(String title, String bodyHtml) {
        return """
          <div class='card section-card'>
            <div class='block-kicker'>%s</div>
            %s
          </div>
        """.formatted(escape(title), bodyHtml == null ? "" : bodyHtml);
    }

    private String listCard(String title, List<String> items, String emptyMessage) {
        return sectionCard(title, bulletList(items, emptyMessage));
    }

    private String toneCard(String title, List<String> items, String emptyMessage) {
        return """
          <div class='card tone-card'>
            <div class='block-kicker'>%s</div>
            %s
          </div>
        """.formatted(escape(title), bulletList(items, emptyMessage));
    }

    private String statCard(String label, String value, String detail) {
        return """
          <div class='stat-card'>
            <div class='stat-label'>%s</div>
            <div class='stat-value'>%s</div>
            <div class='stat-detail'>%s</div>
          </div>
        """.formatted(escape(label), escape(value), escape(detail));
    }

    private String deltaCard(String label, Double delta, ValueKind kind) {
        String tone = "flat";
        String value = EMPTY;
        if (delta != null) {
            tone = delta > 0d ? "up" : delta < 0d ? "down" : "flat";
            value = signedValue(delta, kind);
        }
        return """
          <div class='stat-card'>
            <div class='stat-label'>%s</div>
            <div class='stat-value'>%s</div>
            <div class='delta-pill delta-%s'>vs carga anterior</div>
          </div>
        """.formatted(escape(label), escape(value), escape(tone));
    }

    private String coverKpiCard(String label, String value) {
        return """
          <div class='cover-kpi-card'>
            <div class='cover-kpi-label'>%s</div>
            <div class='cover-kpi-value'>%s</div>
          </div>
        """.formatted(escape(label), escape(value));
    }

    private String chartCard(String title, String subtitle, String svg) {
        return """
          <div class='chart-box'>
            <div class='chart-title'>%s</div>
            <div class='chart-subtitle'>%s</div>
            <div class='chart-svg'>%s</div>
          </div>
        """.formatted(escape(title), escape(subtitle), svg == null ? "" : svg);
    }

    private String wideChartCard(String title, String subtitle, String svg) {
        return """
          <div class='chart-box chart-box-wide'>
            <div class='chart-title'>%s</div>
            <div class='chart-subtitle'>%s</div>
            <div class='chart-svg'>%s</div>
          </div>
        """.formatted(escape(title), escape(subtitle), svg == null ? "" : svg);
    }

    private String mutedCard(String text) {
        return "<div class='card muted-card'>" + escape(text) + "</div>";
    }

    private String twoColumnGrid(List<String> items) {
        return gridTable(2, items, "row-table");
    }

    private String twoColumnRow(String leftHtml, String rightHtml, String className) {
        return """
          <table class='%s'>
            <tr>
              <td>%s</td>
              <td>%s</td>
            </tr>
          </table>
        """.formatted(className, leftHtml == null ? "" : leftHtml, rightHtml == null ? "" : rightHtml);
    }

    private String gridTable(int columns, List<String> cells, String className) {
        StringBuilder html = new StringBuilder("<table class='" + escape(className) + "'>");
        for (int i = 0; i < cells.size(); i += columns) {
            html.append("<tr>");
            for (int j = 0; j < columns; j++) {
                int index = i + j;
                html.append("<td>");
                html.append(index < cells.size() ? cells.get(index) : "");
                html.append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</table>");
        return html.toString();
    }

    private String bulletList(List<String> items, String emptyMessage) {
        List<String> safeItems = items == null ? List.of() : items.stream()
            .filter(item -> item != null && !item.isBlank())
            .toList();
        if (safeItems.isEmpty()) {
            return "<div class='muted-text'>" + escape(emptyMessage) + "</div>";
        }
        StringBuilder html = new StringBuilder("<ul class='bullet-list'>");
        for (String item : safeItems) {
            html.append("<li>").append(escape(item)).append("</li>");
        }
        html.append("</ul>");
        return html.toString();
    }

    private String svgBarChart(List<NamedValue> values, ValueKind kind, String accent, int width, int height) {
        if (values == null || values.isEmpty()) {
            return emptySvg(width, height, "Sin datos disponibles.");
        }
        double max = values.stream().mapToDouble(NamedValue::value).max().orElse(0d);
        if (max <= 0d) {
            return emptySvg(width, height, "Sin valores positivos.");
        }
        int left = 26;
        int right = 16;
        int top = 22;
        int bottom = 48;
        double plotW = width - left - right;
        double plotH = height - top - bottom;
        double barGap = 8d;
        double barW = Math.max(18d, (plotW - ((values.size() - 1) * barGap)) / values.size());

        StringBuilder grid = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            double y = top + (plotH * i / 3d);
            grid.append(String.format(Locale.ROOT,
                "<line x1='%d' y1='%.1f' x2='%d' y2='%.1f' stroke='rgba(148,163,184,0.16)' stroke-width='1'/>",
                left, y, width - right, y));
        }

        StringBuilder bars = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            NamedValue row = values.get(i);
            double x = left + i * (barW + barGap);
            double barH = (row.value() / max) * plotH;
            double y = top + plotH - barH;
            bars.append(String.format(Locale.ROOT,
                "<rect x='%.1f' y='%.1f' width='%.1f' height='%.1f' rx='8' fill='%s' fill-opacity='0.92'/>",
                x, y, barW, Math.max(2d, barH), accent));
            bars.append(String.format(Locale.ROOT,
                "<text x='%.1f' y='%.1f' font-size='10' fill='rgba(248,250,252,0.88)' text-anchor='middle'>%s</text>",
                x + barW / 2d, Math.max(14d, y - 6d), escape(shortValue(row.value(), kind))));
            bars.append(String.format(Locale.ROOT,
                "<text x='%.1f' y='%d' font-size='10' fill='rgba(226,232,240,0.74)' text-anchor='middle'>%s</text>",
                x + barW / 2d, height - 20, escape(truncate(row.label(), 10))));
        }

        return svgShell(width, height, grid + bars.toString());
    }

    private String svgHorizontalBarChart(List<NamedValue> values, ValueKind kind, String accent, int width, int height) {
        if (values == null || values.isEmpty()) {
            return emptySvg(width, height, "Sin datos disponibles.");
        }
        double max = values.stream().mapToDouble(NamedValue::value).max().orElse(0d);
        if (max <= 0d) {
            return emptySvg(width, height, "Sin valores positivos.");
        }
        int left = 86;
        int right = 18;
        int top = 20;
        int bottom = 18;
        double plotW = width - left - right;
        double rowGap = 10d;
        double rowH = Math.max(14d, (height - top - bottom - ((values.size() - 1) * rowGap)) / values.size());

        StringBuilder bars = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            NamedValue row = values.get(i);
            double y = top + i * (rowH + rowGap);
            double barW = (row.value() / max) * plotW;
            bars.append(String.format(Locale.ROOT,
                "<text x='12' y='%.1f' font-size='10' fill='rgba(226,232,240,0.74)'>%s</text>",
                y + rowH * 0.72d, escape(truncate(row.label(), 12))));
            bars.append(String.format(Locale.ROOT,
                "<rect x='%d' y='%.1f' width='%.1f' height='%.1f' rx='7' fill='%s' fill-opacity='0.92'/>",
                left, y, Math.max(2d, barW), rowH, accent));
            bars.append(String.format(Locale.ROOT,
                "<text x='%.1f' y='%.1f' font-size='10' fill='rgba(248,250,252,0.88)'>%s</text>",
                Math.min(width - right - 42d, left + barW + 8d), y + rowH * 0.72d, escape(shortValue(row.value(), kind))));
        }
        return svgShell(width, height, bars.toString());
    }

    private String svgMonthlyCosts(List<WorkforceMonthlyCostDto> rows, int width, int height) {
        if (rows == null || rows.isEmpty()) {
            return emptySvg(width, height, "Sin detalle mensual.");
        }
        List<MonthlyRow> data = rows.stream()
            .filter(row -> row != null && (row.costePersonal() != null || row.ssEmpresa() != null || row.costeTotal() != null))
            .map(row -> new MonthlyRow(
                row.month(),
                row.costePersonal() == null ? 0d : row.costePersonal(),
                row.ssEmpresa() == null ? 0d : row.ssEmpresa(),
                row.costeTotal() == null ? ((row.costePersonal() == null ? 0d : row.costePersonal()) + (row.ssEmpresa() == null ? 0d : row.ssEmpresa())) : row.costeTotal()
            ))
            .toList();
        if (data.isEmpty()) {
            return emptySvg(width, height, "Sin detalle mensual.");
        }
        double max = data.stream().mapToDouble(MonthlyRow::total).max().orElse(0d);
        if (max <= 0d) max = 1d;
        int left = 22;
        int right = 16;
        int top = 24;
        int bottom = 48;
        double plotW = width - left - right;
        double plotH = height - top - bottom;
        double groupW = plotW / data.size();
        double barW = Math.max(8d, groupW * 0.22d);

        StringBuilder grid = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            double y = top + (plotH * i / 3d);
            grid.append(String.format(Locale.ROOT,
                "<line x1='%d' y1='%.1f' x2='%d' y2='%.1f' stroke='rgba(148,163,184,0.16)' stroke-width='1'/>",
                left, y, width - right, y));
        }

        StringBuilder shapes = new StringBuilder();
        StringBuilder totalLine = new StringBuilder();
        for (int i = 0; i < data.size(); i++) {
            MonthlyRow row = data.get(i);
            double groupX = left + i * groupW;
            double personalH = row.personal() / max * plotH;
            double ssH = row.ss() / max * plotH;
            double personalX = groupX + groupW * 0.18d;
            double ssX = personalX + barW + 6d;
            double personalY = top + plotH - personalH;
            double ssY = top + plotH - ssH;
            shapes.append(String.format(Locale.ROOT,
                "<rect x='%.1f' y='%.1f' width='%.1f' height='%.1f' rx='6' fill='#60a5fa' fill-opacity='0.92'/>",
                personalX, personalY, barW, Math.max(2d, personalH)));
            shapes.append(String.format(Locale.ROOT,
                "<rect x='%.1f' y='%.1f' width='%.1f' height='%.1f' rx='6' fill='#2dd4bf' fill-opacity='0.92'/>",
                ssX, ssY, barW, Math.max(2d, ssH)));
            double pointX = groupX + groupW * 0.72d;
            double pointY = top + plotH - (row.total() / max * plotH);
            if (i > 0) totalLine.append(' ');
            totalLine.append(String.format(Locale.ROOT, "%.1f,%.1f", pointX, pointY));
            shapes.append(String.format(Locale.ROOT,
                "<text x='%.1f' y='%d' font-size='10' fill='rgba(226,232,240,0.74)' text-anchor='middle'>%s</text>",
                groupX + groupW * 0.48d, height - 20, escape(truncate(row.month(), 3))));
        }
        shapes.append(String.format(Locale.ROOT,
            "<polyline fill='none' stroke='#f8fafc' stroke-width='2.4' points='%s'/>",
            totalLine));

        String legend = """
          <text x='24' y='18' font-size='10' fill='rgba(226,232,240,0.74)'>■ Coste personal</text>
          <text x='150' y='18' font-size='10' fill='rgba(226,232,240,0.74)'>■ SS empresa</text>
          <text x='248' y='18' font-size='10' fill='rgba(226,232,240,0.74)'>— Coste total</text>
        """;

        return svgShell(width, height, legend + grid + shapes);
    }

    private String svgScatterChart(List<NamedPoint> points,
                                   String xLabel,
                                   String yLabel,
                                   ValueKind xKind,
                                   ValueKind yKind,
                                   int width,
                                   int height) {
        if (points == null || points.isEmpty()) {
            return emptySvg(width, height, "Sin datos suficientes para el cuadrante.");
        }
        double maxX = points.stream().mapToDouble(NamedPoint::x).max().orElse(0d);
        double maxY = points.stream().mapToDouble(NamedPoint::y).max().orElse(0d);
        if (maxX <= 0d || maxY <= 0d) {
            return emptySvg(width, height, "Sin valores positivos.");
        }
        int left = 28;
        int right = 14;
        int top = 18;
        int bottom = 34;
        double plotW = width - left - right;
        double plotH = height - top - bottom;
        double avgX = points.stream().mapToDouble(NamedPoint::x).average().orElse(0d);
        double avgY = points.stream().mapToDouble(NamedPoint::y).average().orElse(0d);
        double avgXPos = left + (avgX / maxX) * plotW;
        double avgYPos = top + plotH - (avgY / maxY) * plotH;

        StringBuilder grid = new StringBuilder();
        grid.append(String.format(Locale.ROOT,
            "<line x1='%d' y1='%.1f' x2='%d' y2='%.1f' stroke='rgba(148,163,184,0.18)' stroke-width='1' stroke-dasharray='4 4'/>",
            left, avgYPos, width - right, avgYPos));
        grid.append(String.format(Locale.ROOT,
            "<line x1='%.1f' y1='%d' x2='%.1f' y2='%d' stroke='rgba(148,163,184,0.18)' stroke-width='1' stroke-dasharray='4 4'/>",
            avgXPos, top, avgXPos, height - bottom));

        StringBuilder dots = new StringBuilder();
        for (NamedPoint point : points) {
            double cx = left + (point.x() / maxX) * plotW;
            double cy = top + plotH - (point.y() / maxY) * plotH;
            dots.append(String.format(Locale.ROOT,
                "<circle cx='%.1f' cy='%.1f' r='5.2' fill='#60a5fa' fill-opacity='0.96'/>",
                cx, cy));
            dots.append(String.format(Locale.ROOT,
                "<text x='%.1f' y='%.1f' font-size='10' fill='rgba(248,250,252,0.88)' text-anchor='middle'>%s</text>",
                cx, Math.max(12d, cy - 8d), escape(truncate(point.label(), 10))));
        }
        String axes = """
          <text x='28' y='%d' font-size='10' fill='rgba(226,232,240,0.74)'>%s</text>
          <text x='%d' y='%d' font-size='10' fill='rgba(226,232,240,0.74)' text-anchor='end'>%s</text>
        """.formatted(height - 12, escape(xLabel), width - 12, 14, escape(yLabel));

        return svgShell(width, height, grid + dots.toString() + axes);
    }

    private String svgShell(int width, int height, String inner) {
        return """
          <svg width='%d' height='%d' viewBox='0 0 %d %d' xmlns='http://www.w3.org/2000/svg'>
            <defs>
              <linearGradient id='workforce-svg-bg' x1='0' y1='0' x2='1' y2='1'>
                <stop offset='0' stop-color='#08111f'/>
                <stop offset='1' stop-color='#0f1f36'/>
              </linearGradient>
            </defs>
            <rect x='0' y='0' width='%d' height='%d' rx='16' fill='url(#workforce-svg-bg)'/>
            %s
          </svg>
        """.formatted(width, height, width, height, width, height, inner == null ? "" : inner);
    }

    private String emptySvg(int width, int height, String message) {
        return svgShell(width, height,
            "<text x='" + (width / 2) + "' y='" + (height / 2) + "' font-size='12' fill='rgba(226,232,240,0.78)' text-anchor='middle'>"
                + escape(message) + "</text>");
    }

    private List<NamedValue> namedValues(List<WorkforceGestorDto> gestores, MetricExtractor extractor) {
        return gestores.stream()
            .map(row -> {
                Double value = extractor.extract(row);
                return value == null ? null : new NamedValue(row.gestor(), value);
            })
            .filter(row -> row != null)
            .toList();
    }

    private List<NamedValue> namedYearValues(Map<Integer, Long> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.entrySet().stream()
            .map(entry -> new NamedValue(String.valueOf(entry.getKey()), entry.getValue().doubleValue()))
            .toList();
    }

    private List<NamedPoint> namedPairs(List<WorkforceGestorDto> gestores,
                                        MetricExtractor xExtractor,
                                        MetricExtractor yExtractor) {
        return gestores.stream()
            .map(row -> {
                Double x = xExtractor.extract(row);
                Double y = yExtractor.extract(row);
                return x == null || y == null ? null : new NamedPoint(row.gestor(), x, y);
            })
            .filter(row -> row != null)
            .toList();
    }

    private Map<Integer, Long> aggregateAnnualSeats(List<WorkforceGestorDto> gestores) {
        Map<Integer, Long> totals = new TreeMap<>();
        for (WorkforceGestorDto row : gestores) {
            if (row.annualSeatTotals() == null) continue;
            for (Map.Entry<Integer, Long> entry : row.annualSeatTotals().entrySet()) {
                totals.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
        }
        return totals;
    }

    private WorkforceInsightsDto insights(WorkforceSummaryDto summary) {
        return summary != null && summary.insights() != null ? summary.insights() : WorkforceInsightFactory.build(summary);
    }

    private WorkforceSummaryDto ensureInsights(WorkforceSummaryDto summary) {
        if (summary == null || summary.insights() != null) {
            return summary;
        }
        return new WorkforceSummaryDto(
            summary.kpis(),
            summary.gestores(),
            summary.detectedColumns(),
            summary.activityYears(),
            summary.laborCosts(),
            summary.pairedLaborCosts(),
            summary.history(),
            summary.laborCostsHistory(),
            WorkforceInsightFactory.build(summary),
            summary.workforceImport(),
            summary.laborCostsImport(),
            summary.pairedLaborCostsImport(),
            summary.workforceImports(),
            summary.laborCostImports()
        );
    }

    private static List<WorkforceGestorDto> safeGestores(WorkforceSummaryDto summary) {
        return summary == null || summary.gestores() == null ? List.of() : summary.gestores();
    }

    private static WorkforceSummaryDto emptySummary() {
        return new WorkforceSummaryDto(
            new WorkforceKpiDto(0, 0, 0, 0, null, null, null),
            List.of(),
            List.of(),
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            List.of()
        );
    }

    private static Double delta(long current, long previous) {
        return (double) (current - previous);
    }

    private static Double delta(Double current, Double previous) {
        if (current == null || previous == null) return null;
        return current - previous;
    }

    private static String servicesLabel(WorkforceGestorDto row) {
        return "C/M %s · IS %s · DDCC %s · Lib %s".formatted(
            fmtInteger(row.contModelosOk()),
            fmtInteger(row.isIrpfOk()),
            fmtInteger(row.ddccOk()),
            fmtInteger(row.librosOk())
        );
    }

    private static String shortValue(double value, ValueKind kind) {
        return switch (kind) {
            case INTEGER -> fmtInteger(Math.round(value));
            case CURRENCY -> fmtCurrency(value);
            case PERCENT -> fmtPercent(value);
            case NUMBER -> fmtNumber(value);
        };
    }

    private static String signedValue(double value, ValueKind kind) {
        String sign = value > 0d ? "+" : value < 0d ? "-" : "";
        double abs = Math.abs(value);
        return sign + switch (kind) {
            case INTEGER -> fmtInteger(Math.round(abs));
            case CURRENCY -> fmtCurrency(abs);
            case PERCENT -> fmtPercent(abs);
            case NUMBER -> fmtNumber(abs);
        };
    }

    private static String fmtNumber(Number value) {
        if (value == null) return EMPTY;
        NumberFormat fmt = NumberFormat.getNumberInstance(REPORT_LOCALE);
        fmt.setMaximumFractionDigits(2);
        fmt.setMinimumFractionDigits(0);
        return fmt.format(value);
    }

    private static String fmtInteger(long value) {
        NumberFormat fmt = NumberFormat.getIntegerInstance(REPORT_LOCALE);
        return fmt.format(value);
    }

    private static String fmtCurrency(Number value) {
        if (value == null) return EMPTY;
        NumberFormat fmt = NumberFormat.getCurrencyInstance(REPORT_LOCALE);
        fmt.setMaximumFractionDigits(2);
        fmt.setMinimumFractionDigits(2);
        return fmt.format(value);
    }

    private static String fmtPercent(Number value) {
        if (value == null) return EMPTY;
        NumberFormat fmt = NumberFormat.getNumberInstance(REPORT_LOCALE);
        fmt.setMaximumFractionDigits(2);
        fmt.setMinimumFractionDigits(0);
        return fmt.format(value) + " %";
    }

    private static String shortDate(Instant instant) {
        if (instant == null) return EMPTY;
        return instant.atZone(ZoneId.systemDefault()).format(SHORT_DATE_FMT);
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.length() <= max) return trimmed;
        if (max <= 3) return trimmed.substring(0, max);
        return trimmed.substring(0, max - 3) + "...";
    }

    private static String applyTemplate(String template, Map<String, String> placeholders) {
        String html = template;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            html = html.replace("{{" + entry.getKey() + "}}", entry.getValue() == null ? "" : entry.getValue());
        }
        return html;
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private static String loadClasspathResource(String path) {
        try (InputStream in = WorkforceReportService.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) return "";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo cargar recurso " + path, ex);
        }
    }

    @FunctionalInterface
    private interface MetricExtractor {
        Double extract(WorkforceGestorDto row);
    }

    private enum ValueKind {
        NUMBER,
        INTEGER,
        CURRENCY,
        PERCENT
    }

    private record NamedValue(String label, double value) {}

    private record NamedPoint(String label, double x, double y) {}

    private record MonthlyRow(String month, double personal, double ss, double total) {}
}

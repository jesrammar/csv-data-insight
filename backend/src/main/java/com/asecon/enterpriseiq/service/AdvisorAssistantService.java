package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.AdvisorActionDto;
import com.asecon.enterpriseiq.dto.AdvisorEvidenceDto;
import com.asecon.enterpriseiq.dto.AssistantChatRequestDto;
import com.asecon.enterpriseiq.dto.AssistantChatResponseDto;
import com.asecon.enterpriseiq.dto.AdvisorRecommendationsDto;
import com.asecon.enterpriseiq.dto.AssistantMessageDto;
import com.asecon.enterpriseiq.dto.DashboardInsightDto;
import com.asecon.enterpriseiq.dto.DashboardMetricDto;
import com.asecon.enterpriseiq.dto.TransactionAnalyticsDto;
import com.asecon.enterpriseiq.dto.TribunalRiskDto;
import com.asecon.enterpriseiq.dto.TribunalSummaryDto;
import com.asecon.enterpriseiq.dto.UniversalInsightDto;
import com.asecon.enterpriseiq.dto.UniversalSummaryDto;
import com.asecon.enterpriseiq.model.KpiMonthly;
import com.asecon.enterpriseiq.repo.KpiMonthlyRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;

@Service
public class AdvisorAssistantService {
    private final UniversalCsvService universalCsvService;
    private final KpiMonthlyRepository kpiMonthlyRepository;
    private final DashboardMetricsService dashboardMetricsService;
    private final TribunalImportService tribunalImportService;
    private final TransactionAnalyticsService transactionAnalyticsService;

    public AdvisorAssistantService(UniversalCsvService universalCsvService,
                                   KpiMonthlyRepository kpiMonthlyRepository,
                                   DashboardMetricsService dashboardMetricsService,
                                   TribunalImportService tribunalImportService,
                                   TransactionAnalyticsService transactionAnalyticsService) {
        this.universalCsvService = universalCsvService;
        this.kpiMonthlyRepository = kpiMonthlyRepository;
        this.dashboardMetricsService = dashboardMetricsService;
        this.tribunalImportService = tribunalImportService;
        this.transactionAnalyticsService = transactionAnalyticsService;
    }

    public AssistantChatResponseDto chat(Long companyId, AssistantChatRequestDto req) {
        Optional<UniversalSummaryDto> universal = universalCsvService.latest(companyId);
        DashboardContext dashboard = loadDashboard(companyId);
        TribunalSummaryDto tribunal = safeTribunal(companyId);
        TransactionAnalyticsDto txAnalytics = loadTxAnalytics(companyId, dashboard);

        boolean hasAnyData = universal.isPresent()
            || (dashboard != null && dashboard.kpis != null && !dashboard.kpis.isEmpty())
            || (tribunal != null && tribunal.kpis() != null && tribunal.kpis().totalClients() > 0);

        if (!hasAnyData) {
            return new AssistantChatResponseDto(
                "Aún no tengo datos suficientes para asesorarte. Sube un CSV/XLSX en “Análisis universal” o carga movimientos para el dashboard (caja) y vuelvo con un plan 30/60/90 días.",
                List.of("¿Cuál es tu objetivo principal (margen, costes, caja, crecimiento)?"),
                List.of(),
                List.of("Quiero mejorar margen", "Quiero reducir costes", "Quiero mejorar caja", "Detecta riesgos y quick wins")
            );
        }

        String userMsg = lastUserMessage(req).orElse("");
        Intent intent = detectIntent(userMsg);
        Horizon horizon = detectHorizon(userMsg);

        List<UniversalInsightDto> insights = universal.map(u -> u.insights() == null ? List.<UniversalInsightDto>of() : u.insights()).orElse(List.of());
        List<AdvisorActionDto> actions = buildActions(universal.orElse(null), insights, dashboard, tribunal, txAnalytics, intent, horizon);

        String reply = buildReply(universal.orElse(null), insights, dashboard, tribunal, intent, actions);
        List<String> questions = buildQuestions(intent);
        List<String> prompts = buildSuggestedPrompts(intent);

        return new AssistantChatResponseDto(reply, questions, actions, prompts);
    }

    public AdvisorRecommendationsDto recommendations(Long companyId, String period) {
        return recommendations(companyId, period, null);
    }

    public AdvisorRecommendationsDto recommendations(Long companyId, String period, String objective) {
        Optional<UniversalSummaryDto> universal = universalCsvService.latest(companyId);
        DashboardContext dashboard = loadDashboard(companyId);
        TribunalSummaryDto tribunal = safeTribunal(companyId);
        TransactionAnalyticsDto txAnalytics = loadTxAnalytics(companyId, dashboard);

        List<UniversalInsightDto> insights = universal.map(u -> u.insights() == null ? List.<UniversalInsightDto>of() : u.insights()).orElse(List.of());
        Intent intent = intentFromObjective(objective);
        Horizon horizon = new Horizon("30d", "60d");
        List<AdvisorActionDto> actions = buildActions(universal.orElse(null), insights, dashboard, tribunal, txAnalytics, intent, horizon);
        String reply = buildReply(universal.orElse(null), insights, dashboard, tribunal, intent, actions);

        String resolvedPeriod = (period == null || period.isBlank())
            ? (dashboard != null && dashboard.latestPeriod != null ? dashboard.latestPeriod : YearMonth.now().toString())
            : period;

        String summary = reply == null ? "" : reply;
        return new AdvisorRecommendationsDto(resolvedPeriod, summary, actions == null ? List.of() : actions);
    }

    private static Intent intentFromObjective(String objective) {
        String obj = RecommendationObjective.normalize(objective);
        return switch (obj) {
            case RecommendationObjective.CASH -> Intent.CASH;
            case RecommendationObjective.COST -> Intent.COST;
            case RecommendationObjective.MARGIN -> Intent.MARGIN;
            case RecommendationObjective.GROWTH -> Intent.GROWTH;
            case RecommendationObjective.RISK -> Intent.RISK;
            default -> Intent.GENERAL;
        };
    }

    public String buildConsultingReportHtml(Long companyId, String companyName) {
        Optional<UniversalSummaryDto> universal = universalCsvService.latest(companyId);
        DashboardContext dashboard = loadDashboard(companyId);
        TribunalSummaryDto tribunal = safeTribunal(companyId);
        TransactionAnalyticsDto txAnalytics = loadTxAnalytics(companyId, dashboard);

        Intent intent = Intent.GENERAL;
        Horizon horizon = new Horizon("30d", "60d");

        List<UniversalInsightDto> insights = universal.map(u -> u.insights() == null ? List.<UniversalInsightDto>of() : u.insights()).orElse(List.of());
        List<AdvisorActionDto> actions = buildActions(universal.orElse(null), insights, dashboard, tribunal, txAnalytics, intent, horizon);

        String summaryBlock = buildReply(universal.orElse(null), insights, dashboard, tribunal, intent, actions);

        String actionsHtml = actions.stream().limit(10).map(a -> {
            String evidenceHtml = "";
            if (a.evidence() != null && !a.evidence().isEmpty()) {
                String items = a.evidence().stream().limit(8).map(e -> {
                    String meta = joinNonBlank(" · ", e.subtitle(), e.metric());
                    String metaHtml = meta.isBlank() ? "" : "<span class='muted'>(" + esc(meta) + ")</span>";
                    String detail = e.detail() == null || e.detail().isBlank() ? "" : "<br/><span class='muted'>" + esc(e.detail()) + "</span>";
                    return "<li><strong>" + esc(e.title()) + "</strong> " + metaHtml + detail + "</li>";
                }).collect(Collectors.joining("\n"));
                evidenceHtml = "<ul class='evidence'>" + items + "</ul>";
            }

            return """
            <tr>
              <td>%s</td>
              <td>%s</td>
              <td><strong>%s</strong><br/><span class="muted">%s</span>%s</td>
              <td class="muted">%s</td>
            </tr>
            """.formatted(esc(a.horizon()), esc(a.priority()), esc(a.title()), esc(a.detail()), evidenceHtml, esc(a.kpi()));
        }).collect(Collectors.joining("\n"));

        String insightsHtml = insights.stream().limit(10).map(i -> """
            <li><strong>%s</strong>: %s</li>
            """.formatted(esc(i.title()), esc(i.message())))
            .collect(Collectors.joining("\n"));

        String tribunalHtml = "";
        if (tribunal != null && tribunal.risk() != null && !tribunal.risk().isEmpty()) {
            tribunalHtml = tribunal.risk().stream().limit(8).map(r -> """
                <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>
                """.formatted(esc(r.cliente()), esc(r.cif()), esc(r.gestor()), esc(r.issues())))
                .collect(Collectors.joining("\n"));
            tribunalHtml = """
              <div class="card">
                <h2>Riesgos (Tribunal)</h2>
                <table>
                  <thead><tr><th>Cliente</th><th>CIF</th><th>Gestor</th><th>Motivo</th></tr></thead>
                  <tbody>%s</tbody>
                </table>
              </div>
            """.formatted(tribunalHtml);
        }

        String dashboardHtml = "";
        if (dashboard != null && dashboard.metrics != null && !dashboard.metrics.isEmpty()) {
            String rows = dashboard.metrics.stream().limit(10).map(m -> """
                <div class="kpi"><div class="kpi-title">%s</div><div class="kpi-value">%s</div></div>
                """.formatted(esc(m.getLabel()), esc(m.getValue())))
                .collect(Collectors.joining("\n"));
            dashboardHtml = """
              <div class="card">
                <h2>Caja (KPIs)</h2>
                <div class="kpi-grid">%s</div>
              </div>
            """.formatted(rows);
        }

        return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset='utf-8'/>
          <title>Informe consultivo - EnterpriseIQ</title>
          <style>
            body { font-family: Arial, sans-serif; margin: 28px; color: #0f172a; background: #ffffff; }
            h1 { margin: 0 0 6px; }
            h2 { margin: 0 0 10px; font-size: 18px; }
            .muted { color: #475569; font-size: 12px; }
            .evidence { margin: 8px 0 0 18px; padding-left: 12px; }
            .evidence li { margin: 4px 0; }
            .grid { display: grid; grid-template-columns: 1fr; gap: 12px; margin-top: 14px; }
            .card { border: 1px solid #e2e8f0; border-radius: 12px; padding: 14px; }
            table { width: 100%%; border-collapse: collapse; }
            th, td { border-bottom: 1px solid #e2e8f0; padding: 8px; vertical-align: top; text-align: left; }
            th { background: #f8fafc; font-size: 12px; text-transform: uppercase; letter-spacing: .04em; }
            .kpi-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
            .kpi { border: 1px solid #e2e8f0; border-radius: 10px; padding: 10px; }
            .kpi-title { font-size: 12px; color: #475569; }
            .kpi-value { font-size: 18px; font-weight: 700; margin-top: 6px; }
            pre { white-space: pre-wrap; font-family: inherit; }
          </style>
        </head>
        <body>
          <div>
            <h1>Informe consultivo</h1>
            <div class="muted">Empresa: %s · Generado: %s</div>
          </div>

          <div class="grid">
            <div class="card">
              <h2>Resumen ejecutivo</h2>
              <pre>%s</pre>
            </div>

            %s

            <div class="card">
              <h2>Insights (dato y negocio)</h2>
              <ul>%s</ul>
            </div>

            <div class="card">
              <h2>Plan 30/60/90</h2>
              <table>
                <thead><tr><th>Horizonte</th><th>Prioridad</th><th>Accion</th><th>KPI</th></tr></thead>
                <tbody>%s</tbody>
              </table>
            </div>

            %s
          </div>
        </body>
        </html>
        """.formatted(
            esc(companyName == null ? "Empresa" : companyName),
            java.time.ZonedDateTime.now().toString(),
            esc(summaryBlock),
            dashboardHtml,
            insightsHtml,
            actionsHtml,
            tribunalHtml
        );
    }

    private static String esc(String raw) {
        if (raw == null) return "";
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static Optional<String> lastUserMessage(AssistantChatRequestDto req) {
        if (req == null || req.messages() == null || req.messages().isEmpty()) return Optional.empty();
        for (int i = req.messages().size() - 1; i >= 0; i--) {
            AssistantMessageDto m = req.messages().get(i);
            if (m == null) continue;
            if ("user".equalsIgnoreCase(m.role()) && m.content() != null && !m.content().isBlank()) {
                return Optional.of(m.content());
            }
        }
        return Optional.empty();
    }

    private static String buildReply(UniversalSummaryDto summary,
                                     List<UniversalInsightDto> insights,
                                     DashboardContext dashboard,
                                     TribunalSummaryDto tribunal,
                                     Intent intent,
                                     List<AdvisorActionDto> actions) {
        List<String> blocks = new ArrayList<>();

        if (summary != null) {
            blocks.add("Resumen (universal): '" + summary.filename() + "' (" + summary.rowCount() + " filas, " + summary.columnCount() + " columnas).");
        }
        if (dashboard != null && dashboard.latestPeriod != null) {
            blocks.add("Caja (dashboard): ultimos " + dashboard.kpis.size() + " meses hasta " + dashboard.latestPeriod + ".");
        }
        if (tribunal != null && tribunal.kpis() != null && tribunal.kpis().totalClients() > 0) {
            blocks.add("Tribunal: " + tribunal.kpis().totalClients()
                + " clientes (" + tribunal.kpis().activeClients()
                + " activos), bajas=" + tribunal.kpis().bajaPct()
                + "%, cont=" + tribunal.kpis().contabilidadPct()
                + "%, fiscal=" + tribunal.kpis().fiscalPct() + "%.");
        }
        String base = blocks.isEmpty() ? "Resumen rapido:" : String.join(" ", blocks);

        String insightLine = insights.stream()
            .limit(3)
            .map(i -> i.title() + ": " + i.message())
            .collect(Collectors.joining(" | "));

        String focus = switch (intent) {
            case CASH -> "Enfoque: caja y liquidez.";
            case COST -> "Enfoque: eficiencia y control de costes.";
            case GROWTH -> "Enfoque: crecimiento con control de margen.";
            case RISK -> "Enfoque: riesgos y quick wins.";
            case MARGIN -> "Enfoque: margen y rentabilidad.";
            default -> "Enfoque: plan de mejora general.";
        };

        String topActions = actions.stream().limit(3).map(a -> "• " + a.title()).collect(Collectors.joining("\n"));
        if (topActions.isBlank()) topActions = "• Define objetivo y sube datos para priorizar acciones.";

        return base + "\n" + focus + (insightLine.isBlank() ? "" : "\n" + insightLine) + "\n\nAcciones prioritarias:\n" + topActions;
    }

    private static List<String> buildQuestions(Intent intent) {
        List<String> qs = new ArrayList<>();
        qs.add("¿Cuál es el objetivo #1 (margen, costes, caja, crecimiento)?");
        if (intent == Intent.CASH) qs.add("¿Tienes picos de pagos (impuestos/nóminas/proveedores) por mes?");
        if (intent == Intent.COST) qs.add("¿Qué gastos son fijos vs variables?");
        if (intent == Intent.GROWTH) qs.add("¿Cuál es tu capacidad operativa (equipo/producción) este trimestre?");
        qs.add("¿Qué horizonte: 30, 60 o 90 días?");
        return qs;
    }

    private static List<String> buildSuggestedPrompts(Intent intent) {
        return switch (intent) {
            case CASH -> List.of("Plan 30/60/90 para caja", "¿Qué mes es más crítico?", "Recomendaciones de cobro/pago");
            case COST -> List.of("Plan de recorte de costes", "¿Dónde están los sobrecostes?", "Quick wins esta semana");
            case GROWTH -> List.of("Plan de crecimiento", "Subir ingresos sin perder margen", "Riesgos del plan");
            case RISK -> List.of("Lista de riesgos", "Controles recomendados", "Acciones inmediatas");
            case MARGIN -> List.of("Mejorar margen", "¿Qué partida pesa más?", "Acciones por impacto");
            default -> List.of("Plan 30/60/90", "Quick wins", "Riesgos y oportunidades", "Checklist Tribunal (cumplimiento)");
        };
    }

    private static AdvisorActionDto action(String horizon, String priority, String title, String detail, String kpi) {
        return new AdvisorActionDto(horizon, priority, title, detail, kpi, List.of());
    }

    private static AdvisorActionDto action(String horizon,
                                           String priority,
                                           String title,
                                           String detail,
                                           String kpi,
                                           List<AdvisorEvidenceDto> evidence) {
        return new AdvisorActionDto(horizon, priority, title, detail, kpi, evidence == null ? List.of() : evidence);
    }

    private static String joinNonBlank(String separator, String... parts) {
        if (parts == null || parts.length == 0) return "";
        return java.util.Arrays.stream(parts)
            .filter(p -> p != null && !p.isBlank())
            .collect(Collectors.joining(separator));
    }

    private static List<AdvisorActionDto> buildActions(UniversalSummaryDto summary,
                                                       List<UniversalInsightDto> insights,
                                                       DashboardContext dashboard,
                                                       TribunalSummaryDto tribunal,
                                                       TransactionAnalyticsDto txAnalytics,
                                                       Intent intent,
                                                       Horizon horizon) {
        List<AdvisorActionDto> actions = new ArrayList<>();

        boolean hasBudget = insights.stream().anyMatch(i -> i != null && "Lectura de presupuesto".equalsIgnoreCase(i.title()));
        UniversalInsightDto quality = insights.stream()
            .filter(i -> i != null && "Calidad de datos".equalsIgnoreCase(i.title()))
            .findFirst()
            .orElse(null);

        if (quality != null) {
            actions.add(action(
                "7d",
                "P0",
                "Mejorar calidad de datos",
                "Completa nulos y estandariza columnas clave antes de sacar conclusiones. " + quality.message(),
                "Null% por columna"
            ));
        }

        if (dashboard != null && dashboard.insights != null) {
            boolean runwayCritical = dashboard.insights.stream().anyMatch(i -> i != null && "Runway corto".equalsIgnoreCase(i.getTitle()));
            boolean netNegative = dashboard.insights.stream().anyMatch(i -> i != null && "Flujo neto negativo".equalsIgnoreCase(i.getTitle()));
            if (runwayCritical || netNegative || intent == Intent.CASH) {
                List<AdvisorEvidenceDto> cashEvidence = dashboard.metrics == null ? List.of() : dashboard.metrics.stream()
                    .limit(6)
                    .map(m -> new AdvisorEvidenceDto(
                        "dashboard_metric",
                        m.getLabel(),
                        m.getKey(),
                        null,
                        m.getValue()
                    ))
                    .collect(Collectors.toList());

                if (txAnalytics != null) {
                    List<AdvisorEvidenceDto> extra = new ArrayList<>();
                    if (txAnalytics.anomalies() != null) {
                        extra.addAll(txAnalytics.anomalies().stream().limit(3).map(a -> new AdvisorEvidenceDto(
                            "tx_anomaly",
                            "Anomalía " + a.date(),
                            "Score: " + String.format(Locale.ROOT, "%.2f", a.score()),
                            a.reason(),
                            a.net() == null ? null : a.net().toPlainString()
                        )).toList());
                    }
                    if (txAnalytics.categories() != null) {
                        extra.addAll(txAnalytics.categories().stream()
                            .sorted((a, b) -> b.outflows().abs().compareTo(a.outflows().abs()))
                            .limit(5)
                            .map(c -> new AdvisorEvidenceDto(
                                "tx_category",
                                c.category(),
                                "Salidas: " + c.outflows().toPlainString() + " · Entradas: " + c.inflows().toPlainString(),
                                "Total: " + c.total().toPlainString() + " · # " + c.count(),
                                null
                            ))
                            .toList());
                    }
                    if (!extra.isEmpty()) {
                        List<AdvisorEvidenceDto> merged = new ArrayList<>(cashEvidence);
                        merged.addAll(extra);
                        cashEvidence = merged;
                    }
                }
                actions.add(action(
                    horizon.primary,
                    "P0",
                    "Plan de caja (cobros/pagos)",
                    "Define calendario semanal de cobros/pagos, minimo de caja y acciones: renegociar plazos, frenar gastos no esenciales y acelerar cobros.",
                    "Runway, burn rate, flujo neto",
                    cashEvidence
                ));
            }
        }

        if (tribunal != null && tribunal.risk() != null && !tribunal.risk().isEmpty()) {
            TribunalRiskDto first = tribunal.risk().get(0);
            List<AdvisorEvidenceDto> riskEvidence = tribunal.risk().stream()
                .limit(10)
                .map(r -> new AdvisorEvidenceDto(
                    "tribunal_risk",
                    r.cliente(),
                    joinNonBlank(" · ",
                        r.cif() == null ? null : "CIF: " + r.cif(),
                        r.gestor() == null ? null : "Gestor: " + r.gestor()
                    ),
                    r.issues(),
                    null
                ))
                .collect(Collectors.toList());
            actions.add(action(
                horizon.primary,
                "P1",
                "Atacar clientes en riesgo (Tribunal)",
                "Prioriza una lista corta de clientes con incidencias (ej: '" + first.cliente() + "') y asigna responsable + fecha de cierre por incidencia.",
                "Riesgos por cliente/gestor",
                riskEvidence
            ));
        }

        if (tribunal != null && tribunal.kpis() != null && tribunal.kpis().totalClients() > 0) {
            double bajaPct = tribunal.kpis().bajaPct();
            double contPct = tribunal.kpis().contabilidadPct();
            double fiscalPct = tribunal.kpis().fiscalPct();

            if (bajaPct >= 15) {
                List<AdvisorEvidenceDto> bajaEvidence = tribunal.gestores() == null ? List.of() : tribunal.gestores().stream()
                    .sorted((a, b) -> Long.compare(b.activeClients(), a.activeClients()))
                    .limit(5)
                    .map(g -> new AdvisorEvidenceDto(
                        "tribunal_gestor",
                        g.gestor(),
                        "Activos: " + g.activeClients() + " / Total: " + g.totalClients(),
                        "Minuta media: " + g.minutasAvg() + " · Carga media: " + g.cargaAvg(),
                        null
                    ))
                    .collect(Collectors.toList());
                actions.add(action(
                    horizon.primary,
                    "P1",
                    "Reducir bajas (Tribunal)",
                    "Segmenta clientes de baja y revisa: precio vs valor, carga por gestor y calidad del servicio. Propón acciones de retención para los 20 con más riesgo.",
                    "% bajas",
                    bajaEvidence
                ));
            }

            if (contPct < 85) {
                List<AdvisorEvidenceDto> contEvidence = tribunal.gestores() == null ? List.of() : tribunal.gestores().stream()
                    .sorted((a, b) -> Long.compare(b.activeClients(), a.activeClients()))
                    .limit(5)
                    .map(g -> new AdvisorEvidenceDto(
                        "tribunal_gestor",
                        g.gestor(),
                        "Activos: " + g.activeClients() + " / Total: " + g.totalClients(),
                        "Minuta media: " + g.minutasAvg() + " · Carga media: " + g.cargaAvg(),
                        null
                    ))
                    .collect(Collectors.toList());
                actions.add(action(
                    horizon.primary,
                    "P0",
                    "Cerrar cumplimiento contable (Tribunal)",
                    "Define checklist semanal para CONT/MODELOS y corrige estados PDTE/NEGATIVO. Prioriza por impacto y por gestor.",
                    "% contabilidad",
                    contEvidence
                ));
            }

            if (fiscalPct < 85) {
                List<AdvisorEvidenceDto> fiscalEvidence = tribunal.gestores() == null ? List.of() : tribunal.gestores().stream()
                    .sorted((a, b) -> Long.compare(b.activeClients(), a.activeClients()))
                    .limit(5)
                    .map(g -> new AdvisorEvidenceDto(
                        "tribunal_gestor",
                        g.gestor(),
                        "Activos: " + g.activeClients() + " / Total: " + g.totalClients(),
                        "Minuta media: " + g.minutasAvg() + " · Carga media: " + g.cargaAvg(),
                        null
                    ))
                    .collect(Collectors.toList());
                actions.add(action(
                    horizon.secondary,
                    "P0",
                    "Cerrar cumplimiento fiscal (Tribunal)",
                    "Ataca IS/IRPF, DDCC y LIBROS con un sprint de regularización. Asigna responsables, fechas y evidencia por cliente.",
                    "% fiscal",
                    fiscalEvidence
                ));
            }

            if (tribunal.gestores() != null && !tribunal.gestores().isEmpty()) {
                var topCarga = tribunal.gestores().stream()
                    .max(Comparator.comparingDouble(g -> g.cargaAvg()))
                    .orElse(null);
                if (topCarga != null) {
                    List<AdvisorEvidenceDto> topGestores = tribunal.gestores().stream()
                        .sorted((a, b) -> Double.compare(b.cargaAvg(), a.cargaAvg()))
                        .limit(5)
                        .map(g -> new AdvisorEvidenceDto(
                            "tribunal_gestor",
                            g.gestor(),
                            "Activos: " + g.activeClients() + " / Total: " + g.totalClients(),
                            "Minuta media: " + g.minutasAvg() + " · Carga media: " + g.cargaAvg(),
                            null
                        ))
                        .collect(Collectors.toList());
                    actions.add(action(
                        horizon.primary,
                        "P1",
                        "Equilibrar carga por gestor (Tribunal)",
                        "El gestor con mayor carga media es '" + topCarga.gestor() + "' (" + topCarga.cargaAvg() + "). Rebalancea cartera y revisa minutas vs carga.",
                        "Carga media por gestor",
                        topGestores
                    ));
                }
            }
        }

        if (hasBudget) {
            actions.add(action(
                horizon.primary,
                "P0",
                "Plan mensual de margen",
                "Revisa el margen por mes y actua en el peor mes: reduce partidas de gasto y protege los ingresos con mayor contribucion.",
                "Margen mensual"
            ));
            actions.add(action(
                horizon.secondary,
                "P1",
                "Escenarios (sensibilidad)",
                "Simula +/-% en 2 drivers (ingresos y un bloque de gastos) y define umbrales de decision para el trimestre.",
                "Margen vs escenario"
            ));
        }

        if (intent == Intent.CASH) {
            actions.add(action(horizon.primary, "P0", "Calendario de caja", "Monta un calendario semanal de cobros/pagos y define un minimo de caja. Prioriza renegociar plazos si hay meses negativos.", "Runway / caja minima"));
        } else if (intent == Intent.COST) {
            List<AdvisorEvidenceDto> costEvidence = List.of();
            if (txAnalytics != null && txAnalytics.categories() != null) {
                costEvidence = txAnalytics.categories().stream()
                    .sorted((a, b) -> b.outflows().abs().compareTo(a.outflows().abs()))
                    .limit(6)
                    .map(c -> new AdvisorEvidenceDto(
                        "tx_category",
                        c.category(),
                        "Salidas: " + c.outflows().toPlainString(),
                        "Total: " + c.total().toPlainString() + " · # " + c.count(),
                        null
                    ))
                    .toList();
            }
            actions.add(action(
                horizon.primary,
                "P0",
                "Top 3 costes a atacar",
                "Identifica las 3 partidas con mayor peso y aplica: renegociación, sustitución, o eliminación de no esenciales.",
                "% gasto sobre ingresos",
                costEvidence
            ));
        } else if (intent == Intent.GROWTH) {
            actions.add(action(horizon.primary, "P0", "Crecimiento con control", "Define 2 palancas de ingresos y 1 limitador operativo; establece KPIs semanales para no destruir margen.", "Ingresos / margen"));
        } else if (intent == Intent.MARGIN) {
            actions.add(action(horizon.primary, "P0", "Mejorar margen", "Ataca la combinacion: precio, mix y coste unitario. Empieza por el driver con mayor variabilidad.", "Margen bruto/neto"));
        } else {
            actions.add(action(horizon.primary, "P1", "Quick wins de gestion", "Ordena cuentas, detecta meses outlier y crea un cuadro de mando mensual con 5 KPIs y responsables.", "KPIs mensuales"));
        }

        if (actions.isEmpty()) {
            actions.add(action("30d", "P1", "Definir objetivo", "Dime si priorizas margen, costes o caja para concretar recomendaciones.", "Objetivo"));
        }

        return actions.stream()
            .sorted(Comparator.comparing(AdvisorActionDto::priority))
            .collect(Collectors.toList());
    }

    private enum Intent { CASH, COST, GROWTH, RISK, MARGIN, GENERAL }

    private record Horizon(String primary, String secondary) {}

    private static Intent detectIntent(String msg) {
        String m = msg == null ? "" : msg.toLowerCase(Locale.ROOT);
        if (m.contains("caja") || m.contains("liquidez") || m.contains("tesorer")) return Intent.CASH;
        if (m.contains("coste") || m.contains("gasto") || m.contains("recortar")) return Intent.COST;
        if (m.contains("crecer") || m.contains("crecimiento") || m.contains("ventas")) return Intent.GROWTH;
        if (m.contains("riesgo") || m.contains("fraude") || m.contains("alerta")) return Intent.RISK;
        if (m.contains("margen") || m.contains("rentabil")) return Intent.MARGIN;
        return Intent.GENERAL;
    }

    private static Horizon detectHorizon(String msg) {
        String m = msg == null ? "" : msg.toLowerCase(Locale.ROOT);
        if (m.contains("90")) return new Horizon("90d", "60d");
        if (m.contains("60")) return new Horizon("60d", "90d");
        if (m.contains("30")) return new Horizon("30d", "60d");
        return new Horizon("30d", "60d");
    }

    private TribunalSummaryDto safeTribunal(Long companyId) {
        try {
            return tribunalImportService.getSummary(companyId);
        } catch (Exception ex) {
            return null;
        }
    }

    private record DashboardContext(String latestPeriod,
                                    List<KpiMonthly> kpis,
                                    List<DashboardMetricDto> metrics,
                                    List<DashboardInsightDto> insights) {}

    private DashboardContext loadDashboard(Long companyId) {
        try {
            var latestOpt = kpiMonthlyRepository.findFirstByCompanyIdOrderByPeriodDesc(companyId);
            if (latestOpt.isEmpty()) return null;
            String latest = latestOpt.get().getPeriod();
            YearMonth latestYm = YearMonth.parse(latest, DateTimeFormatter.ofPattern("yyyy-MM"));
            YearMonth fromYm = latestYm.minusMonths(11);
            String from = fromYm.format(DateTimeFormatter.ofPattern("yyyy-MM"));

            List<KpiMonthly> kpis = kpiMonthlyRepository.findByCompanyIdAndPeriodBetweenOrderByPeriodAsc(companyId, from, latest);
            var computed = dashboardMetricsService.compute(kpis);
            return new DashboardContext(latest, kpis, computed.metrics(), computed.insights());
        } catch (Exception ex) {
            return null;
        }
    }

    private TransactionAnalyticsDto loadTxAnalytics(Long companyId, DashboardContext dashboard) {
        try {
            if (dashboard == null || dashboard.latestPeriod == null || dashboard.latestPeriod.isBlank()) return null;
            return transactionAnalyticsService.analytics(companyId, dashboard.latestPeriod, null, null, null, null, null, null, 10);
        } catch (Exception ex) {
            return null;
        }
    }
}

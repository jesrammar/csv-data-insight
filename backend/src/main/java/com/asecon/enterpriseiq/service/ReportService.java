package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.Alert;
import com.asecon.enterpriseiq.model.Report;
import com.asecon.enterpriseiq.model.ReportFormat;
import com.asecon.enterpriseiq.model.ReportStatus;
import com.asecon.enterpriseiq.model.UniversalView;
import com.asecon.enterpriseiq.dto.UniversalChartDataDto;
import com.asecon.enterpriseiq.dto.UniversalSummaryDto;
import com.asecon.enterpriseiq.dto.UniversalViewRequest;
import com.asecon.enterpriseiq.repo.AlertRepository;
import com.asecon.enterpriseiq.repo.CompanySettingsRepository;
import com.asecon.enterpriseiq.repo.KpiMonthlyRepository;
import com.asecon.enterpriseiq.repo.ReportRepository;
import com.asecon.enterpriseiq.repo.UniversalViewRepository;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.text.NumberFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReportService {
    private static final String MONTHLY_REPORT_TEMPLATE = loadClasspathResource("reports/monthly-report-template.html");
    private static final String MONTHLY_REPORT_CSS = loadClasspathResource("reports/monthly-report.css");

    public record PreparedMonthlyReport(
        String html,
        Long selectedUniversalViewId,
        String selectedUniversalViewName,
        String selectedUniversalAggregationMode
    ) {}

    private final ReportRepository reportRepository;
    private final Path reportsRoot;
    private final KpiMonthlyRepository kpiMonthlyRepository;
    private final AlertRepository alertRepository;
    private final UniversalCsvService universalCsvService;
    private final UniversalViewRepository universalViewRepository;
    private final UniversalViewService universalViewService;
    private final CompanySettingsRepository companySettingsRepository;
    private final PeriodWorkflowService periodWorkflowService;

    public ReportService(ReportRepository reportRepository,
                         KpiMonthlyRepository kpiMonthlyRepository,
                         AlertRepository alertRepository,
                         UniversalCsvService universalCsvService,
                         UniversalViewRepository universalViewRepository,
                         UniversalViewService universalViewService,
                         CompanySettingsRepository companySettingsRepository,
                         PeriodWorkflowService periodWorkflowService,
                         @Value("${app.storage.reports}") String reportsRoot) {
        this.reportRepository = reportRepository;
        this.kpiMonthlyRepository = kpiMonthlyRepository;
        this.alertRepository = alertRepository;
        this.universalCsvService = universalCsvService;
        this.universalViewRepository = universalViewRepository;
        this.universalViewService = universalViewService;
        this.companySettingsRepository = companySettingsRepository;
        this.periodWorkflowService = periodWorkflowService;
        this.reportsRoot = Path.of(reportsRoot);
    }

    public byte[] renderPdfFromHtml(String htmlContent) {
        if (htmlContent == null) htmlContent = "";
        try (var baos = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(htmlContent, reportsRoot.toUri().toString());
            builder.toStream(baos);
            builder.run();
            return baos.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo generar PDF: " + ex.getMessage(), ex);
        }
    }

    public Report generateHtmlReport(Company company, String period, String htmlContent) throws IOException {
        return generateHtmlReport(company, period, htmlContent, null, null, null);
    }

    public Report generateHtmlReport(Company company, String period, PreparedMonthlyReport prepared) throws IOException {
        if (prepared == null) {
            return generateHtmlReport(company, period, "", null, null, null);
        }
        return generateHtmlReport(
            company,
            period,
            prepared.html(),
            prepared.selectedUniversalViewId(),
            prepared.selectedUniversalViewName(),
            prepared.selectedUniversalAggregationMode()
        );
    }

    public Report generateHtmlReport(Company company, String period, String htmlContent,
                                     Long selectedUniversalViewId,
                                     String selectedUniversalViewName,
                                     String selectedUniversalAggregationMode) throws IOException {
        Files.createDirectories(reportsRoot);
        if (company == null || company.getId() == null) {
            throw new IllegalArgumentException("Company is required");
        }
        String resolvedPeriod = period == null ? "" : period.trim();
        if (resolvedPeriod.isBlank()) {
            resolvedPeriod = YearMonth.now().minusMonths(1).toString();
        }

        Report previous = reportRepository.findTopByCompanyIdAndPeriodOrderByVersionNoDescCreatedAtDesc(company.getId(), resolvedPeriod).orElse(null);
        int nextVersion = previous == null || previous.getVersionNo() == null ? 1 : previous.getVersionNo() + 1;

        Report report = new Report();
        report.setCompany(company);
        report.setPeriod(resolvedPeriod);
        report.setFormat(ReportFormat.HTML);
        report.setStatus(ReportStatus.READY);
        report.setCreatedAt(Instant.now());
        report.setVersionNo(nextVersion);
        report.setSelectedUniversalViewId(selectedUniversalViewId);
        report.setSelectedUniversalViewName(selectedUniversalViewName);
        report.setSelectedUniversalAggregationMode(selectedUniversalAggregationMode);
        report = reportRepository.save(report);

        Path reportPath = reportsRoot.resolve("report-" + report.getId() + ".html");
        Files.writeString(reportPath, htmlContent == null ? "" : htmlContent, StandardCharsets.UTF_8);
        report.setStorageRef(reportPath.toString());
        report = reportRepository.save(report);
        periodWorkflowService.syncFromReport(report);
        return report;
    }

    public String loadReportContent(Report report) throws IOException {
        String ref = report == null ? null : report.getStorageRef();
        if (ref == null || ref.isBlank()) {
            throw new ResponseStatusException(HttpStatus.GONE, "El informe ya no está disponible (retención de storage). Vuelve a generarlo.");
        }
        Path p = Path.of(ref);
        if (!Files.exists(p)) {
            throw new ResponseStatusException(HttpStatus.GONE, "El informe ya no está disponible (archivo eliminado). Vuelve a generarlo.");
        }
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    public String buildHtmlTemplate(Company company, String period, String summary) {
        return buildHtmlTemplate(company, period, summary, (Long) null);
    }

    public PreparedMonthlyReport prepareMonthlyHtmlReport(Company company, String period, String summary, Long requestedUniversalViewId) {
        ResolvedUniversalView resolvedUniversalView = resolveUniversalViewForReport(company == null ? null : company.getId(), requestedUniversalViewId);
        return new PreparedMonthlyReport(
            buildHtmlTemplate(company, period, summary, resolvedUniversalView),
            resolvedUniversalView == null ? null : resolvedUniversalView.viewId(),
            resolvedUniversalView == null ? null : resolvedUniversalView.viewName(),
            resolvedUniversalView == null ? null : resolvedUniversalView.aggregationMode()
        );
    }

    public String buildHtmlTemplate(Company company, String period, String summary, Long requestedUniversalViewId) {
        ResolvedUniversalView resolvedUniversalView = resolveUniversalViewForReport(company == null ? null : company.getId(), requestedUniversalViewId);
        return buildHtmlTemplate(company, period, summary, resolvedUniversalView);
    }

    private String buildHtmlTemplate(Company company, String period, String summary, ResolvedUniversalView resolvedUniversalView) {
        var settings = company == null || company.getId() == null ? null : companySettingsRepository.findById(company.getId()).orElse(null);
        String consultancyName = settings == null ? null : settings.getReportConsultancyName();
        if (consultancyName == null || consultancyName.isBlank()) consultancyName = "EnterpriseIQ";

        String primaryColor = settings == null ? null : settings.getReportPrimaryColor();
        if (primaryColor == null || primaryColor.isBlank()) primaryColor = "#14b8a6";

        String logoUrl = settings == null ? null : settings.getReportLogoUrl();
        String footerText = settings == null ? null : settings.getReportFooterText();
        if (footerText == null || footerText.isBlank()) {
            footerText = "Documento generado automáticamente. Requiere revisión profesional antes de su envío.";
        }

        String logoInner;
        if (isAllowedInlineLogo(logoUrl)) {
            String safe = logoUrl.trim()
                .replace("\"", "%22")
                .replace("<", "")
                .replace(">", "");
            logoInner = "<img alt='logo' src=\"" + safe + "\" />";
        } else {
            logoInner = escape(consultancyName);
        }

        var kpi = (company == null || period == null) ? null : kpiMonthlyRepository.findByCompanyIdAndPeriod(company.getId(), period).orElse(null);
        List<Alert> alerts = (company == null || period == null)
            ? List.of()
            : alertRepository.findByCompanyIdAndPeriod(company.getId(), period);
        alerts = alerts.stream()
            .sorted(Comparator.comparing((Alert a) -> a.getCreatedAt() == null ? Instant.EPOCH : a.getCreatedAt()).reversed())
            .limit(12)
            .toList();

        var series = (company == null || period == null) ? List.<com.asecon.enterpriseiq.model.KpiMonthly>of() : last12(company.getId(), period);

        List<BigDecimal> netSeries = series.stream().map(com.asecon.enterpriseiq.model.KpiMonthly::getNetFlow).toList();
        List<BigDecimal> balSeries = series.stream().map(com.asecon.enterpriseiq.model.KpiMonthly::getEndingBalance).toList();
        List<BigDecimal> inSeries = series.stream().map(com.asecon.enterpriseiq.model.KpiMonthly::getInflows).toList();
        List<BigDecimal> outSeries = series.stream().map(com.asecon.enterpriseiq.model.KpiMonthly::getOutflows).toList();

        List<String> monthTicks = series.stream().map(s -> {
            try {
                YearMonth ym = YearMonth.parse(s.getPeriod());
                return ym.getMonth().getDisplayName(TextStyle.SHORT, new Locale("es", "ES")) + " " + String.valueOf(ym.getYear()).substring(2);
            } catch (Exception ignored) {
                return s.getPeriod();
            }
        }).toList();
        String monthLabels = monthTicks.stream().reduce((a, b) -> a + " · " + b).orElse("");

        String netSvg = svgLine(monthTicks, netSeries, primaryColor);
        String balSvg = svgLine(monthTicks, balSeries, "#60a5fa");
        String inOutSvg = svgBars(monthTicks, inSeries, outSeries, "#22c55e", "#fb7185");

        String kpiVerdict = kpi == null ? "Sin datos suficientes para evaluar." : kpiVerdict(kpi.getNetFlow(), kpi.getEndingBalance());

        String monthDonut = kpi == null ? "<div class='muted'>Sin datos.</div>" : svgDonut(
            abs(kpi.getInflows()),
            abs(kpi.getOutflows()),
            "Cobros",
            "Pagos",
            "#22c55e",
            "#fb7185"
        );

        String coverageGauge = kpi == null ? "<div class='muted'>Sin datos.</div>" : svgGauge(
            coveragePct(kpi.getEndingBalance(), kpi.getOutflows()),
            "Cobertura de pagos",
            "#22c55e",
            "#f59e0b",
            "#fb7185"
        );
        String distributionContext = kpi == null ? "Sin datos suficientes para comparar cobros y pagos en este periodo." : buildDistributionContext(kpi);
        String distributionFoot = kpi == null ? "Carga transacciones para leer mejor el equilibrio entre entradas y salidas." : buildDistributionFoot(kpi);
        String liquidityContext = kpi == null ? "Sin datos suficientes para medir la cobertura de pagos del periodo." : buildLiquidityContext(kpi);
        String liquidityFoot = kpi == null ? "La lectura de liquidez necesita saldo final y pagos del mes." : buildLiquidityFoot(kpi);
        String netContext = buildNetContext(series, monthTicks);
        String balanceContext = buildBalanceContext(series, monthTicks);
        String inOutContext = buildInOutContext(series, monthTicks);

        String kpiHtml = kpi == null ? "<div class='muted'>Sin KPIs para este periodo.</div>" : """
          <div class='kpi-grid'>
            <div class='kpi-card'>
              <div class='kpi-label'>Cobros</div>
              <div class='kpi-value'>%s</div>
              <div class='kpi-help'>Entradas de caja (clientes, ventas, otros ingresos).</div>
            </div>
            <div class='kpi-card'>
              <div class='kpi-label'>Pagos</div>
              <div class='kpi-value'>%s</div>
              <div class='kpi-help'>Salidas de caja (proveedores, nóminas, impuestos).</div>
            </div>
            <div class='kpi-card'>
              <div class='kpi-label'>Neto</div>
              <div class='kpi-value'>%s</div>
              <div class='kpi-help'>Cobros − pagos del mes. Si es negativo varios meses, hay tensión.</div>
            </div>
            <div class='kpi-card'>
              <div class='kpi-label'>Saldo final</div>
              <div class='kpi-value'>%s</div>
              <div class='kpi-help'>Saldo estimado al cierre. Si baja, se acerca un problema de liquidez.</div>
            </div>
          </div>
          <div class='verdict'>
            <span class='badge'>Lectura rápida</span>
            <span class='verdict-text'>%s</span>
          </div>
        """.formatted(
            fmtMoney(kpi.getInflows()),
            fmtMoney(kpi.getOutflows()),
            fmtMoney(kpi.getNetFlow()),
            fmtMoney(kpi.getEndingBalance()),
            escape(kpiVerdict)
        );

        String alertsHtml = alerts.isEmpty()
            ? "<div class='muted'>Sin alertas para este periodo.</div>"
            : alerts.stream().map(a -> {
                String title = alertTitle(a.getType());
                String sev = alertSeverity(a.getType());
                String why = alertWhy(a.getType());
                String action = alertAction(a.getType());
                String msg = a.getMessage() == null ? "" : a.getMessage();
                return """
                  <div class='alert'>
                    <div class='alert-head'>
                      <div class='alert-title'>%s</div>
                      <div class='alert-sev %s'>%s</div>
                    </div>
                    <div class='alert-msg'>%s</div>
                    <div class='alert-foot'>
                      <div><span class='label'>Por qué importa:</span> %s</div>
                      <div style='margin-top:6px;'><span class='label'>Acción sugerida:</span> %s</div>
                    </div>
                  </div>
                """.formatted(
                    escape(title),
                    escape(sev.toLowerCase(Locale.ROOT)),
                    escape(sev),
                    escape(msg),
                    escape(why),
                    escape(action)
                );
            }).reduce("", (acc, s) -> acc + s);

        String budgetHtml = "";
        UniversalSummaryDto uni = null;
        UniversalStoryContext universalStory = resolvedUniversalView == null ? null : resolvedUniversalView.storyContext();
        try {
            if (company != null) {
                uni = universalCsvService.latest(company.getId()).orElse(null);
                var budgetInsight = uni == null || uni.insights() == null ? null : uni.insights().stream()
                    .filter(i -> i != null && i.title() != null && i.title().toLowerCase(Locale.ROOT).contains("presupuesto"))
                    .findFirst()
                    .orElse(null);
                if (budgetInsight != null) {
                    budgetHtml = """
                      <div class='section-title'>Presupuesto (detectado)</div>
                      <div class='card'>
                        <div class='small'>%s</div>
                        <div class='muted' style='margin-top:10px;'>Tip: abre el Dashboard Presupuesto para ver gráficos y variaciones.</div>
                      </div>
                    """.formatted(escape(budgetInsight.message() == null ? "" : budgetInsight.message()));
                }
            }
        } catch (Exception ignored) {}

        String executive = buildExecutiveSummary(company, period, kpi, alerts);
        MonthlyStorySections storySections = buildStorySections(company, period, kpi, series, alerts, uni, universalStory);
        String storyHtml = storySections.primaryHtml() + storySections.secondaryHtml();
        String genAt = fmtGeneratedAt(ZonedDateTime.now(ZoneId.systemDefault()));

        if (MONTHLY_REPORT_TEMPLATE != null) {
            Map<String, String> placeholders = new LinkedHashMap<>();
            placeholders.put("REPORT_CSS", MONTHLY_REPORT_CSS.replace("{{PRIMARY_COLOR}}", escape(primaryColor)));
            placeholders.put("CONSULTANCY_NAME", escape(consultancyName));
            placeholders.put("LOGO_INNER", logoInner);
            placeholders.put("COMPANY_NAME", escape(company == null ? "—" : company.getName()));
            placeholders.put("PERIOD", escape(period == null ? "—" : period));
            placeholders.put("GENERATED_AT", escape(genAt));
            placeholders.put("SUMMARY", escape(summary == null ? "" : summary));
            placeholders.put("EXECUTIVE_SUMMARY", escape(executive));
            placeholders.put("PRIMARY_STORY_HTML", storySections.primaryHtml());
            placeholders.put("SECONDARY_STORY_HTML", storySections.secondaryHtml());
            placeholders.put("KPI_HTML", kpiHtml);
            placeholders.put("MONTH_DONUT", monthDonut);
            placeholders.put("COVERAGE_GAUGE", coverageGauge);
            placeholders.put("DISTRIBUTION_CONTEXT", escape(distributionContext));
            placeholders.put("DISTRIBUTION_FOOT", escape(distributionFoot));
            placeholders.put("LIQUIDITY_CONTEXT", escape(liquidityContext));
            placeholders.put("LIQUIDITY_FOOT", escape(liquidityFoot));
            placeholders.put("MONTH_LABELS", escape(monthLabels));
            placeholders.put("NET_CONTEXT", escape(netContext));
            placeholders.put("BALANCE_CONTEXT", escape(balanceContext));
            placeholders.put("INOUT_CONTEXT", escape(inOutContext));
            placeholders.put("NET_SVG", netSvg);
            placeholders.put("BALANCE_SVG", balSvg);
            placeholders.put("IN_OUT_SVG", inOutSvg);
            placeholders.put("ALERTS_HTML", alertsHtml);
            placeholders.put("BUDGET_HTML", budgetHtml);
            placeholders.put("FOOTER_TEXT", escape(footerText));
            return applyTemplate(MONTHLY_REPORT_TEMPLATE, placeholders);
        }

        return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset='utf-8'/>
          <title>EnterpriseIQ Report</title>
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
              --card: #ffffff;
              --soft: #f8fafc;
              --border: #e2e8f0;
              --navy: #0b1220;
              --navy2: #0f1b33;
              --accent: %s;
              --accent2: #60a5fa;
              --danger: #fb7185;
              --warn: #f59e0b;
              --success: #22c55e;
            }
            body { font-family: Arial, sans-serif; color: var(--ink); background: var(--bg); }
            h1,h2,h3 { margin: 0; }
            .muted { color: #475569; font-size: 12px; }
            .small { font-size: 12px; }
            .cover {
              padding: 30px 30px 34px;
              border-radius: 16px;
              background: linear-gradient(135deg, var(--navy) 0%%, var(--navy2) 60%%, #082f49 100%%);
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
            .brand { font-size: 18px; letter-spacing: 1px; text-transform: uppercase; opacity: 0.9; }
            .brand-row { display: table; width: 100%%; }
            .brand-left { display: table-cell; vertical-align: middle; }
            .brand-right { display: table-cell; vertical-align: middle; text-align: right; }
            .logo {
              display: inline-block;
              padding: 8px 10px;
              border-radius: 12px;
              border: 1px solid rgba(248,250,252,0.18);
              background: rgba(15,23,42,0.35);
              font-size: 12px;
              letter-spacing: .16em;
              text-transform: uppercase;
            }
            .logo img { height: 18px; width: auto; vertical-align: middle; display: block; }
            .title { margin-top: 24px; font-size: 34px; }
            .subtitle { margin-top: 10px; color: rgba(248,250,252,0.78); line-height: 1.5; }
            .meta { margin-top: 20px; display: table; }
            .meta .row { display: table-row; }
            .meta .k { display: table-cell; padding: 6px 18px 6px 0; opacity: 0.78; }
            .meta .v { display: table-cell; padding: 6px 0; font-weight: 700; }
            .page-break { page-break-after: always; }
            .section-title { margin: 18px 0 10px; font-size: 16px; color: #0f172a; }
            .card { border: 1px solid #e2e8f0; padding: 14px 14px; border-radius: 12px; background: #ffffff; }
            .kpi-grid { display: table; width: 100%%; table-layout: fixed; border-spacing: 10px; }
            .kpi-card { display: table-cell; border: 1px solid #e2e8f0; border-radius: 12px; padding: 12px; background: #f8fafc; vertical-align: top; }
            .kpi-label { font-size: 11px; letter-spacing: 0.12em; text-transform: uppercase; color: #475569; }
            .kpi-value { margin-top: 8px; font-size: 18px; font-weight: 800; color: #0f172a; }
            .kpi-help { margin-top: 6px; font-size: 11px; color: #64748b; line-height: 1.35; }
            .verdict { margin-top: 10px; padding: 10px 12px; border-radius: 12px; border: 1px solid #e2e8f0; background: #ffffff; }
            .verdict .badge { display: inline-block; font-size: 11px; letter-spacing: 0.12em; text-transform: uppercase; color: #0f172a; font-weight: 800; background: #e2e8f0; padding: 5px 8px; border-radius: 999px; }
            .verdict-text { margin-left: 10px; font-size: 12px; color: #0f172a; }

            .mini { border: 1px solid #e2e8f0; border-radius: 12px; overflow: hidden; background: #ffffff; }
            .mini table { width: 100%%; border-collapse: collapse; }
            .mini th, .mini td { padding: 10px 12px; border-bottom: 1px solid #e2e8f0; font-size: 12px; vertical-align: top; }
            .mini th { background: #f8fafc; text-align: left; color: #0f172a; width: 32%%; }
            .mini tr:last-child td, .mini tr:last-child th { border-bottom: 0; }
            .impact { display: inline-block; padding: 4px 8px; border-radius: 999px; border: 1px solid #e2e8f0; font-size: 11px; letter-spacing: 0.12em; text-transform: uppercase; font-weight: 900; }
            .impact.high { background: rgba(251,113,133,0.12); border-color: rgba(251,113,133,0.28); color: #9f1239; }
            .impact.medium { background: rgba(245,158,11,0.12); border-color: rgba(245,158,11,0.28); color: #92400e; }
            .impact.low { background: rgba(34,197,94,0.12); border-color: rgba(34,197,94,0.28); color: #065f46; }
            .story-grid { display: table; width: 100%%; table-layout: fixed; border-spacing: 10px; margin: -6px 0 0; }
            .insight { display: table-cell; border: 1px solid #e2e8f0; border-radius: 12px; padding: 12px; background: #ffffff; vertical-align: top; }
            .insight h3 { font-size: 13px; margin: 0; color: #0f172a; }
            .insight .why { margin-top: 8px; font-size: 12px; color: #334155; line-height: 1.35; }
            .insight .do { margin-top: 10px; font-size: 12px; color: #0f172a; line-height: 1.35; }
            .reco table { width: 100%%; border-collapse: collapse; }
            .reco th, .reco td { padding: 10px 12px; border-bottom: 1px solid #e2e8f0; font-size: 12px; vertical-align: top; }
            .reco th { background: #f8fafc; text-align: left; color: #0f172a; }
            .reco tr:last-child td, .reco tr:last-child th { border-bottom: 0; }
            .grid2 { display: table; width: 100%%; border-spacing: 10px; }
            .col { display: table-cell; width: 50%%; vertical-align: top; }
            .chart { border: 1px solid #e2e8f0; border-radius: 12px; padding: 12px; background: #ffffff; }
            .chart h3 { font-size: 13px; color: #0f172a; }
            .chart .sub { margin-top: 6px; font-size: 11px; color: #64748b; }
            .chart-legend { margin-top: 8px; font-size: 11px; color: #64748b; }
            .dot { display: inline-block; width: 10px; height: 10px; border-radius: 999px; margin-right: 6px; vertical-align: middle; }

            .alert { border: 1px solid #e2e8f0; padding: 12px; border-radius: 12px; background: #ffffff; margin-top: 10px; }
            .alert-head { display: table; width: 100%%; }
            .alert-title { display: table-cell; font-weight: 800; color: #0f172a; font-size: 13px; }
            .alert-sev { display: table-cell; text-align: right; font-size: 11px; letter-spacing: 0.12em; text-transform: uppercase; font-weight: 900; }
            .alert-sev.high { color: #9f1239; }
            .alert-sev.medium { color: #92400e; }
            .alert-sev.low { color: #065f46; }
            .alert-msg { margin-top: 8px; font-size: 12px; color: #0f172a; line-height: 1.45; }
            .alert-foot { margin-top: 10px; font-size: 11px; color: #334155; line-height: 1.4; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 12px; padding: 10px; }
            .label { color: #0f172a; font-weight: 800; }
            .footer { margin-top: 18px; font-size: 10px; color: #64748b; }
          </style>
        </head>
        <body>
          <div class='cover page-break'>
            <div class='brand-row'>
              <div class='brand-left'>
                <div class='brand'>%s</div>
              </div>
              <div class='brand-right'>
                <div class='logo'>%s</div>
              </div>
            </div>
            <div class='title'>Informe mensual</div>
            <div class='subtitle'>Portada, KPIs, tendencia y alertas explicadas (listo para enviar al cliente tras revisión).</div>
            <div class='meta'>
              <div class='row'><div class='k'>Empresa</div><div class='v'>%s</div></div>
              <div class='row'><div class='k'>Periodo</div><div class='v'>%s</div></div>
              <div class='row'><div class='k'>Generado</div><div class='v'>%s</div></div>
            </div>
          </div>

          <div class='section-title'>Resumen ejecutivo</div>
          <div class='card'>
            <div class='small'>%s</div>
            <div class='muted' style='margin-top:10px;'>%s</div>
          </div>

          %s

          <div class='section-title'>KPIs del periodo</div>
          %s

          <div class='section-title'>Radiografía del periodo</div>
          <div class='grid2'>
            <div class='col'>
              <div class='chart'>
                <h3>Distribución</h3>
                <div class='sub'>Cobros vs pagos (proporción)</div>
                %s
              </div>
            </div>
            <div class='col'>
              <div class='chart'>
                <h3>Liquidez</h3>
                <div class='sub'>Saldo final vs pagos del mes (cobertura)</div>
                %s
              </div>
            </div>
          </div>

          <div class='section-title'>Tendencia y caja (últimos 12 meses)</div>
          <div class='grid2'>
            <div class='col'>
              <div class='chart'>
                <h3>Neto mensual</h3>
                <div class='sub'>%s</div>
                %s
              </div>
            </div>
            <div class='col'>
              <div class='chart'>
                <h3>Saldo final</h3>
                <div class='sub'>%s</div>
                %s
              </div>
            </div>
          </div>

          <div class='grid2' style='margin-top: 0;'>
            <div class='col' style='width: 100%%;'>
              <div class='chart'>
                <h3>Cobros vs pagos</h3>
                <div class='sub'>%s</div>
                %s
                <div class='chart-legend'>
                  <span class='dot' style='background:%s;'></span>Cobros
                  <span style='margin-left:14px;'><span class='dot' style='background:%s;'></span>Pagos</span>
                </div>
              </div>
            </div>
          </div>

          <div class='section-title'>Alertas y riesgos</div>
          %s

          %s

          <div class='footer'>%s</div>
        </body>
        </html>
        """.formatted(
            escape(primaryColor),
            escape(consultancyName),
            logoInner,
            escape(company == null ? "—" : company.getName()),
            escape(period == null ? "—" : period),
            escape(genAt),
            escape(summary == null ? "" : summary),
            escape(executive),
            storyHtml,
            kpiHtml,
            monthDonut,
            coverageGauge,
            escape(monthLabels),
            netSvg,
            escape(monthLabels),
            balSvg,
            escape(monthLabels),
            inOutSvg,
            "#22c55e",
            "#fb7185",
            alertsHtml,
            budgetHtml,
            escape(footerText)
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
        try (InputStream input = ReportService.class.getClassLoader().getResourceAsStream(location)) {
            if (input == null) {
                throw new IllegalStateException("No se pudo cargar el recurso del informe: " + location);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo leer el recurso del informe: " + location, ex);
        }
    }

    private List<com.asecon.enterpriseiq.model.KpiMonthly> last12(Long companyId, String period) {
        try {
            YearMonth ym = YearMonth.parse(period);
            String from = ym.minusMonths(11).toString();
            return kpiMonthlyRepository.findByCompanyIdAndPeriodBetweenOrderByPeriodAsc(companyId, from, period);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String fmtMoney(BigDecimal v) {
        if (v == null) return "—";
        try {
            NumberFormat nf = NumberFormat.getCurrencyInstance(new Locale("es", "ES"));
            return nf.format(v.setScale(2, RoundingMode.HALF_UP));
        } catch (Exception ignored) {
            return v.setScale(2, RoundingMode.HALF_UP).toPlainString() + " €";
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String svgLine(List<String> ticks, List<BigDecimal> values, String stroke) {
        if (values == null || values.isEmpty()) {
            return "<div class='muted'>Sin datos.</div>";
        }
        if (values.size() == 1) {
            return svgSingleValuePanel(
                ticks != null && !ticks.isEmpty() ? ticks.get(0) : "Periodo",
                values.get(0),
                stroke == null ? "#0ea5e9" : stroke,
                "Solo hay un mes cargado. La evolucion se activara al tener al menos 2 periodos."
            );
        }
        int w = 560;
        int h = 212;
        int pad = 18;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double[] v = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            double d = values.get(i) == null ? 0.0 : values.get(i).doubleValue();
            v[i] = d;
            min = Math.min(min, d);
            max = Math.max(max, d);
        }
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            return "<div class='muted'>Sin datos.</div>";
        }
        if (Math.abs(max - min) < 1e-9) {
            max = min + 1.0;
        }

        double top = pad + 22;
        double bottom = h - pad - 30;

        StringBuilder points = new StringBuilder();
        StringBuilder area = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            double x = pad + (double) i * (double) (w - pad * 2) / (double) Math.max(1, v.length - 1);
            double y = top + (max - v[i]) * (bottom - top) / (max - min);
            if (i > 0) points.append(' ');
            points.append(String.format(Locale.ROOT, "%.1f,%.1f", x, y));
            if (i == 0) area.append(String.format(Locale.ROOT, "M %.1f %.1f ", x, y));
            else area.append(String.format(Locale.ROOT, "L %.1f %.1f ", x, y));
        }
        area.append(String.format(Locale.ROOT, "L %.1f %.1f L %.1f %.1f Z", (double) (w - pad), bottom, (double) pad, bottom));

        double yMin = bottom;
        double yMax = top;
        String tick1 = ticks != null && !ticks.isEmpty() ? ticks.get(0) : "";
        String tickN = ticks != null && !ticks.isEmpty() ? ticks.get(ticks.size() - 1) : "";
        String tickM = ticks != null && ticks.size() >= 3 ? ticks.get(ticks.size() / 2) : "";

        String grid = """
            <g opacity='0.55'>
              <line x1='%d' y1='%.1f' x2='%d' y2='%.1f' stroke='#20324f' stroke-width='1'/>
              <line x1='%d' y1='%.1f' x2='%d' y2='%.1f' stroke='#20324f' stroke-width='1'/>
              <line x1='%d' y1='%.1f' x2='%d' y2='%.1f' stroke='#20324f' stroke-width='1'/>
            </g>
        """.formatted(pad, yMin, w - pad, yMin, pad, (yMin + yMax) / 2.0, w - pad, (yMin + yMax) / 2.0, pad, yMax, w - pad, yMax);

        return """
          <svg width='%d' height='%d' viewBox='0 0 %d %d' xmlns='http://www.w3.org/2000/svg'>
            <defs>
              <linearGradient id='bg' x1='0' y1='0' x2='1' y2='1'>
                <stop offset='0' stop-color='#070b16'/>
                <stop offset='1' stop-color='#0b1220'/>
              </linearGradient>
              <linearGradient id='area' x1='0' y1='0' x2='0' y2='1'>
                <stop offset='0' stop-color='%s' stop-opacity='0.35'/>
                <stop offset='1' stop-color='%s' stop-opacity='0.02'/>
              </linearGradient>
            </defs>
            <rect x='0' y='0' width='%d' height='%d' rx='14' fill='url(#bg)'/>
            %s
            <path d='%s' fill='url(#area)'/>
            <polyline fill='none' stroke='%s' stroke-width='3.6' points='%s'/>
            <circle cx='%d' cy='%.1f' r='4.4' fill='%s'/>
            <circle cx='%d' cy='%.1f' r='4.4' fill='%s'/>
            <text x='%d' y='%d' font-size='12' fill='rgba(248,250,252,0.74)'>%s</text>
            <text x='%d' y='%d' font-size='12' fill='rgba(248,250,252,0.74)' text-anchor='middle'>%s</text>
            <text x='%d' y='%d' font-size='12' fill='rgba(248,250,252,0.74)' text-anchor='end'>%s</text>
          </svg>
        """.formatted(
            w, h, w, h,
            stroke == null ? "#0ea5e9" : stroke,
            stroke == null ? "#0ea5e9" : stroke,
            w, h,
            grid,
            area,
            stroke == null ? "#0ea5e9" : stroke,
            points,
            pad, top + (max - v[0]) * (bottom - top) / (max - min), stroke == null ? "#0ea5e9" : stroke,
            w - pad, top + (max - v[v.length - 1]) * (bottom - top) / (max - min), stroke == null ? "#0ea5e9" : stroke,
            pad, h - 10, escape(tick1),
            w / 2, h - 10, escape(tickM),
            w - pad, h - 10, escape(tickN)
        );
    }

    private static String svgBars(List<String> ticks, List<BigDecimal> seriesA, List<BigDecimal> seriesB, String colorA, String colorB) {
        if (seriesA == null || seriesA.isEmpty() || seriesB == null || seriesB.isEmpty()) return "<div class='muted'>Sin datos.</div>";
        int n = Math.min(seriesA.size(), seriesB.size());
        if (n == 0) return "<div class='muted'>Sin datos.</div>";
        if (n == 1) {
            return svgSingleComparePanel(
                ticks != null && !ticks.isEmpty() ? ticks.get(0) : "Periodo",
                seriesA.get(0),
                seriesB.get(0),
                colorA == null ? "#22c55e" : colorA,
                colorB == null ? "#fb7185" : colorB
            );
        }
        int w = 560;
        int h = 228;
        int pad = 18;
        double max = 0;
        double[] a = new double[n];
        double[] b = new double[n];
        for (int i = 0; i < n; i++) {
            a[i] = seriesA.get(i) == null ? 0.0 : Math.abs(seriesA.get(i).doubleValue());
            b[i] = seriesB.get(i) == null ? 0.0 : Math.abs(seriesB.get(i).doubleValue());
            max = Math.max(max, Math.max(a[i], b[i]));
        }
        if (max < 1e-9) max = 1.0;
        double top = pad + 22;
        double bottom = h - pad - 30;
        double plotH = bottom - top;
        double baseY = bottom;
        double groupW = (double) (w - pad * 2) / (double) Math.max(1, n);
        double barW = Math.max(3.0, groupW * 0.33);
        StringBuilder rects = new StringBuilder();
        for (int i = 0; i < n; i++) {
            double x0 = pad + i * groupW + (groupW - 2 * barW) / 2.0;
            double ha = (a[i] / max) * plotH;
            double hb = (b[i] / max) * plotH;
            rects.append(String.format(Locale.ROOT,
                "<rect x='%.1f' y='%.1f' width='%.1f' height='%.1f' rx='4' fill='url(#ga)'/>",
                x0, baseY - ha, barW, ha));
            rects.append(String.format(Locale.ROOT,
                "<rect x='%.1f' y='%.1f' width='%.1f' height='%.1f' rx='4' fill='url(#gb)'/>",
                x0 + barW + 4, baseY - hb, barW, hb));
        }

        String tick1 = ticks != null && !ticks.isEmpty() ? ticks.get(0) : "";
        String tickN = ticks != null && !ticks.isEmpty() ? ticks.get(ticks.size() - 1) : "";
        String tickM = ticks != null && ticks.size() >= 3 ? ticks.get(ticks.size() / 2) : "";

        String grid = """
            <g opacity='0.55'>
              <line x1='%d' y1='%.1f' x2='%d' y2='%.1f' stroke='#20324f' stroke-width='1'/>
              <line x1='%d' y1='%.1f' x2='%d' y2='%.1f' stroke='#20324f' stroke-width='1'/>
              <line x1='%d' y1='%.1f' x2='%d' y2='%.1f' stroke='#20324f' stroke-width='1'/>
            </g>
        """.formatted(pad, bottom, w - pad, bottom, pad, (bottom + top) / 2.0, w - pad, (bottom + top) / 2.0, pad, top, w - pad, top);

        return """
          <svg width='%d' height='%d' viewBox='0 0 %d %d' xmlns='http://www.w3.org/2000/svg'>
            <defs>
              <linearGradient id='bg' x1='0' y1='0' x2='1' y2='1'>
                <stop offset='0' stop-color='#070b16'/>
                <stop offset='1' stop-color='#0b1220'/>
              </linearGradient>
              <linearGradient id='ga' x1='0' y1='0' x2='0' y2='1'>
                <stop offset='0' stop-color='%s' stop-opacity='0.95'/>
                <stop offset='1' stop-color='%s' stop-opacity='0.35'/>
              </linearGradient>
              <linearGradient id='gb' x1='0' y1='0' x2='0' y2='1'>
                <stop offset='0' stop-color='%s' stop-opacity='0.95'/>
                <stop offset='1' stop-color='%s' stop-opacity='0.35'/>
              </linearGradient>
            </defs>
            <rect x='0' y='0' width='%d' height='%d' rx='14' fill='url(#bg)'/>
            %s
            %s
            <text x='%d' y='%d' font-size='12' fill='rgba(248,250,252,0.74)'>%s</text>
            <text x='%d' y='%d' font-size='12' fill='rgba(248,250,252,0.74)' text-anchor='middle'>%s</text>
            <text x='%d' y='%d' font-size='12' fill='rgba(248,250,252,0.74)' text-anchor='end'>%s</text>
          </svg>
        """.formatted(
            w, h, w, h,
            colorA == null ? "#22c55e" : colorA,
            colorA == null ? "#22c55e" : colorA,
            colorB == null ? "#fb7185" : colorB,
            colorB == null ? "#fb7185" : colorB,
            w, h,
            grid,
            rects.toString(),
            pad, h - 10, escape(tick1),
            w / 2, h - 10, escape(tickM),
            w - pad, h - 10, escape(tickN)
        );
    }

        private static String svgDonut(BigDecimal a, BigDecimal b, String labelA, String labelB, String colorA, String colorB) {
        double av = a == null ? 0.0 : a.doubleValue();
        double bv = b == null ? 0.0 : b.doubleValue();
        double total = Math.max(0.0, av) + Math.max(0.0, bv);
        if (total <= 0.0) return "<div class='muted'>Sin datos.</div>";
        double pA = Math.max(0.0, av) / total;
        int size = 280;
        int cx = 140;
        int cy = 132;
        int r = 92;
        double circ = 2.0 * Math.PI * r;
        double dashA = circ * pA;
        double dashB = circ - dashA;
        String ca = colorA == null ? "#22c55e" : colorA;
        String cb = colorB == null ? "#fb7185" : colorB;

        return """
          <svg width='%d' height='%d' viewBox='0 0 %d %d' xmlns='http://www.w3.org/2000/svg'>
            <defs>
              <linearGradient id='bg' x1='0' y1='0' x2='1' y2='1'>
                <stop offset='0' stop-color='#070b16'/>
                <stop offset='1' stop-color='#0b1220'/>
              </linearGradient>
            </defs>
            <rect x='0' y='0' width='%d' height='%d' rx='14' fill='url(#bg)'/>
            <circle cx='%d' cy='%d' r='%d' fill='none' stroke='#20324f' stroke-width='20'/>
            <g transform='rotate(-90 %d %d)'>
              <circle cx='%d' cy='%d' r='%d' fill='none' stroke='%s' stroke-width='20'
                stroke-linecap='round' stroke-dasharray='%.1f %.1f' stroke-dashoffset='0'/>
              <circle cx='%d' cy='%d' r='%d' fill='none' stroke='%s' stroke-width='20'
                stroke-linecap='round' stroke-dasharray='%.1f %.1f' stroke-dashoffset='%.1f'/>
            </g>
            <text x='%d' y='%d' font-size='18' fill='rgba(248,250,252,0.82)' text-anchor='middle'>%s</text>
            <text x='%d' y='%d' font-size='34' font-weight='800' fill='#f8fafc' text-anchor='middle'>%d%%</text>
            <text x='%d' y='%d' font-size='18' fill='rgba(248,250,252,0.76)' text-anchor='middle'>%s vs %s</text>
            <g>
              <circle cx='22' cy='242' r='6' fill='%s'/><text x='36' y='248' font-size='16' fill='rgba(248,250,252,0.88)'>%s</text>
              <circle cx='152' cy='242' r='6' fill='%s'/><text x='166' y='248' font-size='16' fill='rgba(248,250,252,0.88)'>%s</text>
            </g>
          </svg>
        """.formatted(
            size, size, size, size,
            size, size,
            cx, cy, r,
            cx, cy,
            cx, cy, r, ca, dashA, circ,
            cx, cy, r, cb, dashB, circ, dashA,
            cx, 94, "Proporcion",
            cx, 142, (int) Math.round(pA * 100.0),
            cx, 174, escape(labelA == null ? "A" : labelA), escape(labelB == null ? "B" : labelB),
            ca, escape(labelA == null ? "A" : labelA),
            cb, escape(labelB == null ? "B" : labelB)
        );
    }

        private static String svgGauge(double pct, String title, String ok, String warn, String bad) {
        double p = Double.isFinite(pct) ? Math.max(0, Math.min(100, pct)) : 0.0;
        String c;
        if (p >= 60) c = ok == null ? "#22c55e" : ok;
        else if (p >= 35) c = warn == null ? "#f59e0b" : warn;
        else c = bad == null ? "#fb7185" : bad;

        int w = 280;
        int h = 260;
        int cx = 140;
        int cy = 162;
        int r = 96;
        double start = Math.PI;
        double end = 2 * Math.PI;
        double angle = start + (end - start) * (p / 100.0);

        double x0 = cx - r;
        double y0 = cy;
        double x1 = cx + r;
        double y1 = cy;
        double x = cx + r * Math.cos(angle);
        double y = cy + r * Math.sin(angle);

        String dArc = "M " + fmt(x0) + " " + fmt(y0) + " A " + r + " " + r + " 0 0 1 " + fmt(x1) + " " + fmt(y1);
        String dVal = "M " + fmt(x0) + " " + fmt(y0) + " A " + r + " " + r + " 0 0 1 " + fmt(x) + " " + fmt(y);

        return """
          <svg width='%d' height='%d' viewBox='0 0 %d %d' xmlns='http://www.w3.org/2000/svg'>
            <defs>
              <linearGradient id='bg' x1='0' y1='0' x2='1' y2='1'>
                <stop offset='0' stop-color='#070b16'/>
                <stop offset='1' stop-color='#0b1220'/>
              </linearGradient>
            </defs>
            <rect x='0' y='0' width='%d' height='%d' rx='14' fill='url(#bg)'/>
            <path d='%s' fill='none' stroke='#20324f' stroke-width='20' stroke-linecap='round'/>
            <path d='%s' fill='none' stroke='%s' stroke-width='20' stroke-linecap='round'/>
            <circle cx='%d' cy='%d' r='5.6' fill='%s'/>
            <text x='%d' y='%d' font-size='18' fill='rgba(248,250,252,0.82)' text-anchor='middle'>%s</text>
            <text x='%d' y='%d' font-size='36' font-weight='900' fill='#f8fafc' text-anchor='middle'>%d%%</text>
            <text x='%d' y='%d' font-size='18' fill='rgba(248,250,252,0.76)' text-anchor='middle'>saldo / pagos</text>
          </svg>
        """.formatted(
            w, h, w, h,
            w, h,
            dArc,
            dVal,
            c,
            (int) Math.round(x), (int) Math.round(y), c,
            cx, 86, escape(title == null ? "" : title),
            cx, 144, (int) Math.round(p),
            cx, 182
        );
    }

        private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }

    private static String svgSingleValuePanel(String periodLabel, BigDecimal value, String accent, String note) {
        String safeAccent = accent == null ? "#0ea5e9" : accent;
        String safePeriod = escape(periodLabel == null ? "Periodo" : periodLabel);
        String safeValue = escape(fmtMoney(value));
        String safeNote = escape(note == null ? "" : note);
        return """
          <svg width='560' height='212' viewBox='0 0 560 212' xmlns='http://www.w3.org/2000/svg'>
            <defs>
              <linearGradient id='bg-single' x1='0' y1='0' x2='1' y2='1'>
                <stop offset='0' stop-color='#070b16'/>
                <stop offset='1' stop-color='#0b1220'/>
              </linearGradient>
            </defs>
            <rect x='0' y='0' width='560' height='212' rx='14' fill='url(#bg-single)'/>
            <circle cx='38' cy='36' r='8' fill='%s'/>
            <text x='56' y='40' font-size='14' fill='rgba(248,250,252,0.84)'>%s</text>
            <text x='32' y='110' font-size='34' font-weight='800' fill='#f8fafc'>%s</text>
            <text x='32' y='144' font-size='15' fill='rgba(248,250,252,0.72)'>Solo hay 1 dato en la serie.</text>
            <text x='32' y='170' font-size='13' fill='rgba(248,250,252,0.58)'>%s</text>
          </svg>
        """.formatted(safeAccent, safePeriod, safeValue, safeNote);
    }

    private static String svgSingleComparePanel(String periodLabel, BigDecimal inflows, BigDecimal outflows, String colorA, String colorB) {
        double a = abs(inflows).doubleValue();
        double b = abs(outflows).doubleValue();
        double max = Math.max(Math.max(a, b), 1.0d);
        double leftHeight = (a / max) * 96.0d;
        double rightHeight = (b / max) * 96.0d;
        return """
          <svg width='560' height='228' viewBox='0 0 560 228' xmlns='http://www.w3.org/2000/svg'>
            <defs>
              <linearGradient id='bg-compare' x1='0' y1='0' x2='1' y2='1'>
                <stop offset='0' stop-color='#070b16'/>
                <stop offset='1' stop-color='#0b1220'/>
              </linearGradient>
            </defs>
            <rect x='0' y='0' width='560' height='228' rx='14' fill='url(#bg-compare)'/>
            <text x='24' y='30' font-size='14' fill='rgba(248,250,252,0.84)'>%s</text>
            <text x='24' y='52' font-size='13' fill='rgba(248,250,252,0.62)'>Solo hay 1 periodo cargado: comparativa puntual, no tendencia.</text>
            <rect x='126' y='150' width='112' height='%.1f' rx='8' transform='translate(0 %.1f)' fill='%s'/>
            <rect x='322' y='150' width='112' height='%.1f' rx='8' transform='translate(0 %.1f)' fill='%s'/>
            <text x='182' y='202' font-size='14' fill='rgba(248,250,252,0.84)' text-anchor='middle'>Cobros %s</text>
            <text x='378' y='202' font-size='14' fill='rgba(248,250,252,0.84)' text-anchor='middle'>Pagos %s</text>
          </svg>
        """.formatted(
            escape(periodLabel == null ? "Periodo" : periodLabel),
            leftHeight,
            -leftHeight,
            colorA == null ? "#22c55e" : colorA,
            rightHeight,
            -rightHeight,
            colorB == null ? "#fb7185" : colorB,
            escape(fmtMoney(abs(inflows))),
            escape(fmtMoney(abs(outflows)))
        );
    }

    private static String buildDistributionContext(com.asecon.enterpriseiq.model.KpiMonthly kpi) {
        BigDecimal inflows = abs(kpi.getInflows());
        BigDecimal outflows = abs(kpi.getOutflows());
        BigDecimal net = safe(kpi.getNetFlow());
        String sign = net.compareTo(BigDecimal.ZERO) >= 0 ? "positivo" : "negativo";
        return "Cobros " + fmtMoney(inflows) + " frente a pagos " + fmtMoney(outflows) + ". El mes queda con neto " + sign + " de " + fmtMoney(net.abs()) + ".";
    }

    private static String buildDistributionFoot(com.asecon.enterpriseiq.model.KpiMonthly kpi) {
        BigDecimal inflows = abs(kpi.getInflows());
        BigDecimal outflows = abs(kpi.getOutflows());
        BigDecimal total = inflows.add(outflows);
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return "No hay movimiento suficiente para leer el equilibrio del periodo.";
        }
        BigDecimal outShare = outflows.divide(total, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
        if (outShare.compareTo(new BigDecimal("55")) >= 0) {
            return "Los pagos absorben " + outShare.setScale(0, RoundingMode.HALF_UP).toPlainString() + "% del movimiento del periodo: conviene vigilar margen de maniobra y calendario de cobros.";
        }
        return "La salida de caja pesa " + outShare.setScale(0, RoundingMode.HALF_UP).toPlainString() + "% del movimiento del periodo: la estructura del mes sigue siendo manejable si se sostiene el ritmo de cobro.";
    }

    private static String buildLiquidityContext(com.asecon.enterpriseiq.model.KpiMonthly kpi) {
        BigDecimal balance = safe(kpi.getEndingBalance());
        BigDecimal outflows = abs(kpi.getOutflows());
        double coverageRatio = outflows.compareTo(BigDecimal.ZERO) <= 0 ? 0.0 : balance.max(BigDecimal.ZERO).divide(outflows, 2, RoundingMode.HALF_UP).doubleValue();
        return "Saldo final " + fmtMoney(balance) + " para pagos de " + fmtMoney(outflows) + ". La caja cubre aprox. " + String.format(Locale.ROOT, "%.1fx", coverageRatio) + " los pagos del mes.";
    }

    private static String buildLiquidityFoot(com.asecon.enterpriseiq.model.KpiMonthly kpi) {
        double coverageRatio = coveragePct(kpi.getEndingBalance(), kpi.getOutflows()) / 100.0;
        if (coverageRatio < 1.0) {
            return "Cobertura por debajo de 1 mes: un retraso de cobro puede tensionar la operativa inmediata.";
        }
        if (coverageRatio < 1.5) {
            return "Cobertura ajustada: la empresa puede operar, pero con poco margen ante IVA, nominas o gastos no previstos.";
        }
        return "Cobertura razonable: hay colchon para operar sin tension inmediata, aunque conviene mantener disciplina de cobro.";
    }

    private static String buildNetContext(List<com.asecon.enterpriseiq.model.KpiMonthly> series, List<String> ticks) {
        if (series == null || series.isEmpty()) {
            return "Sin historico suficiente para leer la evolucion del neto mensual.";
        }
        int lastIdx = series.size() - 1;
        var last = series.get(lastIdx);
        String period = ticks != null && ticks.size() > lastIdx ? ticks.get(lastIdx) : last.getPeriod();
        String detail = "Ultimo dato " + String.valueOf(period) + ": " + fmtMoney(last.getNetFlow()) + ".";
        if (series.size() < 2) return detail;
        var prev = series.get(lastIdx - 1);
        BigDecimal delta = safe(last.getNetFlow()).subtract(safe(prev.getNetFlow()));
        return detail + " Variacion frente al mes anterior: " + fmtMoney(delta) + ".";
    }

    private static String buildBalanceContext(List<com.asecon.enterpriseiq.model.KpiMonthly> series, List<String> ticks) {
        if (series == null || series.isEmpty()) {
            return "Sin historico suficiente para leer la evolucion del saldo final.";
        }
        int lastIdx = series.size() - 1;
        var last = series.get(lastIdx);
        String period = ticks != null && ticks.size() > lastIdx ? ticks.get(lastIdx) : last.getPeriod();
        String detail = "Saldo final mas reciente (" + String.valueOf(period) + "): " + fmtMoney(last.getEndingBalance()) + ".";
        if (series.size() < 2) return detail;
        var prev = series.get(lastIdx - 1);
        BigDecimal delta = safe(last.getEndingBalance()).subtract(safe(prev.getEndingBalance()));
        return detail + " Movimiento frente al mes anterior: " + fmtMoney(delta) + ".";
    }

    private static String buildInOutContext(List<com.asecon.enterpriseiq.model.KpiMonthly> series, List<String> ticks) {
        if (series == null || series.isEmpty()) {
            return "Sin historico suficiente para comparar entradas y salidas.";
        }
        long negativeMonths = series.stream()
            .filter(item -> item != null && item.getNetFlow() != null && item.getNetFlow().compareTo(BigDecimal.ZERO) < 0)
            .count();
        String span = ticks == null || ticks.isEmpty() ? "la ventana visible" : (ticks.get(0) + " a " + ticks.get(ticks.size() - 1));
        if (negativeMonths > 0) {
            return "Comparativa mensual de cobros y pagos en " + span + ". Hay " + negativeMonths + " mes(es) donde la salida supera a la entrada.";
        }
        return "Comparativa mensual de cobros y pagos en " + span + ". En la ventana visible no aparecen meses con neto negativo.";
    }

    private static BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String formatCompactMoney(double value) {
        double abs = Math.abs(value);
        String suffix = "";
        double scaled = abs;
        if (abs >= 1000000d) {
            scaled = abs / 1000000d;
            suffix = "M";
        } else if (abs >= 1000d) {
            scaled = abs / 1000d;
            suffix = "k";
        }
        String number = scaled >= 100 ? String.format(Locale.ROOT, "%.0f", scaled) : String.format(Locale.ROOT, "%.1f", scaled);
        return (value < 0 ? "-" : "") + number + suffix + " EUR";
    }

    private static BigDecimal abs(BigDecimal v) {
        if (v == null) return BigDecimal.ZERO;
        return v.abs();
    }

    private static double coveragePct(BigDecimal endingBalance, BigDecimal outflows) {
        if (endingBalance == null || outflows == null) return 0.0;
        try {
            BigDecimal den = outflows.abs();
            if (den.compareTo(BigDecimal.ZERO) <= 0) return 0.0;
            BigDecimal pct = endingBalance.max(BigDecimal.ZERO).divide(den, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
            return pct.doubleValue();
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private static String kpiVerdict(BigDecimal netFlow, BigDecimal endingBalance) {
        if (endingBalance != null && endingBalance.compareTo(BigDecimal.ZERO) < 0) {
            return "Semáforo rojo: saldo final negativo. Riesgo de impagos si no se actúa.";
        }
        if (netFlow != null && netFlow.compareTo(BigDecimal.ZERO) < 0) {
            return "Semáforo ámbar: el mes cierra en negativo. Prioriza cobros y ajusta pagos.";
        }
        return "Semáforo verde: caja estable este mes. Mantén control de gastos fijos y reserva colchón.";
    }

    private static String fmtGeneratedAt(ZonedDateTime dt) {
        if (dt == null) return Instant.now().toString();
        try {
            return dt.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime().toString().replace('T', ' ');
        } catch (Exception ignored) {
            return dt.toString();
        }
    }

    private static String buildExecutiveSummary(Company company, String period, com.asecon.enterpriseiq.model.KpiMonthly kpi, List<Alert> alerts) {
        String name = company == null ? "Empresa" : company.getName();
        String p = period == null ? "—" : period;
        int nAlerts = alerts == null ? 0 : alerts.size();
        if (kpi == null) {
            return "No hay KPIs de caja para " + name + " en " + p + ". Sube transacciones para generar la lectura automática.";
        }
        String net = fmtMoney(kpi.getNetFlow());
        String bal = fmtMoney(kpi.getEndingBalance());
        String inflows = fmtMoney(kpi.getInflows());
        String outflows = fmtMoney(kpi.getOutflows());
        return "Periodo " + p + ": cobros " + inflows + ", pagos " + outflows + ", neto " + net + ", saldo final " + bal + ". Alertas detectadas: " + nAlerts + ".";
    }

    private record ActionableInsight(String title, String impact, String why, String action) {}
    private record MonthlyStorySections(String primaryHtml, String secondaryHtml) {}
    private record ResolvedUniversalView(
        Long viewId,
        String viewName,
        String aggregationMode,
        UniversalStoryContext storyContext
    ) {}
    private record UniversalStoryContext(
        String viewName,
        String aggregationMode,
        String sourceFilename,
        String chartHtml,
        String chartContext,
        String chartFoot
    ) {}

    private static boolean isAllowedInlineLogo(String raw) {
        if (raw == null) return false;
        String v = raw.trim();
        if (v.isEmpty() || v.length() > 500) return false;
        String lower = v.toLowerCase(Locale.ROOT);
        return lower.startsWith("data:image/png;base64,")
            || lower.startsWith("data:image/jpeg;base64,")
            || lower.startsWith("data:image/jpg;base64,")
            || lower.startsWith("data:image/webp;base64,")
            || lower.startsWith("data:image/gif;base64,");
    }

    private static MonthlyStorySections buildStorySections(Company company,
                                                           String period,
                                                           com.asecon.enterpriseiq.model.KpiMonthly kpi,
                                                           List<com.asecon.enterpriseiq.model.KpiMonthly> series,
                                                           List<Alert> alerts,
                                                           UniversalSummaryDto uni,
                                                           UniversalStoryContext universalStory) {
        if (kpi == null) {
            return new MonthlyStorySections("""
              <div class='section-title'>Qué falta para cerrar el informe</div>
              <div class='card'>
                <div class='small'><strong>Sube un CSV/XLSX de transacciones</strong> para este periodo (o usa el modo AUTO).</div>
                <div class='muted' style='margin-top:10px;'>Con transacciones, EnterpriseIQ calcula KPIs, tendencia y alertas, y el PDF añade recomendaciones accionables.</div>
              </div>
            """, "");
        }

        int nAlerts = alerts == null ? 0 : alerts.size();
        int highAlerts = alerts == null ? 0 : (int) alerts.stream()
            .filter(a -> "HIGH".equalsIgnoreCase(alertSeverity(a == null ? null : a.getType())))
            .count();

        double coverage = coveragePct(kpi.getEndingBalance(), kpi.getOutflows());
        String coverageLabel = coverage <= 0 ? "—" : String.format(Locale.ROOT, "%.0f%%", coverage * 100.0);

        com.asecon.enterpriseiq.model.KpiMonthly prev = (series == null || series.size() < 2) ? null : series.get(series.size() - 2);
        String netDelta = "—";
        try {
            if (prev != null && prev.getNetFlow() != null && kpi.getNetFlow() != null) {
                netDelta = fmtMoney(kpi.getNetFlow().subtract(prev.getNetFlow()));
            }
        } catch (Exception ignored) {}

        List<ActionableInsight> insights = new ArrayList<>();

        String verdict = kpiVerdict(kpi.getNetFlow(), kpi.getEndingBalance());
        String impact1 = (kpi.getEndingBalance() != null && kpi.getEndingBalance().compareTo(BigDecimal.ZERO) < 0) ? "HIGH"
            : (kpi.getNetFlow() != null && kpi.getNetFlow().compareTo(BigDecimal.ZERO) < 0) ? "MEDIUM"
            : "LOW";
        insights.add(new ActionableInsight(
            "Caja del mes: " + (impact1.equals("HIGH") ? "riesgo" : impact1.equals("MEDIUM") ? "a vigilar" : "estable"),
            impact1,
            verdict,
            impact1.equals("HIGH")
                ? "Plan 7–14 días: prioriza cobros, negocia vencimientos y corta pagos no críticos."
                : impact1.equals("MEDIUM")
                    ? "Revisa cobros pendientes y ajusta pagos. Objetivo: que el neto vuelva a positivo el mes próximo."
                    : "Mantén disciplina de cobros y reserva. Si hay crecimiento, define un colchón mínimo."
        ));

        if (coverage > 0 && coverage < 1.0) {
            insights.add(new ActionableInsight(
                "Liquidez: cobertura de pagos por debajo de 1 mes",
                "HIGH",
                "El saldo final no cubre los pagos del mes. Un retraso de cobro puede provocar tensión inmediata.",
                "Asegura recobro/anticipos y revisa calendario de pagos prioritarios (48–72h)."
            ));
        } else if (coverage > 0 && coverage < 1.6) {
            insights.add(new ActionableInsight(
                "Liquidez: cobertura ajustada (1–1,5 meses)",
                "MEDIUM",
                "La empresa opera con poco margen. Cualquier desvío (IVA, nóminas, devoluciones) se nota.",
                "Define umbral de caja y sube frecuencia de revisión (semanal)."
            ));
        } else if (coverage > 0) {
            insights.add(new ActionableInsight(
                "Liquidez: cobertura razonable",
                "LOW",
                "El saldo final cubre con holgura los pagos del mes, lo que reduce el riesgo operativo.",
                "Optimiza: negocia plazos, revisa gastos fijos y fija objetivo de reserva (meses de gastos)."
            ));
        }

        if (nAlerts > 0) {
            insights.add(new ActionableInsight(
                "Riesgos: " + nAlerts + " alertas (" + highAlerts + " alta prioridad)",
                highAlerts > 0 ? "HIGH" : "MEDIUM",
                highAlerts > 0 ? "Hay señales que pueden afectar caja o continuidad si no se actúa." : "Hay señales a revisar para evitar sorpresas en próximos cierres.",
                "Revisa primero las alertas HIGH y documenta acción + responsable (1–2 líneas)."
            ));
        }

        if (uni != null && uni.filename() != null && !uni.filename().isBlank()) {
            String universalViewName = universalStory == null ? null : universalStory.viewName();
            String universalMode = universalStory == null ? null : universalStory.aggregationMode();
            String datasetName = universalStory != null && !isBlank(universalStory.sourceFilename()) ? universalStory.sourceFilename() : uni.filename();
            insights.add(new ActionableInsight(
                !isBlank(universalViewName) ? "Universal: " + universalViewName : "Universal: dataset disponible para análisis adicional",
                "LOW",
                !isBlank(universalMode)
                    ? universalAggregationModeExecutiveLine(universalMode) + " Dataset origen: " + datasetName + "."
                    : "Hay un dataset cargado en Universal (" + uni.filename() + "). Útil para presupuestos, ventas o inventario.",
                !isBlank(universalViewName)
                    ? "Si quieres reutilizar esta lectura, abre \"" + universalViewName + "\" y valida que siga siendo la historia correcta para el cliente."
                    : "Si quieres convertirlo en dashboard, crea una vista (fecha+valor) y guarda una versión para comparar meses."
            ));
        }

        List<ActionableInsight> top = insights.stream().limit(3).toList();

        String insightsHtml = top.stream().map(i -> {
            String cls = i.impact() == null ? "medium" : i.impact().toLowerCase(Locale.ROOT);
            return """
              <div class='insight'>
                <div style='display:flex; justify-content:space-between; align-items:baseline; gap:10px;'>
                  <h3>%s</h3>
                  <span class='impact %s'>%s</span>
                </div>
                <div class='why'>%s</div>
                <div class='do'><span class='label'>Qué haría:</span> %s</div>
              </div>
            """.formatted(
                escape(i.title()),
                escape(cls),
                escape(i.impact() == null ? "MEDIUM" : i.impact()),
                escape(i.why()),
                escape(i.action())
            );
        }).reduce("", (a, b) -> a + b);

        String recoRows = top.stream().map(i -> {
            String cls = i.impact() == null ? "medium" : i.impact().toLowerCase(Locale.ROOT);
            return """
              <tr>
                <td><span class='impact %s'>%s</span></td>
                <td><strong>%s</strong><div class='muted' style='margin-top:6px;'>%s</div></td>
              </tr>
            """.formatted(
                escape(cls),
                escape(i.impact() == null ? "MEDIUM" : i.impact()),
                escape(i.action()),
                escape(i.why())
            );
        }).reduce("", (a, b) -> a + b);

        String quickFactsHtml = """
          <div class='fact-grid'>
            <div class='fact-card'>
              <div class='fact-label'>Periodo</div>
              <div class='fact-value'>%s</div>
              <div class='fact-help'>Cierre mensual actualmente analizado.</div>
            </div>
            <div class='fact-card'>
              <div class='fact-label'>Neto vs mes anterior</div>
              <div class='fact-value'>%s</div>
              <div class='fact-help'>Variación del neto mensual frente al cierre previo comparable.</div>
            </div>
            <div class='fact-card'>
              <div class='fact-label'>Cobertura de pagos</div>
              <div class='fact-value'>%s</div>
              <div class='fact-help'>Colchón estimado respecto al ritmo de pagos del periodo.</div>
            </div>
            <div class='fact-card'>
              <div class='fact-label'>Alertas activas</div>
              <div class='fact-value'>%s</div>
              <div class='fact-help'>%s</div>
            </div>
          </div>
        """.formatted(
            escape(period == null ? "—" : period),
            escape(netDelta),
            escape(coverageLabel),
            escape(nAlerts == 0 ? "Sin alertas" : String.valueOf(nAlerts)),
            escape(nAlerts == 0
                ? "No hay señales automáticas abiertas en este periodo."
                : highAlerts > 0
                    ? highAlerts + " de alta prioridad requieren atención primero."
                    : "Conviene documentar revisión y responsable antes de compartir.")
        );

        String primaryFocus = top.isEmpty()
            ? "Revisa KPIs y alertas antes de compartir el cierre con el cliente."
            : top.get(0).action();
        String primarySupportNote =
            highAlerts > 0
                ? "Resuelve primero las alertas de mayor prioridad; el resto del informe queda como respaldo y trazabilidad."
                : ("HIGH".equalsIgnoreCase(impact1) || "MEDIUM".equalsIgnoreCase(impact1))
                    ? "Si estas señales mejoran, el periodo gana estabilidad y el resto del informe aporta detalle histórico."
                    : "Si estas señales ya están validadas, el resto del informe queda como soporte y conversación con el cliente.";

        String primaryHtml = """
          <section class='report-section keep-together'>
            <div class='section-title'>Señales clave para decidir</div>
            <div class='section-intro'>Dos o tres señales claras para saber si seguimos, corregimos o cerramos el periodo.</div>
            <div class='story-grid'>%s</div>
            <div class='card story-bridge'>
              <div class='small'><strong>Siguiente foco sugerido:</strong> %s</div>
              <div class='muted top-gap'>%s</div>
            </div>
          </section>
        """.formatted(
            insightsHtml,
            escape(primaryFocus),
            escape(primarySupportNote)
        );

        String universalStoryHtml = "";
        if (universalStory != null) {
            String viewName = isBlank(universalStory.viewName()) ? "Vista guardada de Universal" : universalStory.viewName();
            String datasetLine = isBlank(universalStory.sourceFilename())
                ? ""
                : "<div class='muted' style='margin-top:6px;'>Dataset origen: " + escape(universalStory.sourceFilename()) + ".</div>";
            String chartContextLine = isBlank(universalStory.chartContext())
                ? ""
                : "<div class='chart-context'>" + escape(universalStory.chartContext()) + "</div>";
            String chartFootLine = isBlank(universalStory.chartFoot())
                ? ""
                : "<div class='chart-foot'>" + escape(universalStory.chartFoot()) + "</div>";
            String chartBlock = isBlank(universalStory.chartHtml())
                ? ""
                : """
                  <div class='chart keep-together' style='margin-top:12px;'>
                    <div class='chart-kicker'>Universal</div>
                    <h3>%s</h3>
                    <div class='sub'>%s</div>
                    %s
                    %s
                    %s
                  </div>
                """.formatted(
                    escape(viewName),
                    escape(universalAggregationModeExecutiveLine(universalStory.aggregationMode())),
                    chartContextLine,
                    universalStory.chartHtml(),
                    chartFootLine
                );
            universalStoryHtml = """
              <section class='report-section keep-together'>
                <div class='section-title'>Vista Universal enlazada</div>
                <div class='section-intro'>Cuando esta lectura se reutiliza en pantalla o en entregable, mantiene el mismo modo oficial.</div>
                <div class='card'>
                  <div class='small'><strong>%s</strong> · Modo oficial: %s</div>
                  <div class='muted' style='margin-top:10px;'>%s</div>
                  %s
                  %s
                </div>
              </section>
            """.formatted(
                escape(viewName),
                escape(universalAggregationModeLabel(universalStory.aggregationMode())),
                escape(universalAggregationModeExecutiveLine(universalStory.aggregationMode())),
                datasetLine,
                chartBlock
            );
        }

        String secondaryHtml = """
          <section class='report-section keep-together'>
            <div class='section-title'>Plan de acción sugerido</div>
            <div class='section-intro'>Acciones concretas para documentar seguimiento sin abrir todavía la parte más técnica.</div>
            <div class='card reco'>
              <table>
                <thead><tr><th>Impacto</th><th>Acción recomendada</th></tr></thead>
                <tbody>%s</tbody>
              </table>
              <div class='muted' style='margin-top:10px;'>Nota: estas recomendaciones son automáticas. Ajusta con contexto (estacionalidad, impuestos, cobros puntuales).</div>
            </div>
          </section>
          <section class='report-section keep-together'>
            <div class='section-title'>Ficha rápida del periodo</div>
            <div class='section-intro'>Solo el contexto mínimo para dejar rastro del cierre sin volver a una tabla larga.</div>
            %s
          </section>
          %s
        """.formatted(recoRows, quickFactsHtml, universalStoryHtml);

        return new MonthlyStorySections(primaryHtml, secondaryHtml);
    }

    private ResolvedUniversalView resolveUniversalViewForReport(Long companyId, Long requestedUniversalViewId) {
        if (companyId == null || universalViewRepository == null || universalViewService == null) return null;
        boolean explicitSelection = requestedUniversalViewId != null;
        UniversalView selectedView = explicitSelection
            ? universalViewRepository.findByIdAndCompanyId(requestedUniversalViewId, companyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "La vista Universal seleccionada no existe para esta empresa."))
            : universalViewRepository.findByCompanyIdOrderByCreatedAtDesc(companyId).stream().findFirst().orElse(null);
        if (selectedView == null) return null;
        try {
            UniversalViewRequest raw = universalViewService.decodeConfig(selectedView.getConfigJson());
            if (raw == null) raw = new UniversalViewRequest();
            if (isBlank(raw.getName())) raw.setName(selectedView.getName());
            if (isBlank(raw.getType())) raw.setType(selectedView.getType());

            UniversalViewRequest normalized = universalViewService.canonicalizeRequest(raw, companyId, selectedView.getSourceUniversalImportId());
            if (normalized == null) normalized = raw;
            if (isBlank(normalized.getName())) normalized.setName(selectedView.getName());

            String aggregationMode = normalized.getAggregationMode();
            UniversalChartDataDto chart = null;
            String chartHtml;
            String chartContext;
            String chartFoot;
            try {
                chart = universalViewService.previewSnapshot(companyId, normalized, selectedView.getSourceUniversalImportId());
                aggregationMode = firstNonBlank(aggregationMode, metaString(chart == null ? null : chart.meta(), "aggregationMode"));
                chartHtml = renderUniversalChartHtml(chart, aggregationMode);
                chartContext = buildUniversalChartContext(chart);
                chartFoot = buildUniversalChartFoot(chart);
            } catch (Exception chartEx) {
                if (!explicitSelection) return null;
                chartHtml = "<div class='muted'>No se pudo renderizar la vista Universal seleccionada para este entregable.</div>";
                chartContext = "La seleccion queda registrada, pero conviene revisar la vista guardada antes de compartir el PDF.";
                chartFoot = "Revisa la configuracion de la vista y genera de nuevo el entregable.";
            }

            String resolvedViewName = firstNonBlank(normalized.getName(), selectedView.getName());
            String sourceFilename = metaString(chart == null ? null : chart.meta(), "sourceFilename");
            return new ResolvedUniversalView(
                selectedView.getId(),
                resolvedViewName,
                aggregationMode,
                new UniversalStoryContext(
                    resolvedViewName,
                    aggregationMode,
                    sourceFilename,
                    chartHtml,
                    chartContext,
                    chartFoot
                )
            );
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            if (explicitSelection) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo preparar la vista Universal seleccionada para el entregable.", ex);
            }
            return null;
        }
    }

    private static String universalAggregationModeLabel(String value) {
        String key = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "ROW_COUNT" -> "Filas";
            case "DISTINCT_ENTRY_COUNT" -> "Asientos";
            case "DISTINCT_DOCUMENT_COUNT" -> "Documentos";
            case "DISTINCT_INVOICE_COUNT" -> "Facturas";
            case "DISTINCT_PARTY_COUNT" -> "Terceros";
            case "SUM_DEBIT" -> "Debe";
            case "SUM_CREDIT" -> "Haber";
            case "NET_BALANCE" -> "Saldo";
            case "SUM_AMOUNT" -> "Importe";
            case "AVG_VALUE" -> "Media";
            default -> "Modo pendiente";
        };
    }

    private static String universalAggregationModeExecutiveLine(String value) {
        String key = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "ROW_COUNT" -> "Esta vista cuenta filas operativas.";
            case "DISTINCT_ENTRY_COUNT" -> "Esta vista cuenta asientos distintos.";
            case "DISTINCT_DOCUMENT_COUNT" -> "Esta vista cuenta documentos distintos.";
            case "DISTINCT_INVOICE_COUNT" -> "Esta vista cuenta facturas distintas.";
            case "DISTINCT_PARTY_COUNT" -> "Esta vista cuenta terceros distintos.";
            case "SUM_DEBIT" -> "Esta vista suma importe en debe.";
            case "SUM_CREDIT" -> "Esta vista suma importe en haber.";
            case "NET_BALANCE" -> "Esta vista calcula saldo neto.";
            case "SUM_AMOUNT" -> "Esta vista suma importe monetario.";
            case "AVG_VALUE" -> "Esta vista calcula la media del valor.";
            default -> "Esta vista mantiene un modo oficial pendiente de lectura.";
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String renderUniversalChartHtml(UniversalChartDataDto chart, String aggregationMode) {
        if (chart == null) return "<div class='muted'>La vista guardada no devolvio grafico para este informe.</div>";
        String type = upper(chart.type());
        return switch (type) {
            case "TIME_SERIES" -> renderUniversalTimeSeries(chart, aggregationMode);
            case "CATEGORY_BAR" -> renderUniversalCategoryBar(chart, aggregationMode);
            case "KPI_CARDS" -> renderUniversalKpiCards(chart, aggregationMode);
            case "SCATTER" -> renderUniversalScatter(chart, aggregationMode);
            case "HEATMAP" -> renderUniversalHeatmap(chart, aggregationMode);
            case "PIVOT_MONTHLY" -> renderUniversalPivot(chart, aggregationMode);
            default -> "<div class='muted'>La vista guardada usa un tipo avanzado no imprimible todavia en este formato.</div>";
        };
    }

    private static String renderUniversalTimeSeries(UniversalChartDataDto chart, String aggregationMode) {
        Map<String, Object> series0 = chart.series() == null || chart.series().isEmpty() ? null : chart.series().get(0);
        List<BigDecimal> values = extractSeriesValues(series0);
        return svgLine(chart.labels(), values, universalAccentColor(aggregationMode));
    }

    private static String renderUniversalCategoryBar(UniversalChartDataDto chart, String aggregationMode) {
        Map<String, Object> series0 = chart.series() == null || chart.series().isEmpty() ? null : chart.series().get(0);
        List<BigDecimal> values = extractSeriesValues(series0);
        return svgHorizontalBars(chart.labels(), values, universalAccentColor(aggregationMode), aggregationMode);
    }

    private static String renderUniversalKpiCards(UniversalChartDataDto chart, String aggregationMode) {
        Map<String, Object> series0 = chart.series() == null || chart.series().isEmpty() ? null : chart.series().get(0);
        List<BigDecimal> values = extractSeriesValues(series0);
        List<String> labels = chart.labels() == null ? List.of() : chart.labels();
        if (labels.isEmpty() || values.isEmpty()) {
            return "<div class='muted'>Sin metricas suficientes para imprimir esta vista.</div>";
        }
        int count = Math.min(labels.size(), values.size());
        StringBuilder html = new StringBuilder("<div class='kpi-grid'>");
        for (int i = 0; i < count; i++) {
            html.append("""
              <div class='kpi-card'>
                <div class='kpi-label'>%s</div>
                <div class='kpi-value'>%s</div>
              </div>
            """.formatted(
                escape(humanizeMetricLabel(labels.get(i))),
                escape(formatUniversalValue(values.get(i), aggregationMode))
            ));
        }
        html.append("</div>");
        return html.toString();
    }

    private static String renderUniversalScatter(UniversalChartDataDto chart, String aggregationMode) {
        Map<String, Object> series0 = chart.series() == null || chart.series().isEmpty() ? null : chart.series().get(0);
        Object rawData = series0 == null ? null : series0.get("data");
        if (!(rawData instanceof List<?> points) || points.isEmpty()) {
            return "<div class='muted'>Sin puntos suficientes para representar la dispersion.</div>";
        }

        List<double[]> pairs = new ArrayList<>();
        for (Object point : points) {
            if (!(point instanceof List<?> row) || row.size() < 2) continue;
            Double x = toDouble(row.get(0));
            Double y = toDouble(row.get(1));
            if (x == null || y == null) continue;
            pairs.add(new double[] { x, y });
            if (pairs.size() >= 300) break;
        }
        if (pairs.isEmpty()) return "<div class='muted'>Sin puntos numericos suficientes para la dispersion.</div>";

        double minX = pairs.stream().mapToDouble(p -> p[0]).min().orElse(0d);
        double maxX = pairs.stream().mapToDouble(p -> p[0]).max().orElse(1d);
        double minY = pairs.stream().mapToDouble(p -> p[1]).min().orElse(0d);
        double maxY = pairs.stream().mapToDouble(p -> p[1]).max().orElse(1d);
        if (Math.abs(maxX - minX) < 1e-9) maxX = minX + 1d;
        if (Math.abs(maxY - minY) < 1e-9) maxY = minY + 1d;

        int w = 560;
        int h = 320;
        int left = 44;
        int right = 18;
        int top = 18;
        int bottom = 30;
        double plotW = w - left - right;
        double plotH = h - top - bottom;
        String accent = universalAccentColor(aggregationMode);

        StringBuilder circles = new StringBuilder();
        for (double[] pair : pairs) {
            double cx = left + ((pair[0] - minX) / (maxX - minX)) * plotW;
            double cy = top + (1d - ((pair[1] - minY) / (maxY - minY))) * plotH;
            circles.append(String.format(Locale.ROOT,
                "<circle cx='%.1f' cy='%.1f' r='3.2' fill='%s' fill-opacity='0.55'/>",
                cx, cy, accent));
        }

        return """
          <svg width='%d' height='%d' viewBox='0 0 %d %d' xmlns='http://www.w3.org/2000/svg'>
            <defs>
              <linearGradient id='bg-scatter' x1='0' y1='0' x2='1' y2='1'>
                <stop offset='0' stop-color='#070b16'/>
                <stop offset='1' stop-color='#0b1220'/>
              </linearGradient>
            </defs>
            <rect x='0' y='0' width='%d' height='%d' rx='14' fill='url(#bg-scatter)'/>
            <line x1='%d' y1='%d' x2='%d' y2='%d' stroke='#20324f' stroke-width='1.2'/>
            <line x1='%d' y1='%d' x2='%d' y2='%d' stroke='#20324f' stroke-width='1.2'/>
            <line x1='%d' y1='%d' x2='%d' y2='%d' stroke='#20324f' stroke-width='1'/>
            <line x1='%d' y1='%d' x2='%d' y2='%d' stroke='#20324f' stroke-width='1'/>
            %s
            <text x='%d' y='%d' font-size='11' fill='rgba(248,250,252,0.72)'>%s</text>
            <text x='%d' y='%d' font-size='11' fill='rgba(248,250,252,0.72)' text-anchor='end'>%s</text>
            <text x='%d' y='%d' font-size='11' fill='rgba(248,250,252,0.72)'>%s</text>
            <text x='%d' y='%d' font-size='11' fill='rgba(248,250,252,0.72)'>%s</text>
          </svg>
        """.formatted(
            w, h, w, h,
            w, h,
            left, h - bottom, w - right, h - bottom,
            left, top, left, h - bottom,
            left, top + (int) (plotH / 2), w - right, top + (int) (plotH / 2),
            left + (int) (plotW / 2), top, left + (int) (plotW / 2), h - bottom,
            circles,
            left, h - 8, escape(formatShortNumber(minX)),
            w - right, h - 8, escape(formatShortNumber(maxX)),
            10, top + 10, escape(formatShortNumber(maxY)),
            10, h - bottom, escape(formatShortNumber(minY))
        );
    }

    private static String renderUniversalHeatmap(UniversalChartDataDto chart, String aggregationMode) {
        List<String> xLabels = chart.labels() == null ? List.of() : chart.labels();
        List<String> yLabels = metaStringList(chart.meta(), "yLabels");
        Map<String, Object> series0 = chart.series() == null || chart.series().isEmpty() ? null : chart.series().get(0);
        Object rawData = series0 == null ? null : series0.get("data");
        if (xLabels.isEmpty() || yLabels.isEmpty() || !(rawData instanceof List<?> points) || points.isEmpty()) {
            return "<div class='muted'>Sin celdas suficientes para imprimir el heatmap.</div>";
        }

        double[][] matrix = new double[yLabels.size()][xLabels.size()];
        boolean[][] present = new boolean[yLabels.size()][xLabels.size()];
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (Object point : points) {
            if (!(point instanceof List<?> row) || row.size() < 3) continue;
            Double x = toDouble(row.get(0));
            Double y = toDouble(row.get(1));
            Double v = toDouble(row.get(2));
            if (x == null || y == null || v == null) continue;
            int xi = (int) Math.round(x);
            int yi = (int) Math.round(y);
            if (yi < 0 || yi >= yLabels.size() || xi < 0 || xi >= xLabels.size()) continue;
            matrix[yi][xi] = v;
            present[yi][xi] = true;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            return "<div class='muted'>Sin celdas numericas suficientes para imprimir el heatmap.</div>";
        }
        if (Math.abs(max - min) < 1e-9) max = min + 1d;

        int w = 560;
        int h = Math.max(230, 78 + yLabels.size() * 28);
        int left = 120;
        int top = 22;
        int right = 18;
        int bottom = 28;
        double cellW = (double) (w - left - right) / Math.max(1, xLabels.size());
        double cellH = (double) (h - top - bottom) / Math.max(1, yLabels.size());
        String accent = universalAccentColor(aggregationMode);

        StringBuilder cells = new StringBuilder();
        for (int y = 0; y < yLabels.size(); y++) {
            for (int x = 0; x < xLabels.size(); x++) {
                if (!present[y][x]) continue;
                double value = matrix[y][x];
                double opacity = 0.18d + (((value - min) / (max - min)) * 0.72d);
                double cx = left + x * cellW;
                double cy = top + y * cellH;
                cells.append(String.format(Locale.ROOT,
                    "<rect x='%.1f' y='%.1f' width='%.1f' height='%.1f' rx='5' fill='%s' fill-opacity='%.3f'/>",
                    cx + 2, cy + 2, Math.max(10d, cellW - 4), Math.max(10d, cellH - 4), accent, opacity));
            }
        }

        StringBuilder xText = new StringBuilder();
        for (int i = 0; i < xLabels.size(); i++) {
            double x = left + i * cellW + (cellW / 2d);
            xText.append(String.format(Locale.ROOT,
                "<text x='%.1f' y='%d' font-size='10' fill='rgba(248,250,252,0.70)' text-anchor='middle'>%s</text>",
                x, h - 8, escape(truncateLabel(xLabels.get(i), 12))));
        }
        StringBuilder yText = new StringBuilder();
        for (int i = 0; i < yLabels.size(); i++) {
            double y = top + i * cellH + (cellH / 2d) + 4d;
            yText.append(String.format(Locale.ROOT,
                "<text x='%d' y='%.1f' font-size='10' fill='rgba(248,250,252,0.70)'>%s</text>",
                12, y, escape(truncateLabel(yLabels.get(i), 20))));
        }

        return """
          <svg width='%d' height='%d' viewBox='0 0 %d %d' xmlns='http://www.w3.org/2000/svg'>
            <defs>
              <linearGradient id='bg-heatmap' x1='0' y1='0' x2='1' y2='1'>
                <stop offset='0' stop-color='#070b16'/>
                <stop offset='1' stop-color='#0b1220'/>
              </linearGradient>
            </defs>
            <rect x='0' y='0' width='%d' height='%d' rx='14' fill='url(#bg-heatmap)'/>
            %s
            %s
            %s
          </svg>
        """.formatted(w, h, w, h, w, h, cells, xText, yText);
    }

    private static String renderUniversalPivot(UniversalChartDataDto chart, String aggregationMode) {
        if (chart.series() == null || chart.series().isEmpty()) {
            return "<div class='muted'>Sin series suficientes para imprimir la comparativa mensual.</div>";
        }
        if (chart.labels() == null || chart.labels().isEmpty()) {
            return "<div class='muted'>Sin periodos suficientes para imprimir la comparativa mensual.</div>";
        }
        if (chart.labels().size() == 1) {
            List<String> categories = new ArrayList<>();
            List<BigDecimal> values = new ArrayList<>();
            for (Map<String, Object> series : chart.series()) {
                categories.add(String.valueOf(series.getOrDefault("name", "Serie")));
                List<BigDecimal> seriesValues = extractSeriesValues(series);
                values.add(seriesValues.isEmpty() ? BigDecimal.ZERO : seriesValues.get(0));
            }
            return svgHorizontalBars(categories, values, universalAccentColor(aggregationMode), aggregationMode);
        }

        List<Map<String, Object>> limitedSeries = chart.series().size() > 4 ? chart.series().subList(0, 4) : chart.series();
        return svgMultiSeriesLines(chart.labels(), limitedSeries, aggregationMode);
    }

    private static String buildUniversalChartContext(UniversalChartDataDto chart) {
        if (chart == null) return null;
        List<String> bits = new ArrayList<>();
        Map<String, Object> meta = chart.meta();
        String sourceFilename = metaString(meta, "sourceFilename");
        String rowsUsed = metaString(meta, "rowsUsed");
        String dateColumn = metaString(meta, "dateColumn");
        String categoryColumn = metaString(meta, "categoryColumn");
        String xColumn = metaString(meta, "xColumn");
        String yColumn = metaString(meta, "yColumn");
        String valueColumn = metaString(meta, "valueColumn");
        if (!isBlank(sourceFilename)) bits.add("Dataset: " + sourceFilename);
        if (!isBlank(rowsUsed)) bits.add("Filas usadas: " + rowsUsed);
        if (!isBlank(dateColumn)) bits.add("Fecha: " + dateColumn);
        if (!isBlank(categoryColumn)) bits.add("Categoria: " + categoryColumn);
        if (!isBlank(xColumn) && !isBlank(yColumn)) bits.add("Ejes: " + xColumn + " x " + yColumn);
        if (!isBlank(valueColumn)) bits.add("Medida: " + valueColumn);
        return bits.isEmpty() ? null : String.join(" | ", bits);
    }

    private static String buildUniversalChartFoot(UniversalChartDataDto chart) {
        if (chart == null) return null;
        List<String> warnings = metaStringList(chart.meta(), "warnings");
        if (!warnings.isEmpty()) return warnings.get(0);
        String type = upper(chart.type());
        return switch (type) {
            case "SCATTER" -> "Cada punto representa una observacion del dataset seleccionado.";
            case "HEATMAP" -> "El color mas intenso marca mayor concentracion relativa dentro de la vista.";
            case "PIVOT_MONTHLY" -> "La vista muestra las series con mayor peso dentro del pivote mensual guardado.";
            default -> null;
        };
    }

    private static String svgHorizontalBars(List<String> labels, List<BigDecimal> values, String accent, String aggregationMode) {
        if (labels == null || values == null || labels.isEmpty() || values.isEmpty()) {
            return "<div class='muted'>Sin datos suficientes para este ranking.</div>";
        }
        int n = Math.min(labels.size(), values.size());
        int w = 560;
        int h = Math.max(190, 56 + (n * 28));
        int left = 176;
        int right = 54;
        int top = 22;
        int bottom = 18;
        double plotW = w - left - right;
        double min = 0d;
        double max = 0d;
        for (int i = 0; i < n; i++) {
            double value = values.get(i) == null ? 0d : values.get(i).doubleValue();
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        if (Math.abs(max - min) < 1e-9) {
            max = max + 1d;
            min = min - 1d;
        }
        double zeroX = left + ((0d - min) / (max - min)) * plotW;
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < n; i++) {
            double value = values.get(i) == null ? 0d : values.get(i).doubleValue();
            double rowY = top + (i * 28d);
            double width = Math.abs(value) / (max - min) * plotW;
            double barX = value >= 0 ? zeroX : zeroX - width;
            String barColor = value < 0 ? "#fb7185" : accent;
            double valueTextX = value >= 0 ? Math.min(w - right, barX + width + 8) : Math.max(8, barX - 8);
            String anchor = value >= 0 ? "start" : "end";
            rows.append(String.format(Locale.ROOT,
                "<text x='12' y='%.1f' font-size='11' fill='rgba(248,250,252,0.82)'>%s</text>",
                rowY + 12, escape(truncateLabel(labels.get(i), 24))));
            rows.append(String.format(Locale.ROOT,
                "<rect x='%.1f' y='%.1f' width='%.1f' height='14' rx='6' fill='%s' fill-opacity='0.92'/>",
                barX, rowY, Math.max(2d, width), barColor));
            rows.append(String.format(Locale.ROOT,
                "<text x='%.1f' y='%.1f' font-size='11' fill='rgba(248,250,252,0.82)' text-anchor='%s'>%s</text>",
                valueTextX, rowY + 11, anchor, escape(formatUniversalValue(values.get(i), aggregationMode))));
        }

        return """
          <svg width='%d' height='%d' viewBox='0 0 %d %d' xmlns='http://www.w3.org/2000/svg'>
            <defs>
              <linearGradient id='bg-bars' x1='0' y1='0' x2='1' y2='1'>
                <stop offset='0' stop-color='#070b16'/>
                <stop offset='1' stop-color='#0b1220'/>
              </linearGradient>
            </defs>
            <rect x='0' y='0' width='%d' height='%d' rx='14' fill='url(#bg-bars)'/>
            <line x1='%.1f' y1='%d' x2='%.1f' y2='%d' stroke='#20324f' stroke-width='1.2'/>
            %s
          </svg>
        """.formatted(w, h, w, h, w, h, zeroX, top - 4, zeroX, h - bottom, rows);
    }

    private static String svgMultiSeriesLines(List<String> labels, List<Map<String, Object>> seriesList, String aggregationMode) {
        if (labels == null || labels.isEmpty() || seriesList == null || seriesList.isEmpty()) {
            return "<div class='muted'>Sin series suficientes para imprimir la tendencia.</div>";
        }
        int w = 560;
        int h = 288;
        int pad = 18;
        int top = 24;
        int bottom = 46;
        int left = 18;
        int right = 18;
        String[] palette = { "#60a5fa", "#22c55e", "#f59e0b", "#fb7185" };

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        List<List<BigDecimal>> valuesBySeries = new ArrayList<>();
        for (Map<String, Object> series : seriesList) {
            List<BigDecimal> values = extractSeriesValues(series);
            valuesBySeries.add(values);
            for (BigDecimal value : values) {
                if (value == null) continue;
                min = Math.min(min, value.doubleValue());
                max = Math.max(max, value.doubleValue());
            }
        }
        if (!Double.isFinite(min) || !Double.isFinite(max)) return "<div class='muted'>Sin valores suficientes para imprimir la tendencia.</div>";
        if (Math.abs(max - min) < 1e-9) max = min + 1d;

        double plotW = w - left - right;
        double plotH = h - top - bottom;
        StringBuilder lines = new StringBuilder();
        StringBuilder legend = new StringBuilder();

        for (int s = 0; s < seriesList.size(); s++) {
            Map<String, Object> series = seriesList.get(s);
            List<BigDecimal> values = valuesBySeries.get(s);
            String color = palette[s % palette.length];
            StringBuilder polyline = new StringBuilder();
            for (int i = 0; i < Math.min(labels.size(), values.size()); i++) {
                double x = left + (labels.size() == 1 ? plotW / 2d : (double) i * plotW / (double) Math.max(1, labels.size() - 1));
                double y = top + (max - values.get(i).doubleValue()) * plotH / (max - min);
                if (i > 0) polyline.append(' ');
                polyline.append(String.format(Locale.ROOT, "%.1f,%.1f", x, y));
            }
            lines.append(String.format(Locale.ROOT,
                "<polyline fill='none' stroke='%s' stroke-width='3' points='%s'/>",
                color, polyline));
            if (!values.isEmpty()) {
                double lastX = left + (labels.size() == 1 ? plotW / 2d : plotW);
                double lastY = top + (max - values.get(Math.min(values.size(), labels.size()) - 1).doubleValue()) * plotH / (max - min);
                lines.append(String.format(Locale.ROOT,
                    "<circle cx='%.1f' cy='%.1f' r='4.2' fill='%s'/>",
                    lastX, lastY, color));
            }
            legend.append(String.format(Locale.ROOT,
                "<text x='%d' y='%d' font-size='11' fill='rgba(248,250,252,0.84)'><tspan fill='%s'>*</tspan> %s</text>",
                left + (s * 132), h - 16, color, escape(truncateLabel(String.valueOf(series.getOrDefault("name", "Serie")), 18))));
        }

        String tick1 = labels.isEmpty() ? "" : labels.get(0);
        String tickM = labels.size() >= 3 ? labels.get(labels.size() / 2) : "";
        String tickN = labels.isEmpty() ? "" : labels.get(labels.size() - 1);

        return """
          <svg width='%d' height='%d' viewBox='0 0 %d %d' xmlns='http://www.w3.org/2000/svg'>
            <defs>
              <linearGradient id='bg-multi' x1='0' y1='0' x2='1' y2='1'>
                <stop offset='0' stop-color='#070b16'/>
                <stop offset='1' stop-color='#0b1220'/>
              </linearGradient>
            </defs>
            <rect x='0' y='0' width='%d' height='%d' rx='14' fill='url(#bg-multi)'/>
            <line x1='%d' y1='%d' x2='%d' y2='%d' stroke='#20324f' stroke-width='1'/>
            <line x1='%d' y1='%.1f' x2='%d' y2='%.1f' stroke='#20324f' stroke-width='1'/>
            <line x1='%d' y1='%d' x2='%d' y2='%d' stroke='#20324f' stroke-width='1'/>
            %s
            <text x='%d' y='%d' font-size='11' fill='rgba(248,250,252,0.72)'>%s</text>
            <text x='%d' y='%d' font-size='11' fill='rgba(248,250,252,0.72)' text-anchor='middle'>%s</text>
            <text x='%d' y='%d' font-size='11' fill='rgba(248,250,252,0.72)' text-anchor='end'>%s</text>
            %s
          </svg>
        """.formatted(
            w, h, w, h,
            w, h,
            left, h - bottom, w - right, h - bottom,
            left, top + (plotH / 2d), w - right, top + (plotH / 2d),
            left, top, w - right, top,
            lines,
            left, h - 28, escape(truncateLabel(tick1, 12)),
            w / 2, h - 28, escape(truncateLabel(tickM, 12)),
            w - right, h - 28, escape(truncateLabel(tickN, 12)),
            legend
        );
    }

    private static List<BigDecimal> extractSeriesValues(Map<String, Object> series) {
        Object rawData = series == null ? null : series.get("data");
        if (!(rawData instanceof List<?> list) || list.isEmpty()) return List.of();
        List<BigDecimal> out = new ArrayList<>(list.size());
        for (Object item : list) {
            BigDecimal value = toBigDecimal(item);
            out.add(value == null ? BigDecimal.ZERO : value);
        }
        return out;
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Integer i) return BigDecimal.valueOf(i.longValue());
        if (value instanceof Long l) return BigDecimal.valueOf(l);
        if (value instanceof Double d && Double.isFinite(d)) return BigDecimal.valueOf(d);
        if (value instanceof Float f && Float.isFinite(f)) return BigDecimal.valueOf(f.doubleValue());
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Double toDouble(Object value) {
        BigDecimal decimal = toBigDecimal(value);
        return decimal == null ? null : decimal.doubleValue();
    }

    private static String metaString(Map<String, Object> meta, String key) {
        if (meta == null || key == null) return null;
        Object value = meta.get(key);
        if (value == null) return null;
        String raw = String.valueOf(value).trim();
        return raw.isEmpty() ? null : raw;
    }

    private static List<String> metaStringList(Map<String, Object> meta, String key) {
        if (meta == null || key == null) return List.of();
        Object value = meta.get(key);
        if (!(value instanceof List<?> list) || list.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String raw = item == null ? null : String.valueOf(item).trim();
            if (!isBlank(raw)) out.add(raw);
        }
        return out;
    }

    private static String humanizeMetricLabel(String raw) {
        if (isBlank(raw)) return "Metrica";
        String value = raw.trim().replace('_', ' ');
        if ("filas origen".equalsIgnoreCase(value)) return "Filas origen";
        if ("media fila".equalsIgnoreCase(value)) return "Media fila";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String formatUniversalValue(BigDecimal value, String aggregationMode) {
        if (value == null) return "—";
        String mode = upper(aggregationMode);
        if ("SUM_AMOUNT".equals(mode) || "SUM_DEBIT".equals(mode) || "SUM_CREDIT".equals(mode) || "NET_BALANCE".equals(mode)) {
            return fmtMoney(value);
        }
        if ("AVG_VALUE".equals(mode)) {
            return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        }
        BigDecimal normalized = value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        return normalized.scale() <= 0 ? normalized.toPlainString() : normalized.toPlainString();
    }

    private static String universalAccentColor(String aggregationMode) {
        return switch (upper(aggregationMode)) {
            case "ROW_COUNT", "DISTINCT_ENTRY_COUNT", "DISTINCT_DOCUMENT_COUNT", "DISTINCT_INVOICE_COUNT", "DISTINCT_PARTY_COUNT" -> "#60a5fa";
            case "AVG_VALUE" -> "#f59e0b";
            case "SUM_AMOUNT", "SUM_DEBIT", "SUM_CREDIT", "NET_BALANCE" -> "#14b8a6";
            default -> "#60a5fa";
        };
    }

    private static String formatShortNumber(double value) {
        double abs = Math.abs(value);
        if (abs >= 1000d) {
            return String.format(Locale.ROOT, "%.1fk", value / 1000d);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String truncateLabel(String raw, int max) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.length() <= max) return value;
        if (max <= 3) return value.substring(0, max);
        return value.substring(0, max - 3) + "...";
    }

    private static String upper(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (!isBlank(value)) return value.trim();
        }
        return null;
    }

    private static String alertTitle(com.asecon.enterpriseiq.model.AlertType type) {
        if (type == null) return "Alerta";
        return switch (type) {
            case NET_FLOW_BELOW_THRESHOLD -> "Flujo neto por debajo del umbral";
            case ENDING_BALANCE_LOW -> "Saldo final bajo";
            case OUTFLOWS_SPIKE -> "Pico de pagos";
            case INFLOWS_DROP -> "Caída de cobros";
        };
    }

    private static String alertSeverity(com.asecon.enterpriseiq.model.AlertType type) {
        if (type == null) return "MEDIUM";
        return switch (type) {
            case ENDING_BALANCE_LOW -> "HIGH";
            case NET_FLOW_BELOW_THRESHOLD -> "MEDIUM";
            case OUTFLOWS_SPIKE -> "MEDIUM";
            case INFLOWS_DROP -> "LOW";
        };
    }

    private static String alertWhy(com.asecon.enterpriseiq.model.AlertType type) {
        if (type == null) return "Puede indicar tensión de caja.";
        return switch (type) {
            case NET_FLOW_BELOW_THRESHOLD -> "Si el neto mensual cae por debajo del umbral, el mes se financia con saldo acumulado.";
            case ENDING_BALANCE_LOW -> "Un saldo final bajo reduce margen de maniobra ante pagos imprevistos.";
            case OUTFLOWS_SPIKE -> "Un pico de pagos suele ser gasto puntual, acumulación o impuestos concentrados.";
            case INFLOWS_DROP -> "Una caída de cobros puede anticipar problemas de ventas o retrasos de clientes.";
        };
    }

    private static String alertAction(com.asecon.enterpriseiq.model.AlertType type) {
        if (type == null) return "Revisar y priorizar acciones de caja.";
        return switch (type) {
            case NET_FLOW_BELOW_THRESHOLD -> "Revisar cobros pendientes, renegociar vencimientos y frenar gasto no crítico.";
            case ENDING_BALANCE_LOW -> "Plan de tesorería: calendario de cobros/pagos, anticipos y colchón mínimo.";
            case OUTFLOWS_SPIKE -> "Identificar partidas responsables y decidir si es puntual o recurrente.";
            case INFLOWS_DROP -> "Revisar facturación, morosidad y activar plan de recobro.";
        };
    }
}


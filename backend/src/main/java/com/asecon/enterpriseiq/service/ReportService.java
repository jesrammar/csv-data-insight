package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.Alert;
import com.asecon.enterpriseiq.model.Report;
import com.asecon.enterpriseiq.model.ReportFormat;
import com.asecon.enterpriseiq.model.ReportStatus;
import com.asecon.enterpriseiq.dto.UniversalSummaryDto;
import com.asecon.enterpriseiq.repo.AlertRepository;
import com.asecon.enterpriseiq.repo.CompanySettingsRepository;
import com.asecon.enterpriseiq.repo.KpiMonthlyRepository;
import com.asecon.enterpriseiq.repo.ReportRepository;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.text.NumberFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReportService {
    private final ReportRepository reportRepository;
    private final Path reportsRoot;
    private final KpiMonthlyRepository kpiMonthlyRepository;
    private final AlertRepository alertRepository;
    private final UniversalCsvService universalCsvService;
    private final CompanySettingsRepository companySettingsRepository;

    public ReportService(ReportRepository reportRepository,
                         KpiMonthlyRepository kpiMonthlyRepository,
                         AlertRepository alertRepository,
                         UniversalCsvService universalCsvService,
                         CompanySettingsRepository companySettingsRepository,
                         @Value("${app.storage.reports}") String reportsRoot) {
        this.reportRepository = reportRepository;
        this.kpiMonthlyRepository = kpiMonthlyRepository;
        this.alertRepository = alertRepository;
        this.universalCsvService = universalCsvService;
        this.companySettingsRepository = companySettingsRepository;
        this.reportsRoot = Path.of(reportsRoot);
    }

    public byte[] renderPdfFromHtml(String htmlContent) {
        if (htmlContent == null) htmlContent = "";
        try (var baos = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(htmlContent, null);
            builder.toStream(baos);
            builder.run();
            return baos.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo generar PDF: " + ex.getMessage(), ex);
        }
    }

    public Report generateHtmlReport(Company company, String period, String htmlContent) throws IOException {
        Files.createDirectories(reportsRoot);
        if (company == null || company.getId() == null) {
            throw new IllegalArgumentException("Company is required");
        }
        String resolvedPeriod = period == null ? "" : period.trim();
        if (resolvedPeriod.isBlank()) {
            resolvedPeriod = YearMonth.now().minusMonths(1).toString();
        }

        Report report = reportRepository.findByCompanyIdAndPeriod(company.getId(), resolvedPeriod).orElse(null);
        if (report == null) {
            report = new Report();
            report.setCompany(company);
            report.setPeriod(resolvedPeriod);
            report.setFormat(ReportFormat.HTML);
            report.setStatus(ReportStatus.READY);
            report.setCreatedAt(Instant.now());
            report = reportRepository.save(report);
        } else {
            report.setStatus(ReportStatus.READY);
            // "createdAt" is used as "last generated at" in UI ordering.
            report.setCreatedAt(Instant.now());
        }

        Path reportPath = reportsRoot.resolve("report-" + report.getId() + ".html");
        Files.writeString(reportPath, htmlContent == null ? "" : htmlContent);
        report.setStorageRef(reportPath.toString());
        return reportRepository.save(report);
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
        return Files.readString(p);
    }

    public String buildHtmlTemplate(Company company, String period, String summary) {
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
        String storyHtml = buildStoryHtml(company, period, kpi, series, alerts, uni);
        String genAt = fmtGeneratedAt(ZonedDateTime.now(ZoneId.systemDefault()));

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
        int w = 520;
        int h = 176;
        int pad = 14;
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

        double top = pad + 16;
        double bottom = h - pad - 18;

        StringBuilder points = new StringBuilder();
        StringBuilder area = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            double x = pad + (double) i * (double) (w - pad * 2) / (double) Math.max(1, v.length - 1);
            double y = top + (max - v[i]) * (bottom - top) / (max - min);
            if (i > 0) points.append(' ');
            points.append(String.format(Locale.ROOT, "%.1f,%.1f", x, y));
            if (i == 0) {
                area.append(String.format(Locale.ROOT, "M %.1f %.1f ", x, y));
            } else {
                area.append(String.format(Locale.ROOT, "L %.1f %.1f ", x, y));
            }
        }
        area.append(String.format(Locale.ROOT, "L %.1f %.1f L %.1f %.1f Z", (double) (w - pad), bottom, (double) pad, bottom));

        double yMin = bottom;
        double yMax = top;

        // Ticks: show first/middle/last when possible
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
            <polyline fill='none' stroke='%s' stroke-width='3.2' points='%s'/>
            <circle cx='%d' cy='%.1f' r='3.6' fill='%s'/>
            <circle cx='%d' cy='%.1f' r='3.6' fill='%s'/>
            <text x='%d' y='%d' font-size='10' fill='rgba(248,250,252,0.60)'>%s</text>
            <text x='%d' y='%d' font-size='10' fill='rgba(248,250,252,0.60)' text-anchor='middle'>%s</text>
            <text x='%d' y='%d' font-size='10' fill='rgba(248,250,252,0.60)' text-anchor='end'>%s</text>
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
        int w = 520;
        int h = 196;
        int pad = 16;
        double max = 0;
        double[] a = new double[n];
        double[] b = new double[n];
        for (int i = 0; i < n; i++) {
            a[i] = seriesA.get(i) == null ? 0.0 : Math.abs(seriesA.get(i).doubleValue());
            b[i] = seriesB.get(i) == null ? 0.0 : Math.abs(seriesB.get(i).doubleValue());
            max = Math.max(max, Math.max(a[i], b[i]));
        }
        if (max < 1e-9) max = 1.0;
        double top = pad + 18;
        double bottom = h - pad - 18;
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
            <text x='%d' y='%d' font-size='10' fill='rgba(248,250,252,0.60)'>%s</text>
            <text x='%d' y='%d' font-size='10' fill='rgba(248,250,252,0.60)' text-anchor='middle'>%s</text>
            <text x='%d' y='%d' font-size='10' fill='rgba(248,250,252,0.60)' text-anchor='end'>%s</text>
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
        int size = 220;
        int cx = 110;
        int cy = 108;
        int r = 72;
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
            <circle cx='%d' cy='%d' r='%d' fill='none' stroke='#20324f' stroke-width='16'/>
            <g transform='rotate(-90 %d %d)'>
              <circle cx='%d' cy='%d' r='%d' fill='none' stroke='%s' stroke-width='16'
                stroke-linecap='round' stroke-dasharray='%.1f %.1f' stroke-dashoffset='0'/>
              <circle cx='%d' cy='%d' r='%d' fill='none' stroke='%s' stroke-width='16'
                stroke-linecap='round' stroke-dasharray='%.1f %.1f' stroke-dashoffset='%.1f'/>
            </g>
            <text x='%d' y='%d' font-size='12' fill='rgba(248,250,252,0.78)' text-anchor='middle'>%s</text>
            <text x='%d' y='%d' font-size='22' font-weight='800' fill='#f8fafc' text-anchor='middle'>%d%%</text>
            <text x='%d' y='%d' font-size='11' fill='rgba(248,250,252,0.70)' text-anchor='middle'>%s vs %s</text>
            <g>
              <circle cx='18' cy='194' r='4' fill='%s'/><text x='28' y='198' font-size='11' fill='rgba(248,250,252,0.78)'>%s</text>
              <circle cx='118' cy='194' r='4' fill='%s'/><text x='128' y='198' font-size='11' fill='rgba(248,250,252,0.78)'>%s</text>
            </g>
          </svg>
        """.formatted(
            size, size, size, size,
            size, size,
            cx, cy, r,
            cx, cy,
            cx, cy, r, ca, dashA, circ,
            cx, cy, r, cb, dashB, circ, dashA,
            cx, 86, "Proporción",
            cx, 116, (int) Math.round(pA * 100.0),
            cx, 140, escape(labelA == null ? "A" : labelA), escape(labelB == null ? "B" : labelB),
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

        int w = 220;
        int h = 220;
        int cx = 110;
        int cy = 130;
        int r = 78;
        double start = Math.PI; // 180deg
        double end = 2 * Math.PI; // 360deg
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
            <path d='%s' fill='none' stroke='#20324f' stroke-width='16' stroke-linecap='round'/>
            <path d='%s' fill='none' stroke='%s' stroke-width='16' stroke-linecap='round'/>
            <circle cx='%d' cy='%d' r='3.4' fill='%s'/>
            <text x='%d' y='%d' font-size='12' fill='rgba(248,250,252,0.78)' text-anchor='middle'>%s</text>
            <text x='%d' y='%d' font-size='26' font-weight='900' fill='#f8fafc' text-anchor='middle'>%d%%</text>
            <text x='%d' y='%d' font-size='11' fill='rgba(248,250,252,0.70)' text-anchor='middle'>saldo / pagos</text>
          </svg>
        """.formatted(
            w, h, w, h,
            w, h,
            dArc,
            dVal,
            c,
            (int) Math.round(x), (int) Math.round(y), c,
            cx, 66, escape(title == null ? "" : title),
            cx, 118, (int) Math.round(p),
            cx, 142
        );
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
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

    private static String buildStoryHtml(Company company,
                                        String period,
                                        com.asecon.enterpriseiq.model.KpiMonthly kpi,
                                        List<com.asecon.enterpriseiq.model.KpiMonthly> series,
                                        List<Alert> alerts,
                                        UniversalSummaryDto uni) {
        if (kpi == null) {
            return """
              <div class='section-title'>Qué falta para cerrar el informe</div>
              <div class='card'>
                <div class='small'><strong>Sube un CSV/XLSX de transacciones</strong> para este periodo (o usa el modo AUTO).</div>
                <div class='muted' style='margin-top:10px;'>Con transacciones, EnterpriseIQ calcula KPIs, tendencia y alertas, y el PDF añade recomendaciones accionables.</div>
              </div>
            """;
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
            insights.add(new ActionableInsight(
                "Universal: dataset disponible para análisis adicional",
                "LOW",
                "Hay un dataset cargado en Universal (" + uni.filename() + "). Útil para presupuestos, ventas o inventario.",
                "Si quieres convertirlo en dashboard, crea una vista (fecha+valor) y guarda una versión para comparar meses."
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

        String miniRows = """
          <tr><th>Periodo</th><td>%s</td></tr>
          <tr><th>Cobros</th><td>%s</td></tr>
          <tr><th>Pagos</th><td>%s</td></tr>
          <tr><th>Neto</th><td>%s <span class='muted'>(vs mes anterior: %s)</span></td></tr>
          <tr><th>Saldo final</th><td>%s</td></tr>
          <tr><th>Cobertura pagos</th><td>%s</td></tr>
          <tr><th>Alertas</th><td>%s</td></tr>
        """.formatted(
            escape(period == null ? "—" : period),
            escape(fmtMoney(kpi.getInflows())),
            escape(fmtMoney(kpi.getOutflows())),
            escape(fmtMoney(kpi.getNetFlow())),
            escape(netDelta),
            escape(fmtMoney(kpi.getEndingBalance())),
            escape(coverageLabel),
            escape(String.valueOf(nAlerts))
        );

        return """
          <div class='section-title'>Mini resumen</div>
          <div class='mini'>
            <table>%s</table>
          </div>

          <div class='section-title'>Insights accionables (para contar la historia)</div>
          <div class='story-grid'>%s</div>

          <div class='section-title'>Recomendaciones por impacto</div>
          <div class='card reco'>
            <table>
              <thead><tr><th>Impacto</th><th>Acción recomendada</th></tr></thead>
              <tbody>%s</tbody>
            </table>
            <div class='muted' style='margin-top:10px;'>Nota: estas recomendaciones son automáticas. Ajusta con contexto (estacionalidad, impuestos, cobros puntuales).</div>
          </div>
        """.formatted(miniRows, insightsHtml, recoRows);
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

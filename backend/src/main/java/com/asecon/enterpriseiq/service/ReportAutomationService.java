package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.repo.AlertRepository;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.asecon.enterpriseiq.repo.KpiMonthlyRepository;
import com.asecon.enterpriseiq.repo.ReportRepository;
import com.asecon.enterpriseiq.repo.AdvisorRecommendationRepository;
import java.io.IOException;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportAutomationService {
    private final CompanyRepository companyRepository;
    private final ReportRepository reportRepository;
    private final ReportService reportService;
    private final AdvisorAssistantService advisorAssistantService;
    private final KpiMonthlyRepository kpiMonthlyRepository;
    private final AlertRepository alertRepository;
    private final AdvisorRecommendationRepository recommendationRepository;

    public ReportAutomationService(CompanyRepository companyRepository,
                                   ReportRepository reportRepository,
                                   ReportService reportService,
                                   AdvisorAssistantService advisorAssistantService,
                                   KpiMonthlyRepository kpiMonthlyRepository,
                                   AlertRepository alertRepository,
                                   AdvisorRecommendationRepository recommendationRepository) {
        this.companyRepository = companyRepository;
        this.reportRepository = reportRepository;
        this.reportService = reportService;
        this.advisorAssistantService = advisorAssistantService;
        this.kpiMonthlyRepository = kpiMonthlyRepository;
        this.alertRepository = alertRepository;
        this.recommendationRepository = recommendationRepository;
    }

    @Transactional
    public void generateMonthly(Long companyId, String period) throws IOException {
        String resolvedPeriod = normalizePeriod(period);
        var company = companyRepository.findById(companyId).orElseThrow();
        String html;
        if (company.getPlan() != null && company.getPlan().isAtLeast(Plan.PLATINUM)) {
            html = advisorAssistantService.buildConsultingReportHtml(companyId, company.getName());
        } else {
            String summary = "Informe mensual generado automáticamente con KPIs, tendencia y alertas del periodo.";
            html = reportService.buildHtmlTemplate(company, resolvedPeriod, summary);
        }
        reportService.generateHtmlReport(company, resolvedPeriod, html);
    }

    private String buildBasicMonthly(Long companyId, String companyName, String period) {
        var kpiOpt = kpiMonthlyRepository.findByCompanyIdAndPeriod(companyId, period);
        var alerts = alertRepository.findByCompanyIdAndPeriod(companyId, period);
        var recOpt = recommendationRepository.findByCompany_IdAndPeriodAndSource(companyId, period, "RULES");

        String kpiBlock = kpiOpt.map(k -> """
            <div class="kpi-grid">
              <div class="kpi"><div class="kpi-title">Inflows</div><div class="kpi-value">%s</div></div>
              <div class="kpi"><div class="kpi-title">Outflows</div><div class="kpi-value">%s</div></div>
              <div class="kpi"><div class="kpi-title">Net Flow</div><div class="kpi-value">%s</div></div>
              <div class="kpi"><div class="kpi-title">Ending Balance</div><div class="kpi-value">%s</div></div>
            </div>
        """.formatted(k.getInflows(), k.getOutflows(), k.getNetFlow(), k.getEndingBalance())).orElse("<div class='muted'>Sin KPIs para este periodo.</div>");

        String alertBlock = alerts == null || alerts.isEmpty()
            ? "<div class='muted'>Sin alertas.</div>"
            : "<ul>" + alerts.stream().limit(10).map(a -> "<li>" + esc(a.getType().name()) + ": " + esc(a.getMessage()) + "</li>").reduce("", String::concat) + "</ul>";

        String actionsBlock = recOpt.map(r -> "<pre>" + esc(r.getSummary()) + "</pre>").orElse("<div class='muted'>Sin recomendaciones generadas.</div>");

        return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset='utf-8'/>
          <title>Informe mensual - EnterpriseIQ</title>
          <style>
            body { font-family: Arial, sans-serif; margin: 28px; color: #0f172a; background: #ffffff; }
            h1 { margin: 0 0 6px; }
            h2 { margin: 0 0 10px; font-size: 18px; }
            .muted { color: #475569; font-size: 12px; }
            .grid { display: grid; grid-template-columns: 1fr; gap: 12px; margin-top: 14px; }
            .card { border: 1px solid #e2e8f0; border-radius: 12px; padding: 14px; }
            .kpi-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
            .kpi { border: 1px solid #e2e8f0; border-radius: 10px; padding: 10px; }
            .kpi-title { font-size: 12px; color: #475569; }
            .kpi-value { font-size: 18px; font-weight: 700; margin-top: 6px; }
            pre { white-space: pre-wrap; font-family: inherit; }
          </style>
        </head>
        <body>
          <div>
            <h1>Informe mensual</h1>
            <div class="muted">Empresa: %s · Periodo: %s</div>
          </div>

          <div class="grid">
            <div class="card">
              <h2>Caja (KPIs)</h2>
              %s
            </div>

            <div class="card">
              <h2>Alertas</h2>
              %s
            </div>

            <div class="card">
              <h2>Recomendaciones</h2>
              %s
            </div>
          </div>
        </body>
        </html>
        """.formatted(esc(companyName), esc(period), kpiBlock, alertBlock, actionsBlock);
    }

    private static String normalizePeriod(String period) {
        if (period == null || period.isBlank()) return YearMonth.now().minusMonths(1).toString();
        String p = period.trim();
        if (p.matches("\\d{4}-\\d{2}")) return p;
        try {
            var ym = YearMonth.parse(p, DateTimeFormatter.ofPattern("yyyy-MM"));
            return ym.toString();
        } catch (Exception ignored) {
            return YearMonth.now().minusMonths(1).toString();
        }
    }

    private static String esc(String raw) {
        if (raw == null) return "";
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}


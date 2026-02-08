package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.Report;
import com.asecon.enterpriseiq.model.ReportFormat;
import com.asecon.enterpriseiq.model.ReportStatus;
import com.asecon.enterpriseiq.repo.ReportRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ReportService {
    private final ReportRepository reportRepository;
    private final Path reportsRoot;

    public ReportService(ReportRepository reportRepository, @Value("${app.storage.reports}") String reportsRoot) {
        this.reportRepository = reportRepository;
        this.reportsRoot = Path.of(reportsRoot);
    }

    public Report generateHtmlReport(Company company, String period, String htmlContent) throws IOException {
        Files.createDirectories(reportsRoot);
        Report report = new Report();
        report.setCompany(company);
        report.setPeriod(period);
        report.setFormat(ReportFormat.HTML);
        report.setStatus(ReportStatus.READY);
        report.setCreatedAt(Instant.now());
        report = reportRepository.save(report);

        Path reportPath = reportsRoot.resolve("report-" + report.getId() + ".html");
        Files.writeString(reportPath, htmlContent);
        report.setStorageRef(reportPath.toString());
        return reportRepository.save(report);
    }

    public String loadReportContent(Report report) throws IOException {
        if (report.getStorageRef() == null) return "";
        return Files.readString(Path.of(report.getStorageRef()));
    }

    public String buildHtmlTemplate(Company company, String period, String summary) {
        return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset='utf-8'/>
          <title>EnterpriseIQ Report</title>
          <style>
            body { font-family: Arial, sans-serif; margin: 32px; }
            h1 { color: #1d3557; }
            .card { border: 1px solid #ddd; padding: 16px; border-radius: 8px; }
          </style>
        </head>
        <body>
          <h1>Informe mensual - EnterpriseIQ</h1>
          <div class='card'>
            <p><strong>Empresa:</strong> %s</p>
            <p><strong>Periodo:</strong> %s</p>
            <p><strong>Resumen:</strong> %s</p>
          </div>
          <p style='margin-top:24px;'>
            TODO: Generar PDF desde HTML con OpenHTMLtoPDF.
          </p>
        </body>
        </html>
        """.formatted(company.getName(), period, summary);
    }
}

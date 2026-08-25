package com.asecon.enterpriseiq.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.asecon.enterpriseiq.dto.WorkforceGestorDto;
import com.asecon.enterpriseiq.dto.WorkforceImportDto;
import com.asecon.enterpriseiq.dto.WorkforceKpiDto;
import com.asecon.enterpriseiq.dto.WorkforceLaborCostsDto;
import com.asecon.enterpriseiq.dto.WorkforceMonthlyCostDto;
import com.asecon.enterpriseiq.dto.WorkforceStatusBreakdownDto;
import com.asecon.enterpriseiq.dto.WorkforceSummaryDto;
import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.model.WorkforceImport;
import com.asecon.enterpriseiq.model.WorkforceImportKind;
import com.asecon.enterpriseiq.repo.AlertRepository;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.asecon.enterpriseiq.repo.CompanySettingsRepository;
import com.asecon.enterpriseiq.repo.KpiMonthlyRepository;
import com.asecon.enterpriseiq.repo.ReportRepository;
import com.asecon.enterpriseiq.repo.UniversalViewRepository;
import com.asecon.enterpriseiq.repo.WorkforceImportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class WorkforceReportServiceTest {
    @Test
    void builds_html_and_renders_real_pdf_from_workforce_sample() throws Exception {
        Company company = new Company();
        company.setName("Consultoria Demo");
        company.setPlan(Plan.GOLD);

        CompanyRepository companyRepository = mock(CompanyRepository.class);
        WorkforceImportRepository importRepository = mock(WorkforceImportRepository.class);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

        ObjectMapper objectMapper = new ObjectMapper();
        TabularFileService tabularFileService = new TabularFileService(120000, 25, new SimpleMeterRegistry());
        WorkforceImportService workforceImportService = new WorkforceImportService(companyRepository, importRepository, tabularFileService, objectMapper);

        List<WorkforceImport> savedImports = new ArrayList<>();
        when(importRepository.save(any(WorkforceImport.class))).thenAnswer(invocation -> {
            WorkforceImport saved = invocation.getArgument(0);
            savedImports.add(saved);
            return saved;
        });

        byte[] workforceBytes = Files.readAllBytes(Path.of("..", "samples", "Workforce_Ficticio_Consultoria_2026.xlsx"));
        MockMultipartFile workforceFile = new MockMultipartFile(
            "file",
            "Workforce_Ficticio_Consultoria_2026.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            workforceBytes
        );
        workforceImportService.importFile(7L, workforceFile);
        WorkforceImport savedWorkforce = savedImports.get(0);

        String laborCostsCsv = String.join("\n",
            "GESTOR,MES,COSTE PERSONAL,SS EMPRESA",
            "ALBA,ENERO,1200,360",
            "ALBA,FEBRERO,1200,360",
            "BRUNO,ENERO,1400,420",
            "CLARA,ENERO,1300,390",
            "DIEGO,ENERO,1500,450",
            "ELENA,ENERO,1600,480",
            "FERNANDO,ENERO,1700,510"
        );
        MockMultipartFile laborCostsFile = new MockMultipartFile(
            "file",
            "labor-costs.csv",
            "text/csv",
            laborCostsCsv.getBytes(StandardCharsets.UTF_8)
        );

        when(importRepository.findFirstByCompanyIdAndImportKindOrderByCreatedAtDesc(7L, WorkforceImportKind.WORKFORCE))
            .thenReturn(Optional.of(savedWorkforce));

        workforceImportService.importLaborCosts(7L, laborCostsFile);
        WorkforceImport savedLaborCosts = savedImports.get(1);

        when(importRepository.findFirstByCompanyIdAndImportKindOrderByCreatedAtDesc(7L, WorkforceImportKind.LABOR_COSTS))
            .thenReturn(Optional.of(savedLaborCosts));

        WorkforceSummaryDto summary = workforceImportService.getSummary(7L);
        WorkforceImportDto workforceStatus = workforceImportService.getLatestImport(7L);
        WorkforceImportDto laborCostsStatus = workforceImportService.getLatestLaborCostsImport(7L);

        Path artifactDir = Path.of("target", "test-artifacts");
        Files.createDirectories(artifactDir);

        ReportService reportService = new ReportService(
            mock(ReportRepository.class),
            mock(KpiMonthlyRepository.class),
            mock(AlertRepository.class),
            mock(UniversalCsvService.class),
            mock(UniversalViewRepository.class),
            mock(UniversalViewService.class),
            mock(CompanySettingsRepository.class),
            mock(PeriodWorkflowService.class),
            artifactDir.toString()
        );
        WorkforceReportService workforceReportService = new WorkforceReportService(reportService);

        String html = workforceReportService.buildWorkforceReportHtml(company, summary, workforceStatus, laborCostsStatus);
        assertThat(html).contains("Resumen ejecutivo");
        assertThat(html).contains("Distribucion operativa");
        assertThat(html).contains("Costes laborales");
        assertThat(html).contains("Coste vs actividad");
        assertThat(html).contains("Workforce_Ficticio_Consultoria_2026");
        assertThat(html).contains("Coste laboral total");

        byte[] pdf = workforceReportService.renderWorkforcePdf(company, summary, workforceStatus, laborCostsStatus);
        Path pdfPath = artifactDir.resolve("workforce-report-preview.pdf");
        Files.write(pdfPath, pdf);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(Files.exists(pdfPath)).isTrue();
        assertThat(summary.gestores()).extracting("gestor")
            .contains("ALBA", "BRUNO", "CLARA", "DIEGO", "ELENA", "FERNANDO");
    }

    @Test
    void avoidsFalseLeadershipNarrativeWhenClientCountsAreTied() {
        Company company = new Company();
        company.setName("Consultoria Demo");
        company.setPlan(Plan.GOLD);

        WorkforceSummaryDto summary = new WorkforceSummaryDto(
            new WorkforceKpiDto(10, 2, 10, 0, 300.0, 10.0, 500.0),
            List.of(
                workforceGestor("ALBA", 5, 5, 0, 150.0, 5.0, 250.0, 70.0),
                workforceGestor("BRUNO", 5, 5, 0, 150.0, 5.0, 250.0, 70.0)
            ),
            List.of("GESTOR", "MINUTAS", "PROMEDIO"),
            List.of(2024),
            new WorkforceLaborCostsDto(
                1_000.0,
                300.0,
                1_300.0,
                650.0,
                List.of(new WorkforceMonthlyCostDto("ENERO", 1_000.0, 300.0, 1_300.0)),
                List.of(),
                0,
                List.of()
            ),
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

        ReportService reportService = new ReportService(
            mock(ReportRepository.class),
            mock(KpiMonthlyRepository.class),
            mock(AlertRepository.class),
            mock(UniversalCsvService.class),
            mock(UniversalViewRepository.class),
            mock(UniversalViewService.class),
            mock(CompanySettingsRepository.class),
            mock(PeriodWorkflowService.class),
            Path.of("target", "test-artifacts").toString()
        );
        WorkforceReportService workforceReportService = new WorkforceReportService(reportService);

        String html = workforceReportService.buildWorkforceReportHtml(company, summary, null, null);

        assertThat(html).contains("La cartera total aparece repartida sin un gestor claramente dominante.");
        assertThat(html).doesNotContain("registra la mayor cartera total");
        assertThat(html).contains("Coste/cliente total");
    }

    private static WorkforceGestorDto workforceGestor(String gestor,
                                                      long totalClients,
                                                      long activeClients,
                                                      long inactiveClients,
                                                      Double minutas,
                                                      Double carga,
                                                      Double asientos,
                                                      Double pctContabilidad) {
        return new WorkforceGestorDto(
            gestor,
            totalClients,
            activeClients,
            inactiveClients,
            minutas,
            carga,
            carga,
            asientos,
            pctContabilidad,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            1,
            1,
            1,
            1,
            new WorkforceStatusBreakdownDto(1, 0, 0, 0, 0),
            new WorkforceStatusBreakdownDto(1, 0, 0, 0, 0),
            new WorkforceStatusBreakdownDto(1, 0, 0, 0, 0),
            new WorkforceStatusBreakdownDto(1, 0, 0, 0, 0),
            Map.of(2024, 250L)
        );
    }
}

package com.asecon.enterpriseiq.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asecon.enterpriseiq.dto.WorkforceImportDto;
import com.asecon.enterpriseiq.dto.WorkforceLaborCostImportSummaryDto;
import com.asecon.enterpriseiq.dto.WorkforceSummaryDto;
import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.model.WorkforceImport;
import com.asecon.enterpriseiq.model.WorkforceImportKind;
import com.asecon.enterpriseiq.model.WorkforceImportStatus;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.asecon.enterpriseiq.repo.WorkforceImportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class WorkforceImportServiceTest {
    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private WorkforceImportRepository importRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TabularFileService tabularFileService = new TabularFileService(120000, 25, new SimpleMeterRegistry());

    private WorkforceImportService workforceImportService;

    @BeforeEach
    void setUp() {
        workforceImportService = new WorkforceImportService(companyRepository, importRepository, tabularFileService, objectMapper);
    }

    @Test
    void importsFictitiousXlsxWorkbookAndAggregatesByGestor() throws IOException {
        Company company = new Company();
        company.setName("Empresa test");
        company.setPlan(Plan.GOLD);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));
        when(importRepository.save(any(WorkforceImport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        byte[] bytes;
        try (InputStream input = WorkforceImportServiceTest.class.getResourceAsStream(
            "/fixtures/workforce/consulting-workforce-2026.xlsx"
        )) {
            assertNotNull(input);
            bytes = input.readAllBytes();
        }
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "consulting-workforce-2026.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            bytes
        );

        WorkforceImportDto dto = workforceImportService.importFile(7L, file);

        assertTrue(dto.rowCount() > 0);
        assertTrue(dto.detectedColumns().contains("GESTOR"));
        assertTrue(dto.detectedColumns().contains("PROMEDIO"));
        assertTrue(!dto.activityYears().isEmpty());

        ArgumentCaptor<WorkforceImport> captor = ArgumentCaptor.forClass(WorkforceImport.class);
        verify(importRepository).save(captor.capture());
        WorkforceImport saved = captor.getValue();
        WorkforceSummaryDto summary = objectMapper.readValue(saved.getSummaryJson(), WorkforceSummaryDto.class);

        assertTrue(summary.kpis().totalClients() > 0);
        assertEquals(6, summary.kpis().totalGestores());
        assertEquals(6, summary.gestores().size());
        assertEquals(List.of("ALBA", "BRUNO", "CLARA", "DIEGO", "ELENA", "FERNANDO"),
            summary.gestores().stream().map(row -> row.gestor()).sorted().toList());
        assertTrue(summary.kpis().activeClients() >= 0);
        assertTrue(summary.kpis().inactiveClients() >= 0);
        assertTrue(summary.kpis().totalMinutas() != null && summary.kpis().totalMinutas() > 0);
        assertTrue(summary.kpis().totalVolumenAsientos() != null && summary.kpis().totalVolumenAsientos() > 0);

        when(importRepository.findFirstByCompanyIdAndImportKindOrderByCreatedAtDesc(7L, WorkforceImportKind.WORKFORCE)).thenReturn(Optional.of(saved));
        WorkforceSummaryDto latest = workforceImportService.getSummary(7L);
        assertNotNull(latest);
        assertEquals(summary.kpis().totalVolumenAsientos(), latest.kpis().totalVolumenAsientos(), 0.01);
    }

    @Test
    void infersWorkforceReferencePeriodFromArbitraryFilenameWhenSelectorIsEmpty() throws IOException {
        Company company = new Company();
        company.setName("Empresa test");
        company.setPlan(Plan.GOLD);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

        List<WorkforceImport> savedImports = new ArrayList<>();
        wireRepository(company, savedImports);

        WorkforceImportDto workforce = workforceImportService.importFile(
            7L,
            workforceCsvFile(
                "Clientes_Marzo_2025.csv",
                workforceRow("ULISES NORTE", 840.25, 6.5, 71.5, 320, 320)
            )
        );
        WorkforceImport storedWorkforce = savedImports.stream()
            .filter(imported -> imported.getImportKind() == WorkforceImportKind.WORKFORCE)
            .findFirst()
            .orElseThrow();
        storedWorkforce.setReferencePeriod("UNKNOWN");
        storedWorkforce.setReferenceYear(null);
        storedWorkforce.setReferenceMonth(null);
        storedWorkforce.setCoverageStartMonth(null);
        storedWorkforce.setCoverageEndMonth(null);
        storedWorkforce.setCoverageCompleteYear(false);
        workforceImportService.importLaborCosts(
            7L,
            laborCostsCsvFile(
                "costes-marzo-2025.csv",
                laborCostRow("ULISES NORTE", "MARZO", 5000, 1500)
            ),
            "2025-03"
        );

        WorkforceSummaryDto latest = workforceImportService.getSummary(7L);
        assertEquals("2025-03", workforce.referencePeriod());
        assertEquals("2025-03", latest.workforceImport().referencePeriod());
        assertEquals("marzo 2025", latest.workforceImport().referenceLabel());
        assertNotNull(latest.pairedLaborCostsImport());
        assertEquals("2025-03", latest.pairedLaborCostsImport().referencePeriod());
        assertEquals(840.25, latest.kpis().totalMinutas(), 0.01);

        var ulises = latest.gestores().stream().filter(row -> "ULISES NORTE".equals(row.gestor())).findFirst().orElseThrow();
        assertEquals(840.25, ulises.totalMinutas(), 0.01);
        assertEquals(6500.0, ulises.costeLaboralAnual(), 0.01);
    }

    @Test
    void keepsExplicitWorkforceReferencePeriodOverFilenameInference() throws IOException {
        Company company = new Company();
        company.setName("Empresa test");
        company.setPlan(Plan.GOLD);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

        List<WorkforceImport> savedImports = new ArrayList<>();
        wireRepository(company, savedImports);

        WorkforceImportDto workforce = workforceImportService.importFile(
            7L,
            workforceCsvFile(
                "cartera-julio-2027.csv",
                workforceRow("SECTOR OMEGA", 120.0, 5.0, 80.0, 210, 210)
            ),
            "2024-11"
        );

        WorkforceSummaryDto latest = workforceImportService.getSummary(7L);
        assertEquals("2024-11", workforce.referencePeriod());
        assertEquals("2024-11", latest.workforceImport().referencePeriod());
        assertEquals("noviembre 2024", latest.workforceImport().referenceLabel());
    }

    @Test
    void doesNotInventWorkforcePeriodFromFilenameThatOnlyContainsYear() throws IOException {
        Company company = new Company();
        company.setName("Empresa test");
        company.setPlan(Plan.GOLD);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

        List<WorkforceImport> savedImports = new ArrayList<>();
        wireRepository(company, savedImports);

        WorkforceImportDto workforce = workforceImportService.importFile(
            7L,
            workforceCsvFile(
                "workforce_2028.csv",
                workforceRow("LUCIA SUR", 98.0, 4.0, 63.0, 180, 180)
            )
        );
        workforceImportService.importLaborCosts(
            7L,
            laborCostsCsvFile(
                "costes-febrero-2028.csv",
                laborCostRow("LUCIA SUR", "FEBRERO", 2200, 660)
            ),
            "2028-02"
        );

        WorkforceSummaryDto latest = workforceImportService.getSummary(7L);
        assertEquals("UNKNOWN", workforce.referencePeriod());
        assertEquals("UNKNOWN", latest.workforceImport().referencePeriod());
        assertEquals("Periodo desconocido", latest.workforceImport().referenceLabel());
        assertNull(latest.laborCostsImport());
        assertNull(latest.pairedLaborCostsImport());
    }

    @Test
    void importsLaborCostsAndCombinesWithWorkforceMetrics() throws IOException {
        Company company = new Company();
        company.setName("Empresa test");
        company.setPlan(Plan.GOLD);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

        List<WorkforceImport> savedImports = new ArrayList<>();
        wireRepository(company, savedImports);

        String workforceCsv = String.join("\n",
            "GESTOR,MINUTAS,F/BAJA,CONT/MODELOS,IS/IRPF,DDCC,LIBROS,CARGA DE TRABAJO,% CONTABILIDAD,N AS 2024,PROMEDIO",
            "ALBA,100,,SI,SI,SI,SI,4,60,200,200",
            "BRUNO,200,,SI,SI,SI,SI,8,80,400,400"
        );
        MockMultipartFile workforceFile = new MockMultipartFile(
            "file",
            "workforce.csv",
            "text/csv",
            workforceCsv.getBytes()
        );

        workforceImportService.importFile(7L, workforceFile, "2026-02");
        WorkforceImport savedWorkforce = savedImports.get(0);

        String laborCostsCsv = buildLaborCostsCsv(List.of(
            laborCostRow("ALBA", "ENERO", 1000, 300),
            laborCostRow("ALBA", "FEBRERO", 1000, 300),
            laborCostRow("ALBA", "MARZO", 1000, 300),
            laborCostRow("ALBA", "ABRIL", 1000, 300),
            laborCostRow("ALBA", "MAYO", 1000, 300),
            laborCostRow("ALBA", "JUNIO", 1000, 300),
            laborCostRow("ALBA", "JULIO", 1000, 300),
            laborCostRow("ALBA", "AGOSTO", 1000, 300),
            laborCostRow("ALBA", "SEPTIEMBRE", 1000, 300),
            laborCostRow("ALBA", "OCTUBRE", 1000, 300),
            laborCostRow("ALBA", "NOVIEMBRE", 1000, 300),
            laborCostRow("ALBA", "DICIEMBRE", 1000, 300),
            laborCostRow("BRUNO", "ENERO", 2000, 600),
            laborCostRow("BRUNO", "FEBRERO", 2000, 600),
            laborCostRow("BRUNO", "MARZO", 2000, 600),
            laborCostRow("BRUNO", "ABRIL", 2000, 600),
            laborCostRow("BRUNO", "MAYO", 2000, 600),
            laborCostRow("BRUNO", "JUNIO", 2000, 600),
            laborCostRow("BRUNO", "JULIO", 2000, 600),
            laborCostRow("BRUNO", "AGOSTO", 2000, 600),
            laborCostRow("BRUNO", "SEPTIEMBRE", 2000, 600),
            laborCostRow("BRUNO", "OCTUBRE", 2000, 600),
            laborCostRow("BRUNO", "NOVIEMBRE", 2000, 600),
            laborCostRow("BRUNO", "DICIEMBRE", 2000, 600)
        ));
        MockMultipartFile laborCostsFile = new MockMultipartFile(
            "file",
            "labor-costs.csv",
            "text/csv",
            laborCostsCsv.getBytes()
        );

        WorkforceImportDto dto = workforceImportService.importLaborCosts(7L, laborCostsFile, 2026);
        assertEquals(24, dto.rowCount());

        WorkforceImport savedLaborCosts = savedImports.get(1);
        WorkforceLaborCostImportSummaryDto savedSummary = objectMapper.readValue(
            savedLaborCosts.getSummaryJson(),
            WorkforceLaborCostImportSummaryDto.class
        );

        assertEquals(2, savedSummary.summary().gestores().size());
        assertEquals(46800.0, savedSummary.summary().totalCosteLaboralAnual(), 0.01);
        assertEquals(36000.0, savedSummary.summary().totalCostePersonalAnual(), 0.01);
        assertEquals(10800.0, savedSummary.summary().totalSsEmpresaAnual(), 0.01);

        var albaCosts = savedSummary.summary().gestores().stream()
            .filter(row -> "ALBA".equals(row.gestor()))
            .findFirst()
            .orElseThrow();
        assertEquals(12000.0, albaCosts.costePersonalAnual(), 0.01);
        assertEquals(3600.0, albaCosts.ssEmpresaAnual(), 0.01);
        assertEquals(15600.0, albaCosts.costeLaboralAnual(), 0.01);
        assertEquals(1000.0, albaCosts.costePersonalMensual().get("ENERO"), 0.01);
        assertEquals(300.0, albaCosts.ssEmpresaMensual().get("FEBRERO"), 0.01);

        WorkforceSummaryDto combined = workforceImportService.getSummary(7L);
        assertEquals(savedWorkforce.getId(), combined.workforceImport().id());
        assertEquals(savedLaborCosts.getId(), combined.laborCostsImport().id());
        assertEquals(savedLaborCosts.getId(), combined.pairedLaborCostsImport().id());
        assertEquals("2026-02", combined.workforceImport().referencePeriod());
        assertEquals("2026-02", combined.laborCostsImport().referencePeriod());
        assertEquals("enero-febrero 2026", combined.laborCostsImport().referenceLabel());
        assertEquals(Boolean.FALSE, combined.laborCostsImport().annualCoverage());

        var alba = combined.gestores().stream().filter(row -> "ALBA".equals(row.gestor())).findFirst().orElseThrow();
        assertEquals(2000.0, alba.costePersonalAnual(), 0.01);
        assertEquals(600.0, alba.ssEmpresaAnual(), 0.01);
        assertEquals(2600.0, alba.costeLaboralAnual(), 0.01);
        assertEquals(2600.0, alba.costePorCliente(), 0.01);
        assertEquals(26000.0, alba.costePor1000Minutas(), 0.01);
        assertEquals(13000.0, alba.costePor1000Asientos(), 0.01);
        assertEquals(38.46, alba.minutasPor1000Coste(), 0.01);
        assertEquals(76.92, alba.asientosPor1000Coste(), 0.01);
        assertNotNull(combined.laborCosts());
        assertEquals(7800.0, combined.laborCosts().totalCosteLaboralAnual(), 0.01);
        assertEquals(2, combined.laborCosts().monthlyTotals().size());
        assertNotNull(combined.pairedLaborCosts());
        assertEquals(2, combined.pairedLaborCosts().monthlyTotals().size());
        assertEquals(List.of("ENERO", "FEBRERO"),
            combined.pairedLaborCosts().monthlyTotals().stream().map(row -> row.month()).toList());
    }

    @Test
    void doesNotAutoPairCostsWhenWorkforcePeriodIsUnknown() throws IOException {
        Company company = new Company();
        company.setName("Empresa test");
        company.setPlan(Plan.GOLD);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

        List<WorkforceImport> savedImports = new ArrayList<>();
        wireRepository(company, savedImports);

        MockMultipartFile workforceFile = new MockMultipartFile(
            "file",
            "workforce.csv",
            "text/csv",
            String.join("\n",
                "GESTOR,MINUTAS,F/BAJA,CONT/MODELOS,IS/IRPF,DDCC,LIBROS,CARGA DE TRABAJO,% CONTABILIDAD,N AS 2024,PROMEDIO",
                "ALBA,100,,SI,SI,SI,SI,4,60,200,200"
            ).getBytes(StandardCharsets.UTF_8)
        );
        workforceImportService.importFile(7L, workforceFile);

        MockMultipartFile laborCostsFile = new MockMultipartFile(
            "file",
            "labor-costs.csv",
            "text/csv",
            buildLaborCostsCsv(List.of(laborCostRow("ALBA", "ENERO", 1000, 300))).getBytes(StandardCharsets.UTF_8)
        );
        workforceImportService.importLaborCosts(7L, laborCostsFile, 2026);

        WorkforceSummaryDto combined = workforceImportService.getSummary(7L);
        assertNull(combined.laborCosts());
        assertNull(combined.laborCostsImport());
        assertNull(combined.pairedLaborCosts());
        assertNull(combined.pairedLaborCostsImport());

        var alba = combined.gestores().stream().filter(row -> "ALBA".equals(row.gestor())).findFirst().orElseThrow();
        assertNull(alba.costePersonalAnual());
        assertNull(alba.ssEmpresaAnual());
        assertNull(alba.costeLaboralAnual());
        assertNull(alba.costePorCliente());
        assertNull(alba.costePor1000Minutas());
        assertNull(alba.costePor1000Asientos());
    }

    @Test
    void returnsEconomicSummaryWhenOnlyLaborCostsExist() throws IOException {
        Company company = new Company();
        company.setName("Empresa test");
        company.setPlan(Plan.GOLD);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

        List<WorkforceImport> savedImports = new ArrayList<>();
        wireRepository(company, savedImports);

        workforceImportService.importLaborCosts(7L, laborCostsCsvFileWithHeader(
            "labor-may.csv",
            "GESTOR,TRABAJADOR,ID EMPLEADO,MES,COSTE PERSONAL,SS EMPRESA,MONEDA",
            "ALBA,ANA,EMP-1,MAYO,1000,300,EUR",
            "BRUNO,BEA,EMP-2,MAYO,900,270,EUR"
        ), "2026-05");

        WorkforceSummaryDto summary = workforceImportService.getSummary(7L);
        assertNull(summary.workforceImport());
        assertEquals("2026-05", summary.laborCostsImport().referencePeriod());
        assertNull(summary.pairedLaborCosts());
        assertNull(summary.pairedLaborCostsImport());
        assertNotNull(summary.laborCosts());
        assertEquals(2470.0, summary.laborCosts().totalCosteLaboralAnual(), 0.01);
        assertEquals(List.of("ALBA", "BRUNO"),
            summary.laborCosts().gestores().stream().map(row -> row.gestor()).sorted().toList());
        assertNotNull(summary.laborCostsHistory());
        assertEquals(1, summary.laborCostsHistory().imports().size());
        assertEquals("2026-05", summary.laborCostsHistory().imports().get(0).referencePeriod());
        assertEquals(0, summary.kpis().totalClients());
        assertTrue(summary.gestores().isEmpty());
    }

    @Test
    void preservesHistoricalVersionsAndPairsMatchingTemporalVersionOnly() throws IOException {
        Company company = new Company();
        company.setName("Empresa test");
        company.setPlan(Plan.GOLD);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

        List<WorkforceImport> savedImports = new ArrayList<>();
        wireRepository(company, savedImports);

        WorkforceImportDto workforceJan = workforceImportService.importFile(7L, workforceCsvFile(
            "workforce-jan.csv",
            workforceRow("ALBA", 100, 4, 60, 180, 180),
            workforceRow("BRUNO", 90, 3, 55, 150, 150),
            workforceRow("CLARA", 80, 2, 50, 120, 120)
        ), "2026-01");
        workforceImportService.importLaborCosts(7L, laborCostsCsvFile(
            "labor-jan.csv",
            laborCostRow("ALBA", "ENERO", 1000, 300),
            laborCostRow("BRUNO", "ENERO", 900, 270),
            laborCostRow("CLARA", "ENERO", 800, 240)
        ), 2026);

        WorkforceImportDto workforceFeb = workforceImportService.importFile(7L, workforceCsvFile(
            "workforce-feb.csv",
            workforceRow("ALBA", 110, 4, 60, 200, 200),
            workforceRow("CLARA", 95, 3, 58, 160, 160),
            workforceRow("DIEGO", 70, 2, 45, 130, 130)
        ), "2026-02");
        workforceImportService.importLaborCosts(7L, laborCostsCsvFile(
            "labor-feb.csv",
            laborCostRow("ALBA", "FEBRERO", 1100, 330),
            laborCostRow("CLARA", "FEBRERO", 950, 285),
            laborCostRow("DIEGO", "FEBRERO", 700, 210)
        ), 2026);

        WorkforceImportDto workforceMarV1 = workforceImportService.importFile(7L, workforceCsvFile(
            "workforce-mar-v1.csv",
            workforceRow("ALBA", 120, 5, 61, 210, 210),
            workforceRow("CLARA", 100, 3, 59, 170, 170),
            workforceRow("DIEGO", 72, 2, 46, 132, 132)
        ), "2026-03");
        WorkforceImportDto laborMarV1 = workforceImportService.importLaborCosts(7L, laborCostsCsvFile(
            "labor-mar-v1.csv",
            laborCostRow("ALBA", "MARZO", 1200, 360),
            laborCostRow("CLARA", "MARZO", 980, 294),
            laborCostRow("DIEGO", "MARZO", 720, 216)
        ), 2026);

        WorkforceImportDto workforceMarV2 = workforceImportService.importFile(7L, workforceCsvFile(
            "workforce-mar-v2.csv",
            workforceRow("ALBA", 126, 5, 62, 215, 215),
            workforceRow("CLARA", 104, 3, 60, 176, 176),
            workforceRow("DIEGO", 75, 2, 47, 135, 135)
        ), "2026-03");
        WorkforceImportDto laborMarV2 = workforceImportService.importLaborCosts(7L, laborCostsCsvFile(
            "labor-mar-v2.csv",
            laborCostRow("ALBA", "MARZO", 1260, 378),
            laborCostRow("CLARA", "MARZO", 1010, 303),
            laborCostRow("DIEGO", "MARZO", 760, 228)
        ), 2026);

        WorkforceSummaryDto january = workforceImportService.getSummary(7L, workforceJan.id());
        assertEquals("2026-01", january.workforceImport().referencePeriod());
        assertEquals("2026-01", january.laborCostsImport().referencePeriod());
        assertEquals("2026-01", january.pairedLaborCostsImport().referencePeriod());
        assertEquals(List.of("ALBA", "BRUNO", "CLARA"),
            january.gestores().stream().map(row -> row.gestor()).sorted().toList());
        assertEquals(List.of("ENERO"), january.pairedLaborCosts().monthlyTotals().stream().map(row -> row.month()).toList());

        WorkforceSummaryDto february = workforceImportService.getSummary(7L, workforceFeb.id());
        assertEquals("2026-02", february.workforceImport().referencePeriod());
        assertEquals("2026-02", february.laborCostsImport().referencePeriod());
        assertEquals("2026-02", february.pairedLaborCostsImport().referencePeriod());
        assertEquals(List.of("ALBA", "CLARA", "DIEGO"),
            february.gestores().stream().map(row -> row.gestor()).sorted().toList());
        assertEquals(List.of("FEBRERO"), february.pairedLaborCosts().monthlyTotals().stream().map(row -> row.month()).toList());
        assertTrue(february.gestores().stream().noneMatch(row -> "BRUNO".equals(row.gestor())));

        WorkforceSummaryDto marchV1 = workforceImportService.getSummary(7L, workforceMarV1.id());
        assertEquals(workforceMarV1.id(), marchV1.workforceImport().id());
        assertEquals(laborMarV1.id(), marchV1.laborCostsImport().id());
        assertEquals(laborMarV1.id(), marchV1.pairedLaborCostsImport().id());
        assertEquals("labor-mar-v1.csv", marchV1.laborCostsImport().filename());
        assertEquals("labor-mar-v1.csv", marchV1.pairedLaborCostsImport().filename());
        assertEquals(3770.0, marchV1.pairedLaborCosts().monthlyTotals().get(0).costeTotal(), 0.01);

        WorkforceSummaryDto latest = workforceImportService.getSummary(7L);
        assertEquals(workforceMarV2.id(), latest.workforceImport().id());
        assertEquals(laborMarV2.id(), latest.laborCostsImport().id());
        assertEquals(laborMarV2.id(), latest.pairedLaborCostsImport().id());
        assertEquals("labor-mar-v2.csv", latest.laborCostsImport().filename());
        assertEquals(3939.0, latest.pairedLaborCosts().monthlyTotals().get(0).costeTotal(), 0.01);

        assertEquals(4, latest.workforceImports().size());
        assertEquals(4, latest.laborCostImports().size());
        assertEquals(1L, latest.workforceImports().stream()
            .filter(row -> "2026-03".equals(row.referencePeriod()) && "ACTIVE".equals(row.status()))
            .count());
        assertEquals(1L, latest.workforceImports().stream()
            .filter(row -> "2026-03".equals(row.referencePeriod()) && "SUPERSEDED".equals(row.status()))
            .count());
        assertEquals(1L, latest.laborCostImports().stream()
            .filter(row -> "2026-03".equals(row.referencePeriod()) && "ACTIVE".equals(row.status()))
            .count());
        assertEquals(1L, latest.laborCostImports().stream()
            .filter(row -> "2026-03".equals(row.referencePeriod()) && "SUPERSEDED".equals(row.status()))
            .count());
    }

    @Test
    void keepsAprilAndMayLaborCostsSeparatedWhenPeriodComesFromSelection() throws IOException {
        Company company = new Company();
        company.setName("Empresa test");
        company.setPlan(Plan.GOLD);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

        List<WorkforceImport> savedImports = new ArrayList<>();
        wireRepository(company, savedImports);

        WorkforceImportDto workforceApril = workforceImportService.importFile(7L, workforceCsvFile(
            "workforce-apr.csv",
            workforceRow("ALBA", 120, 5, 60, 210, 210),
            workforceRow("BRUNO", 90, 4, 55, 160, 160)
        ), "2026-04");
        WorkforceImportDto workforceMay = workforceImportService.importFile(7L, workforceCsvFile(
            "workforce-may.csv",
            workforceRow("ALBA", 132, 6, 62, 225, 225),
            workforceRow("BRUNO", 98, 4, 56, 172, 172)
        ), "2026-05");

        WorkforceImportDto laborApril = workforceImportService.importLaborCosts(7L, laborCostsCsvFile(
            "labor-apr.csv",
            laborCostRow("ALBA", "", 1000, 300),
            laborCostRow("BRUNO", "", 900, 270)
        ), "2026-04");
        WorkforceImportDto laborMay = workforceImportService.importLaborCosts(7L, laborCostsCsvFile(
            "labor-may.csv",
            laborCostRow("ALBA", "", 1100, 330),
            laborCostRow("BRUNO", "", 950, 285)
        ), "2026-05");

        assertEquals("2026-04", laborApril.referencePeriod());
        assertEquals("2026-05", laborMay.referencePeriod());

        WorkforceSummaryDto aprilSummary = workforceImportService.getSummary(7L, workforceApril.id());
        assertEquals("2026-04", aprilSummary.workforceImport().referencePeriod());
        assertEquals("2026-04", aprilSummary.laborCostsImport().referencePeriod());
        assertEquals("2026-04", aprilSummary.pairedLaborCostsImport().referencePeriod());
        assertEquals(List.of("ABRIL"), aprilSummary.pairedLaborCosts().monthlyTotals().stream().map(row -> row.month()).toList());
        assertEquals(2470.0, aprilSummary.laborCosts().totalCosteLaboralAnual(), 0.01);
        assertEquals(2470.0, aprilSummary.pairedLaborCosts().totalCosteLaboralAnual(), 0.01);
        assertEquals(2470.0, aprilSummary.pairedLaborCosts().monthlyTotals().get(0).costeTotal(), 0.01);

        WorkforceSummaryDto maySummary = workforceImportService.getSummary(7L, workforceMay.id());
        assertEquals("2026-05", maySummary.workforceImport().referencePeriod());
        assertEquals("2026-05", maySummary.laborCostsImport().referencePeriod());
        assertEquals("2026-05", maySummary.pairedLaborCostsImport().referencePeriod());
        assertEquals(List.of("MAYO"), maySummary.pairedLaborCosts().monthlyTotals().stream().map(row -> row.month()).toList());
        assertEquals(2665.0, maySummary.laborCosts().totalCosteLaboralAnual(), 0.01);
        assertEquals(2665.0, maySummary.pairedLaborCosts().monthlyTotals().get(0).costeTotal(), 0.01);
    }

    @Test
    void ignoresUnknownAndUnmatchedLaborCostsWhenBuildingCurrentSnapshot() throws IOException {
        Company company = new Company();
        company.setName("Empresa test");
        company.setPlan(Plan.GOLD);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

        List<WorkforceImport> savedImports = new ArrayList<>();
        wireRepository(company, savedImports);

        WorkforceImportDto workforce = workforceImportService.importFile(7L, workforceCsvFile(
            "workforce-sep.csv",
            workforceRow("ALFA TEAM", 140, 5, 60, 220, 220),
            workforceRow("BETA TEAM", 90, 3, 55, 150, 150)
        ), "2027-09");
        ResponseStatusException rejectedLegacyImport = assertThrows(
            ResponseStatusException.class,
            () -> workforceImportService.importLaborCosts(7L, laborCostsCsvFile(
                "legacy-costs.csv",
                laborCostRow("GESTOR LEGACY", "OCTUBRE", 1000, 300)
            ), "2027-09")
        );
        assertEquals(400, rejectedLegacyImport.getStatusCode().value());
        workforceImportService.importLaborCosts(7L, laborCostsCsvFile(
            "labor-sep.csv",
            laborCostRow("ALFA TEAM", "SEPTIEMBRE", 2000, 600),
            laborCostRow("GESTOR EXTERNO", "SEPTIEMBRE", 900, 270)
        ), "2027-09");

        WorkforceSummaryDto summary = workforceImportService.getSummary(7L, workforce.id());
        assertEquals("2027-09", summary.workforceImport().referencePeriod());
        assertEquals("2027-09", summary.laborCostsImport().referencePeriod());
        assertEquals("septiembre 2027", summary.laborCostsImport().referenceLabel());
        assertEquals(2600.0, summary.laborCosts().totalCosteLaboralAnual(), 0.01);
        assertEquals(List.of("ALFA TEAM"),
            summary.laborCosts().gestores().stream().map(row -> row.gestor()).toList());
        assertEquals(List.of("ALFA TEAM", "BETA TEAM"),
            summary.gestores().stream().map(row -> row.gestor()).sorted().toList());
        assertTrue(summary.gestores().stream().noneMatch(row -> "GESTOR EXTERNO".equals(row.gestor())));

        var alfa = summary.gestores().stream().filter(row -> "ALFA TEAM".equals(row.gestor())).findFirst().orElseThrow();
        var beta = summary.gestores().stream().filter(row -> "BETA TEAM".equals(row.gestor())).findFirst().orElseThrow();
        assertEquals(2600.0, alfa.costeLaboralAnual(), 0.01);
        assertNull(beta.costeLaboralAnual());

        assertEquals(1, summary.laborCostsHistory().imports().size());
        assertTrue(summary.laborCostsHistory().imports().stream().anyMatch(item -> "2027-09".equals(item.referencePeriod())));
    }

    @Test
    void supersedesPreviousUnknownSnapshotBeforeSavingNewUnknownSnapshot() throws IOException {
        Company company = new Company();
        company.setName("Empresa test");
        company.setPlan(Plan.GOLD);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

        List<WorkforceImport> savedImports = new ArrayList<>();
        wireRepository(company, savedImports);

        workforceImportService.importFile(7L, workforceCsvFile(
            "workforce-unknown-1.csv",
            workforceRow("ALBA", 100, 4, 60, 180, 180)
        ));
        workforceImportService.importFile(7L, workforceCsvFile(
            "workforce-unknown-2.csv",
            workforceRow("ALBA", 110, 5, 65, 190, 190)
        ));

        assertEquals(2, savedImports.size());
        assertEquals(WorkforceImportStatus.SUPERSEDED, savedImports.get(0).getImportStatus());
        assertEquals(WorkforceImportStatus.ACTIVE, savedImports.get(1).getImportStatus());
        assertEquals("UNKNOWN", savedImports.get(0).getReferencePeriod());
        assertEquals("UNKNOWN", savedImports.get(1).getReferencePeriod());
        verify(importRepository, atLeastOnce()).flush();
    }

    @Test
    void distinguishesInactiveRowsAndPreservesMissingMetrics() throws IOException {
        Company company = new Company();
        company.setName("Empresa test");
        company.setPlan(Plan.GOLD);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));
        when(importRepository.save(any(WorkforceImport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String csv = String.join("\n",
            "GESTOR,MINUTAS,F/BAJA,CONT/MODELOS,IS/IRPF,DDCC,LIBROS,CARGA DE TRABAJO,% CONTABILIDAD,N AS 2023,N AS 2024,PROMEDIO",
            "ALBA,100,,SI,NO,SI-NEGATIVO,SI-PDTE,5,70,10,20,30",
            "BRUNO,,BAJA,NO,SI,SI,NO,,,,,"
        );
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "workforce-active-inactive.csv",
            "text/csv",
            csv.getBytes(StandardCharsets.UTF_8)
        );

        WorkforceImportDto dto = workforceImportService.importFile(7L, file);
        assertEquals(2, dto.rowCount());
        assertEquals(List.of(2023, 2024), dto.activityYears());

        ArgumentCaptor<WorkforceImport> captor = ArgumentCaptor.forClass(WorkforceImport.class);
        verify(importRepository).save(captor.capture());
        WorkforceSummaryDto summary = objectMapper.readValue(captor.getValue().getSummaryJson(), WorkforceSummaryDto.class);

        assertEquals(2, summary.kpis().totalClients());
        assertEquals(1, summary.kpis().activeClients());
        assertEquals(1, summary.kpis().inactiveClients());
        assertEquals(100.0, summary.kpis().totalMinutas(), 0.01);
        assertEquals(5.0, summary.kpis().totalCarga(), 0.01);
        assertEquals(30.0, summary.kpis().totalVolumenAsientos(), 0.01);

        var alba = summary.gestores().stream().filter(row -> "ALBA".equals(row.gestor())).findFirst().orElseThrow();
        assertEquals(1, alba.activeClients());
        assertEquals(0, alba.inactiveClients());
        assertEquals(100.0, alba.totalMinutas(), 0.01);
        assertEquals(5.0, alba.totalCarga(), 0.01);
        assertEquals(5.0, alba.cargaMedia(), 0.01);
        assertEquals(70.0, alba.pctContabilidadMedio(), 0.01);
        assertEquals(30.0, alba.totalVolumenAsientos(), 0.01);
        assertEquals(1, alba.contModelosOk());
        assertEquals(1, alba.isIrpfStates().no());
        assertEquals(1, alba.ddccStates().negative());
        assertEquals(1, alba.librosStates().pending());
        assertEquals(10L, alba.annualSeatTotals().get(2023));
        assertEquals(20L, alba.annualSeatTotals().get(2024));

        var bruno = summary.gestores().stream().filter(row -> "BRUNO".equals(row.gestor())).findFirst().orElseThrow();
        assertEquals(0, bruno.activeClients());
        assertEquals(1, bruno.inactiveClients());
        assertNull(bruno.totalMinutas());
        assertNull(bruno.totalCarga());
        assertNull(bruno.cargaMedia());
        assertNull(bruno.pctContabilidadMedio());
        assertNull(bruno.totalVolumenAsientos());
        assertEquals(1, bruno.contModelosStates().no());
        assertEquals(1, bruno.isIrpfOk());
        assertEquals(1, bruno.ddccOk());
        assertEquals(1, bruno.librosStates().no());
    }

    @Test
    void importsReorderedDynamicWorkforceWithoutOverfittingToRealWorkbook() throws IOException {
        Company company = new Company();
        company.setName("Empresa test");
        company.setPlan(Plan.GOLD);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));
        when(importRepository.save(any(WorkforceImport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String csv = String.join("\n",
            "PROMEDIO,Manager,N AS 2027,% Contabilidad,Cont/Modelos,F/Baja,N AS 2011,Minuta,Libros,Carga,IS/IRPF,DDCC",
            "100,ORBITA,120,68,SI,,80,45,SI-PDTE,3,NO,SI",
            ",ORBITA,0,,NO,BAJA,40,,SI,0,SI-NEGATIVO,NO",
            "0,NADIR,,0,SI,,0,0,NO,,SI,SI-NEGATIVO",
            "50,ZENITH,30,25,SI-NEGATIVO,,20,10,SI,1,SI-PDTE,SI"
        );
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "anti-overfit-workforce.csv",
            "text/csv",
            csv.getBytes(StandardCharsets.UTF_8)
        );

        WorkforceImportDto dto = workforceImportService.importFile(7L, file);
        assertEquals(4, dto.rowCount());
        assertEquals(List.of(2011, 2027), dto.activityYears());
        assertTrue(dto.detectedColumns().contains("Manager"));
        assertTrue(dto.detectedColumns().contains("Minuta"));
        assertTrue(dto.detectedColumns().contains("N AS 2011"));
        assertTrue(dto.detectedColumns().contains("N AS 2027"));

        ArgumentCaptor<WorkforceImport> captor = ArgumentCaptor.forClass(WorkforceImport.class);
        verify(importRepository).save(captor.capture());
        WorkforceSummaryDto summary = objectMapper.readValue(captor.getValue().getSummaryJson(), WorkforceSummaryDto.class);

        assertEquals(4, summary.kpis().totalClients());
        assertEquals(3, summary.kpis().activeClients());
        assertEquals(1, summary.kpis().inactiveClients());
        assertEquals(3, summary.kpis().totalGestores());
        assertEquals(55.0, summary.kpis().totalMinutas(), 0.01);
        assertEquals(4.0, summary.kpis().totalCarga(), 0.01);
        assertEquals(170.0, summary.kpis().totalVolumenAsientos(), 0.01);
        assertEquals(List.of("ORBITA", "NADIR", "ZENITH"),
            summary.gestores().stream().map(row -> row.gestor()).toList());

        var orbita = summary.gestores().stream().filter(row -> "ORBITA".equals(row.gestor())).findFirst().orElseThrow();
        assertEquals(2, orbita.totalClients());
        assertEquals(1, orbita.activeClients());
        assertEquals(1, orbita.inactiveClients());
        assertEquals(45.0, orbita.totalMinutas(), 0.01);
        assertEquals(3.0, orbita.totalCarga(), 0.01);
        assertEquals(1.5, orbita.cargaMedia(), 0.01);
        assertEquals(68.0, orbita.pctContabilidadMedio(), 0.01);
        assertEquals(120.0, orbita.totalVolumenAsientos(), 0.01);
        assertEquals(120L, orbita.annualSeatTotals().get(2011));
        assertEquals(120L, orbita.annualSeatTotals().get(2027));
        assertEquals(1, orbita.contModelosOk());
        assertEquals(1, orbita.contModelosStates().no());
        assertEquals(1, orbita.isIrpfStates().no());
        assertEquals(1, orbita.isIrpfStates().negative());
        assertEquals(1, orbita.ddccOk());
        assertEquals(1, orbita.ddccStates().no());
        assertEquals(1, orbita.librosOk());
        assertEquals(1, orbita.librosStates().pending());

        var nadir = summary.gestores().stream().filter(row -> "NADIR".equals(row.gestor())).findFirst().orElseThrow();
        assertEquals(1, nadir.totalClients());
        assertEquals(1, nadir.activeClients());
        assertEquals(0, nadir.inactiveClients());
        assertEquals(0.0, nadir.totalMinutas(), 0.01);
        assertNull(nadir.totalCarga());
        assertNull(nadir.cargaMedia());
        assertEquals(0.0, nadir.pctContabilidadMedio(), 0.01);
        assertEquals(0.0, nadir.totalVolumenAsientos(), 0.01);
        assertEquals(0L, nadir.annualSeatTotals().get(2011));
        assertNull(nadir.annualSeatTotals().get(2027));
        assertEquals(1, nadir.contModelosOk());
        assertEquals(1, nadir.isIrpfOk());
        assertEquals(1, nadir.ddccStates().negative());
        assertEquals(1, nadir.librosStates().no());

        var zenith = summary.gestores().stream().filter(row -> "ZENITH".equals(row.gestor())).findFirst().orElseThrow();
        assertEquals(1, zenith.totalClients());
        assertEquals(1, zenith.activeClients());
        assertEquals(0, zenith.inactiveClients());
        assertEquals(10.0, zenith.totalMinutas(), 0.01);
        assertEquals(1.0, zenith.totalCarga(), 0.01);
        assertEquals(1.0, zenith.cargaMedia(), 0.01);
        assertEquals(25.0, zenith.pctContabilidadMedio(), 0.01);
        assertEquals(50.0, zenith.totalVolumenAsientos(), 0.01);
        assertEquals(20L, zenith.annualSeatTotals().get(2011));
        assertEquals(30L, zenith.annualSeatTotals().get(2027));
        assertEquals(1, zenith.contModelosStates().negative());
        assertEquals(1, zenith.isIrpfStates().pending());
        assertEquals(1, zenith.ddccOk());
        assertEquals(1, zenith.librosOk());
    }

    @Test
    void buildsHistoryFromPreviousImportsWithoutDuplicatingSnapshots() throws IOException {
        Company company = new Company();
        company.setName("Empresa test");
        company.setPlan(Plan.GOLD);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

        List<WorkforceImport> savedImports = new ArrayList<>();
        when(importRepository.save(any(WorkforceImport.class))).thenAnswer(invocation -> {
            WorkforceImport saved = invocation.getArgument(0);
            saved.setCreatedAt(savedImports.isEmpty()
                ? Instant.parse("2026-01-10T10:00:00Z")
                : Instant.parse("2026-02-10T10:00:00Z"));
            savedImports.add(saved);
            return saved;
        });
        when(importRepository.findFirstByCompanyIdAndImportKindOrderByCreatedAtDesc(7L, WorkforceImportKind.WORKFORCE))
            .thenAnswer(invocation -> savedImports.stream()
                .filter(imported -> imported.getImportKind() == WorkforceImportKind.WORKFORCE)
                .max(Comparator.comparing(WorkforceImport::getCreatedAt)));
        when(importRepository.findFirstByCompanyIdAndImportKindOrderByCreatedAtDesc(7L, WorkforceImportKind.LABOR_COSTS))
            .thenReturn(Optional.empty());
        when(importRepository.findTop12ByCompanyIdAndImportKindOrderByCreatedAtDesc(7L, WorkforceImportKind.WORKFORCE))
            .thenAnswer(invocation -> savedImports.stream()
                .filter(imported -> imported.getImportKind() == WorkforceImportKind.WORKFORCE)
                .sorted(Comparator.comparing(WorkforceImport::getCreatedAt).reversed())
                .toList());

        MockMultipartFile firstFile = new MockMultipartFile(
            "file",
            "workforce-1.csv",
            "text/csv",
            String.join("\n",
                "GESTOR,MINUTAS,F/BAJA,CONT/MODELOS,IS/IRPF,DDCC,LIBROS,CARGA DE TRABAJO,% CONTABILIDAD,N AS 2024,PROMEDIO",
                "ALBA,100,,SI,SI,SI,SI,4,60,200,200",
                "BRUNO,200,,SI,SI,SI,SI,8,80,400,400"
            ).getBytes(StandardCharsets.UTF_8)
        );
        workforceImportService.importFile(7L, firstFile);

        WorkforceSummaryDto firstSummary = workforceImportService.getSummary(7L);
        assertNotNull(firstSummary.history());
        assertEquals(1, firstSummary.history().imports().size());
        assertEquals("workforce-1.csv", firstSummary.history().imports().get(0).filename());

        MockMultipartFile secondFile = new MockMultipartFile(
            "file",
            "workforce-2.csv",
            "text/csv",
            String.join("\n",
                "GESTOR,MINUTAS,F/BAJA,CONT/MODELOS,IS/IRPF,DDCC,LIBROS,CARGA DE TRABAJO,% CONTABILIDAD,N AS 2024,PROMEDIO",
                "ALBA,140,,SI,SI,SI,SI,5,65,220,220",
                "BRUNO,180,,SI,SI,SI,SI,7,75,380,380"
            ).getBytes(StandardCharsets.UTF_8)
        );
        workforceImportService.importFile(7L, secondFile);

        WorkforceSummaryDto secondSummary = workforceImportService.getSummary(7L);
        assertNotNull(secondSummary.history());
        assertEquals(2, secondSummary.history().imports().size());
        assertEquals(List.of("workforce-1.csv", "workforce-2.csv"),
            secondSummary.history().imports().stream().map(row -> row.filename()).toList());
        assertEquals(2L,
            secondSummary.history().imports().stream().map(row -> row.filename()).distinct().count());
        assertEquals(List.of(300.0, 320.0),
            secondSummary.history().imports().stream().map(row -> row.totalMinutas()).toList());

        var albaHistory = secondSummary.history().gestores().stream()
            .filter(row -> "ALBA".equals(row.gestor()))
            .findFirst()
            .orElseThrow();
        assertEquals(2, albaHistory.points().size());
        assertEquals(List.of(100.0, 140.0), albaHistory.points().stream().map(point -> point.totalMinutas()).toList());
    }

    @Test
    void comparesTwoLaborCostPeriodsFromPersistedCanonicalWorkers() throws IOException {
        Company company = new Company();
        company.setName("Empresa test");
        company.setPlan(Plan.GOLD);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

        List<WorkforceImport> savedImports = new ArrayList<>();
        wireRepository(company, savedImports);

        workforceImportService.importFile(7L, workforceCsvFile(
            "workforce-apr.csv",
            workforceRow("ALBA", 120, 5, 60, 210, 210),
            workforceRow("BRUNO", 95, 4, 58, 180, 180)
        ), "2026-04");
        workforceImportService.importFile(7L, workforceCsvFile(
            "workforce-may.csv",
            workforceRow("ALBA", 126, 5, 61, 220, 220),
            workforceRow("BRUNO", 100, 4, 59, 190, 190),
            workforceRow("DIEGO", 82, 3, 52, 150, 150)
        ), "2026-05");

        workforceImportService.importLaborCosts(7L, laborCostsCsvFileWithHeader(
            "labor-apr.csv",
            "GESTOR,TRABAJADOR,ID EMPLEADO,MES,COSTE PERSONAL,SS EMPRESA,MONEDA",
            "ALBA,ANA,EMP-1,ABRIL,1000,300,EUR",
            "BRUNO,BEA,EMP-2,ABRIL,900,270,EUR",
            "BRUNO,CARLA,EMP-3,ABRIL,800,240,EUR"
        ), "2026-04");
        workforceImportService.importLaborCosts(7L, laborCostsCsvFileWithHeader(
            "labor-may.csv",
            "GESTOR,TRABAJADOR,ID EMPLEADO,MES,COSTE PERSONAL,SS EMPRESA,MONEDA",
            "ALBA,ANA,EMP-1,MAYO,1100,330,EUR",
            "BRUNO,BEA,EMP-2,MAYO,950,285,EUR",
            "DIEGO,DANI,EMP-4,MAYO,700,210,EUR",
            "FUERA,ELSA,EMP-9,MAYO,500,150,EUR"
        ), "2026-05");

        var comparison = workforceImportService.getLaborCostComparison(7L, "2026-04", "2026-05", null, null);
        assertEquals("READY", comparison.status());
        assertEquals("EUR", comparison.currency());
        assertEquals("2026-04", comparison.baseImport().referencePeriod());
        assertEquals("2026-05", comparison.comparisonImport().referencePeriod());
        assertEquals(3510.0, comparison.totals().baseCosteTotal(), 0.01);
        assertEquals(4225.0, comparison.totals().comparisonCosteTotal(), 0.01);
        assertEquals(715.0, comparison.totals().deltaCosteTotal(), 0.01);
        assertEquals(3510.0, comparison.totals().reconciledBaseCosteTotal(), 0.01);
        assertEquals(3575.0, comparison.totals().reconciledComparisonCosteTotal(), 0.01);
        assertEquals(2, comparison.counts().comparableWorkers());
        assertEquals(1, comparison.counts().onlyBaseWorkers());
        assertEquals(1, comparison.counts().onlyComparisonWorkers());
        assertEquals(1, comparison.counts().reviewWorkers());
        assertEquals(1, comparison.counts().unmatchedWorkers());
        assertEquals(List.of("ANA", "BEA"),
            comparison.comparableWorkers().stream().map(row -> row.workerLabel()).sorted().toList());
        assertEquals("CARLA", comparison.onlyBaseWorkers().get(0).workerLabel());
        assertEquals("DANI", comparison.onlyComparisonWorkers().get(0).workerLabel());
        assertEquals("ELSA", comparison.reviewWorkers().get(0).workerLabel());
    }

    @Test
    void returnsMissingImportInsteadOfFallingBackToLatestPeriod() throws IOException {
        Company company = new Company();
        company.setName("Empresa test");
        company.setPlan(Plan.GOLD);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

        List<WorkforceImport> savedImports = new ArrayList<>();
        wireRepository(company, savedImports);

        workforceImportService.importLaborCosts(7L, laborCostsCsvFileWithHeader(
            "labor-may-2026.csv",
            "GESTOR,TRABAJADOR,ID EMPLEADO,MES,COSTE PERSONAL,SS EMPRESA,MONEDA",
            "ALBA,ANA,EMP-1,2026-05,1000,300,EUR"
        ), "2026-05");
        workforceImportService.importLaborCosts(7L, laborCostsCsvFileWithHeader(
            "labor-jun-2026.csv",
            "GESTOR,TRABAJADOR,ID EMPLEADO,MES,COSTE PERSONAL,SS EMPRESA,MONEDA",
            "ALBA,ANA,EMP-1,2026-06,1100,330,EUR"
        ), "2026-06");

        var comparison = workforceImportService.getLaborCostComparison(7L, "2026-04", "2026-05", null, null);

        assertEquals("MISSING_IMPORT", comparison.status());
        assertNull(comparison.baseImport());
        assertNotNull(comparison.comparisonImport());
        assertEquals("2026-05", comparison.comparisonImport().referencePeriod());
    }

    @Test
    void rejectsComparisonWhenSelectedImportIdIsInvalidOrIncoherent() throws IOException {
        Company company = new Company();
        company.setName("Empresa test");
        company.setPlan(Plan.GOLD);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

        List<WorkforceImport> savedImports = new ArrayList<>();
        wireRepository(company, savedImports);

        workforceImportService.importLaborCosts(7L, laborCostsCsvFileWithHeader(
            "labor-apr-2026.csv",
            "GESTOR,TRABAJADOR,ID EMPLEADO,MES,COSTE PERSONAL,SS EMPRESA,MONEDA",
            "ALBA,ANA,EMP-1,2026-04,1000,300,EUR"
        ), "2026-04");
        workforceImportService.importLaborCosts(7L, laborCostsCsvFileWithHeader(
            "labor-may-2026.csv",
            "GESTOR,TRABAJADOR,ID EMPLEADO,MES,COSTE PERSONAL,SS EMPRESA,MONEDA",
            "ALBA,ANA,EMP-1,2026-05,1100,330,EUR"
        ), "2026-05");

        WorkforceImport aprilImport = savedImports.stream()
            .filter(imported -> imported.getImportKind() == WorkforceImportKind.LABOR_COSTS)
            .filter(imported -> "2026-04".equals(imported.getReferencePeriod()))
            .findFirst()
            .orElseThrow();
        WorkforceImport mayImport = savedImports.stream()
            .filter(imported -> imported.getImportKind() == WorkforceImportKind.LABOR_COSTS)
            .filter(imported -> "2026-05".equals(imported.getReferencePeriod()))
            .findFirst()
            .orElseThrow();

        ResponseStatusException missingId = assertThrows(
            ResponseStatusException.class,
            () -> workforceImportService.getLaborCostComparison(7L, "2026-04", "2026-05", 999L, mayImport.getId())
        );
        assertEquals(400, missingId.getStatusCode().value());

        ResponseStatusException wrongPeriod = assertThrows(
            ResponseStatusException.class,
            () -> workforceImportService.getLaborCostComparison(7L, "2026-04", "2026-05", mayImport.getId(), null)
        );
        assertEquals(400, wrongPeriod.getStatusCode().value());
        assertTrue(wrongPeriod.getReason().contains("2026-04"));

        ResponseStatusException sameImport = assertThrows(
            ResponseStatusException.class,
            () -> workforceImportService.getLaborCostComparison(7L, "2026-04", "2026-05", aprilImport.getId(), aprilImport.getId())
        );
        assertEquals(400, sameImport.getStatusCode().value());

        ResponseStatusException samePeriod = assertThrows(
            ResponseStatusException.class,
            () -> workforceImportService.getLaborCostComparison(7L, "2026-05", "2026-05", null, null)
        );
        assertEquals(400, samePeriod.getStatusCode().value());
    }

    @Test
    void rejectsComparisonWhenImportIdBelongsToAnotherCompanyOrWrongType() throws IOException {
        Company company = new Company();
        company.setName("Empresa test");
        company.setPlan(Plan.GOLD);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

        List<WorkforceImport> savedImports = new ArrayList<>();
        wireRepository(company, savedImports);

        workforceImportService.importLaborCosts(7L, laborCostsCsvFileWithHeader(
            "labor-may-2026.csv",
            "GESTOR,TRABAJADOR,ID EMPLEADO,MES,COSTE PERSONAL,SS EMPRESA,MONEDA",
            "ALBA,ANA,EMP-1,2026-05,1100,330,EUR"
        ), "2026-05");

        WorkforceImport mayImport = savedImports.stream()
            .filter(imported -> imported.getImportKind() == WorkforceImportKind.LABOR_COSTS)
            .findFirst()
            .orElseThrow();

        Company foreignCompany = new Company();
        WorkforceImport foreignImport = new WorkforceImport();
        foreignImport.setId(91L);
        foreignImport.setCompany(foreignCompany);
        foreignImport.setImportKind(WorkforceImportKind.LABOR_COSTS);
        foreignImport.setImportStatus(WorkforceImportStatus.ACTIVE);
        foreignImport.setReferencePeriod("2026-04");
        foreignImport.setCreatedAt(Instant.parse("2026-04-01T00:00:00Z"));
        savedImports.add(foreignImport);

        WorkforceImport wrongTypeImport = new WorkforceImport();
        wrongTypeImport.setId(92L);
        wrongTypeImport.setCompany(company);
        wrongTypeImport.setImportKind(WorkforceImportKind.WORKFORCE);
        wrongTypeImport.setImportStatus(WorkforceImportStatus.ACTIVE);
        wrongTypeImport.setReferencePeriod("2026-04");
        wrongTypeImport.setCreatedAt(Instant.parse("2026-04-02T00:00:00Z"));
        savedImports.add(wrongTypeImport);

        ResponseStatusException foreignSelection = assertThrows(
            ResponseStatusException.class,
            () -> workforceImportService.getLaborCostComparison(7L, "2026-04", "2026-05", 91L, mayImport.getId())
        );
        assertEquals(400, foreignSelection.getStatusCode().value());

        ResponseStatusException wrongTypeSelection = assertThrows(
            ResponseStatusException.class,
            () -> workforceImportService.getLaborCostComparison(7L, "2026-04", "2026-05", 92L, mayImport.getId())
        );
        assertEquals(400, wrongTypeSelection.getStatusCode().value());
    }

    @Test
    void blocksComparisonWhenCurrenciesDifferAcrossPeriods() throws IOException {
        Company company = new Company();
        company.setName("Empresa test");
        company.setPlan(Plan.GOLD);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

        List<WorkforceImport> savedImports = new ArrayList<>();
        wireRepository(company, savedImports);

        workforceImportService.importFile(7L, workforceCsvFile("workforce-apr.csv", workforceRow("ALBA", 100, 4, 60, 180, 180)), "2026-04");
        workforceImportService.importFile(7L, workforceCsvFile("workforce-may.csv", workforceRow("ALBA", 104, 4, 61, 184, 184)), "2026-05");

        workforceImportService.importLaborCosts(7L, laborCostsCsvFileWithHeader(
            "labor-apr.csv",
            "GESTOR,TRABAJADOR,ID EMPLEADO,MES,COSTE PERSONAL,SS EMPRESA,MONEDA",
            "ALBA,ANA,EMP-1,ABRIL,1000,300,EUR"
        ), "2026-04");
        workforceImportService.importLaborCosts(7L, laborCostsCsvFileWithHeader(
            "labor-may.csv",
            "GESTOR,TRABAJADOR,ID EMPLEADO,MES,COSTE PERSONAL,SS EMPRESA,MONEDA",
            "ALBA,ANA,EMP-1,MAYO,1100,330,USD"
        ), "2026-05");

        var comparison = workforceImportService.getLaborCostComparison(7L, "2026-04", "2026-05", null, null);
        assertEquals("CURRENCY_MISMATCH", comparison.status());
        assertTrue(comparison.message().contains("monedas"));
        assertEquals(0, comparison.comparableWorkers().size());
    }

    @Test
    void rejectsLaborCostImportWhenDeclaredAndDetectedPeriodsContradict() throws IOException {
        Company company = new Company();
        company.setName("Empresa test");
        company.setPlan(Plan.GOLD);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

        List<WorkforceImport> savedImports = new ArrayList<>();
        wireRepository(company, savedImports);

        workforceImportService.importLaborCosts(7L, laborCostsCsvFileWithHeader(
            "labor-may-2026.csv",
            "GESTOR,TRABAJADOR,ID EMPLEADO,MES,COSTE PERSONAL,SS EMPRESA,MONEDA",
            "ALBA,ANA,EMP-1,2026-05,1000,300,EUR"
        ), "2026-05");

        WorkforceImport activeImport = savedImports.stream()
            .filter(imported -> imported.getImportKind() == WorkforceImportKind.LABOR_COSTS)
            .findFirst()
            .orElseThrow();

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> workforceImportService.importLaborCosts(7L, laborCostsCsvFileWithHeader(
                "labor-may-2025.csv",
                "GESTOR,TRABAJADOR,ID EMPLEADO,MES,COSTE PERSONAL,SS EMPRESA,MONEDA",
                "ALBA,ANA,EMP-1,2025-05,1200,360,EUR"
            ), "2026-05")
        );

        assertEquals(400, error.getStatusCode().value());
        assertTrue(error.getReason().contains("declarado 2026-05"));
        assertTrue(error.getReason().contains("detectado 2025-05"));
        assertEquals(1, savedImports.stream().filter(imported -> imported.getImportKind() == WorkforceImportKind.LABOR_COSTS).count());
        assertEquals(WorkforceImportStatus.ACTIVE, activeImport.getImportStatus());
    }

    @Test
    void acceptsExplicitLaborCostPeriodWhenDetectedPeriodMatchesOrIsMissing() throws IOException {
        Company company = new Company();
        company.setName("Empresa test");
        company.setPlan(Plan.GOLD);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

        List<WorkforceImport> savedImports = new ArrayList<>();
        wireRepository(company, savedImports);

        WorkforceImportDto matched = workforceImportService.importLaborCosts(7L, laborCostsCsvFileWithHeader(
            "labor-may-2026.csv",
            "GESTOR,TRABAJADOR,ID EMPLEADO,MES,COSTE PERSONAL,SS EMPRESA,MONEDA",
            "ALBA,ANA,EMP-1,2026-05,1000,300,EUR"
        ), "2026-05");
        assertEquals("2026-05", matched.referencePeriod());

        WorkforceImportDto explicitFallback = workforceImportService.importLaborCosts(7L, laborCostsCsvFileWithHeader(
            "labor-without-period.csv",
            "GESTOR,TRABAJADOR,ID EMPLEADO,MES,COSTE PERSONAL,SS EMPRESA,MONEDA",
            "ALBA,ANA,EMP-1,,900,270,EUR"
        ), "2026-06");
        assertEquals("2026-06", explicitFallback.referencePeriod());
    }

    @Test
    void resolvesLaborCostPeriodFromFilenameAndRejectsUnresolvableOrAmbiguousFiles() throws IOException {
        Company company = new Company();
        company.setName("Empresa test");
        company.setPlan(Plan.GOLD);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

        List<WorkforceImport> savedImports = new ArrayList<>();
        wireRepository(company, savedImports);

        WorkforceImportDto inferred = workforceImportService.importLaborCosts(7L, laborCostsCsvFileWithHeader(
            "Costes_Laborales_Marzo_2027.csv",
            "GESTOR,TRABAJADOR,ID EMPLEADO,MES,COSTE PERSONAL,SS EMPRESA,MONEDA",
            "ALBA,ANA,EMP-1,MARZO,1000,300,EUR"
        ), (String) null);
        assertEquals("2027-03", inferred.referencePeriod());

        ResponseStatusException unresolved = assertThrows(
            ResponseStatusException.class,
            () -> workforceImportService.importLaborCosts(7L, laborCostsCsvFileWithHeader(
                "labor-final.csv",
                "GESTOR,TRABAJADOR,ID EMPLEADO,MES,COSTE PERSONAL,SS EMPRESA,MONEDA",
                "ALBA,ANA,EMP-1,MARZO,1000,300,EUR"
            ), (String) null)
        );
        assertEquals(400, unresolved.getStatusCode().value());

        ResponseStatusException ambiguous = assertThrows(
            ResponseStatusException.class,
            () -> workforceImportService.importLaborCosts(7L, laborCostsCsvFileWithHeader(
                "labor-ambiguous.csv",
                "GESTOR,TRABAJADOR,ID EMPLEADO,MES,COSTE PERSONAL,SS EMPRESA,MONEDA",
                "ALBA,ANA,EMP-1,ABRIL,1000,300,EUR",
                "ALBA,ANA,EMP-1,MAYO,1000,300,EUR"
            ), "2026-04")
        );
        assertEquals(400, ambiguous.getStatusCode().value());
    }

    @Test
    void matchesLaborCostsAgainstWorkforceSnapshotOfSamePeriod() throws IOException {
        Company company = new Company();
        company.setName("Empresa test");
        company.setPlan(Plan.GOLD);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

        List<WorkforceImport> savedImports = new ArrayList<>();
        wireRepository(company, savedImports);

        workforceImportService.importFile(7L, workforceCsvFile("workforce-apr.csv", workforceRow("TEAM APRIL", 100, 4, 60, 180, 180)), "2026-04");
        workforceImportService.importFile(7L, workforceCsvFile("workforce-may.csv", workforceRow("TEAM MAY", 110, 4, 61, 190, 190)), "2026-05");

        workforceImportService.importLaborCosts(7L, laborCostsCsvFileWithHeader(
            "labor-apr.csv",
            "GESTOR,TRABAJADOR,ID EMPLEADO,MES,COSTE PERSONAL,SS EMPRESA,MONEDA",
            "TEAM APRIL,ANA,EMP-1,ABRIL,1000,300,EUR"
        ), "2026-04");

        WorkforceImport savedLaborImport = savedImports.stream()
            .filter(imported -> imported.getImportKind() == WorkforceImportKind.LABOR_COSTS)
            .reduce((first, second) -> second)
            .orElseThrow();
        WorkforceLaborCostImportSummaryDto summary = objectMapper.readValue(savedLaborImport.getSummaryJson(), WorkforceLaborCostImportSummaryDto.class);

        assertNotNull(summary.canonical());
        assertEquals("2026-04", summary.canonical().matchedWorkforceReferencePeriod());
        assertEquals("TEAM APRIL", summary.canonical().workers().get(0).canonicalGestor());
        assertEquals("MATCHED", summary.canonical().workers().get(0).matchingState());
    }

    private void wireRepository(Company company, List<WorkforceImport> savedImports) {
        lenient().when(importRepository.save(any(WorkforceImport.class))).thenAnswer(invocation -> {
            WorkforceImport saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId((long) (savedImports.size() + 1));
            }
            if (saved.getCreatedAt() == null) {
                saved.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(savedImports.size()));
            }
            savedImports.add(saved);
            return saved;
        });
        lenient().when(importRepository.findByCompanyIdAndImportKindOrderByCreatedAtDesc(anyLong(), any(WorkforceImportKind.class)))
            .thenAnswer(invocation -> filterImports(savedImports, company, invocation.getArgument(0), invocation.getArgument(1), Integer.MAX_VALUE));
        lenient().when(importRepository.findTop12ByCompanyIdAndImportKindOrderByCreatedAtDesc(anyLong(), any(WorkforceImportKind.class)))
            .thenAnswer(invocation -> filterImports(savedImports, company, invocation.getArgument(0), invocation.getArgument(1), 12));
        lenient().when(importRepository.findFirstByCompanyIdAndImportKindOrderByCreatedAtDesc(anyLong(), any(WorkforceImportKind.class)))
            .thenAnswer(invocation -> filterImports(savedImports, company, invocation.getArgument(0), invocation.getArgument(1), 1).stream().findFirst());
        lenient().when(importRepository.findFirstByCompanyIdAndImportKindAndImportStatusOrderByCreatedAtDesc(anyLong(), any(WorkforceImportKind.class), any()))
            .thenAnswer(invocation -> filterImports(savedImports, company, invocation.getArgument(0), invocation.getArgument(1), Integer.MAX_VALUE).stream()
                .filter(imported -> imported.getImportStatus() == invocation.getArgument(2))
                .findFirst());
        lenient().when(importRepository.findByIdAndCompanyIdAndImportKind(anyLong(), anyLong(), any(WorkforceImportKind.class)))
            .thenAnswer(invocation -> savedImports.stream()
                .filter(imported -> Objects.equals(imported.getId(), invocation.getArgument(0)))
                .filter(imported -> imported.getCompany() == company)
                .filter(imported -> imported.getImportKind() == invocation.getArgument(2))
                .findFirst());
    }

    private static List<WorkforceImport> filterImports(List<WorkforceImport> savedImports,
                                                       Company company,
                                                       Long companyId,
                                                       WorkforceImportKind importKind,
                                                       int limit) {
        return savedImports.stream()
            .filter(imported -> companyId == 7L)
            .filter(imported -> imported.getCompany() == company)
            .filter(imported -> imported.getImportKind() == importKind)
            .sorted(Comparator.comparing(WorkforceImport::getCreatedAt).reversed()
                .thenComparing(WorkforceImport::getId, Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(limit)
            .toList();
    }

    private static MockMultipartFile workforceCsvFile(String filename, String... rows) {
        return new MockMultipartFile(
            "file",
            filename,
            "text/csv",
            String.join("\n",
                "GESTOR,MINUTAS,F/BAJA,CONT/MODELOS,IS/IRPF,DDCC,LIBROS,CARGA DE TRABAJO,% CONTABILIDAD,N AS 2024,PROMEDIO",
                String.join("\n", rows)
            ).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String workforceRow(String gestor,
                                       double minutas,
                                       double carga,
                                       double pctContabilidad,
                                       int asientos,
                                       int promedio) {
        return "%s,%s,,SI,SI,SI,SI,%s,%s,%s,%s".formatted(
            gestor,
            minutas,
            carga,
            pctContabilidad,
            asientos,
            promedio
        );
    }

    private static MockMultipartFile laborCostsCsvFile(String filename, String... rows) {
        return new MockMultipartFile(
            "file",
            filename,
            "text/csv",
            buildLaborCostsCsv(List.of(rows)).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static MockMultipartFile laborCostsCsvFileWithHeader(String filename, String header, String... rows) {
        return new MockMultipartFile(
            "file",
            filename,
            "text/csv",
            String.join("\n", header, String.join("\n", rows)).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String buildLaborCostsCsv(List<String> rows) {
        return String.join("\n",
            "GESTOR,MES,COSTE PERSONAL,SS EMPRESA",
            String.join("\n", rows)
        );
    }

    private static String laborCostRow(String gestor, String month, double personal, double ssEmpresa) {
        return "%s,%s,%s,%s".formatted(gestor, month, personal, ssEmpresa);
    }
}

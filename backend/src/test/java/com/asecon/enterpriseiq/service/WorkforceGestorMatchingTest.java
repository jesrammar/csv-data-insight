package com.asecon.enterpriseiq.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.asecon.enterpriseiq.dto.WorkforceGestorDto;
import com.asecon.enterpriseiq.dto.WorkforceKpiDto;
import com.asecon.enterpriseiq.dto.WorkforceLaborCostImportSummaryDto;
import com.asecon.enterpriseiq.dto.WorkforceStatusBreakdownDto;
import com.asecon.enterpriseiq.dto.WorkforceSummaryDto;
import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.model.WorkforceImport;
import com.asecon.enterpriseiq.model.WorkforceImportKind;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.asecon.enterpriseiq.repo.WorkforceImportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class WorkforceGestorMatchingTest {
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
    void marksAmbiguousNormalizedGestorAsReview() throws IOException {
        Company company = new Company();
        company.setName("Empresa test");
        company.setPlan(Plan.GOLD);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

        WorkforceSummaryDto workforceSummary = new WorkforceSummaryDto(
            new WorkforceKpiDto(2, 2, 2, 0, 300.0, 12.0, 600.0),
            List.of(
                workforceGestor("JOSE LUIS", 100.0, 4.0, 200.0, 60.0, 200L),
                workforceGestor("JOSE-LUIS", 200.0, 8.0, 400.0, 80.0, 400L)
            ),
            List.of("GESTOR", "MINUTAS", "PROMEDIO"),
            List.of(2024),
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

        WorkforceImport storedWorkforce = new WorkforceImport();
        storedWorkforce.setCompany(company);
        storedWorkforce.setFilename("workforce.csv");
        storedWorkforce.setRowCount(2);
        storedWorkforce.setWarningCount(0);
        storedWorkforce.setErrorCount(0);
        storedWorkforce.setSummaryJson(objectMapper.writeValueAsString(workforceSummary));
        storedWorkforce.setImportKind(WorkforceImportKind.WORKFORCE);

        when(importRepository.findFirstByCompanyIdAndImportKindOrderByCreatedAtDesc(7L, WorkforceImportKind.WORKFORCE))
            .thenReturn(Optional.of(storedWorkforce));

        List<WorkforceImport> savedImports = new ArrayList<>();
        when(importRepository.save(any(WorkforceImport.class))).thenAnswer(invocation -> {
            WorkforceImport saved = invocation.getArgument(0);
            savedImports.add(saved);
            return saved;
        });

        String csv = String.join("\n",
            "GESTOR,MES,COSTE PERSONAL,SS EMPRESA",
            "Jose Luis,ENERO,1000,300"
        );
        MockMultipartFile file = new MockMultipartFile("file", "labor-costs.csv", "text/csv", csv.getBytes());

        var dto = workforceImportService.importLaborCosts(7L, file, "2026-01");
        assertEquals(1, dto.warningCount());
        assertTrue(dto.errorSummary().contains("match ambiguo"));

        WorkforceLaborCostImportSummaryDto savedSummary = objectMapper.readValue(
            savedImports.get(0).getSummaryJson(),
            WorkforceLaborCostImportSummaryDto.class
        );
        assertEquals(1, savedSummary.summary().gestores().size());
        assertEquals("Jose Luis", savedSummary.summary().gestores().get(0).gestor());
        assertEquals(1300.0, savedSummary.summary().gestores().get(0).costeLaboralAnual(), 0.01);
        assertEquals(1, savedSummary.summary().reviewCount());
        assertEquals("REVIEW", savedSummary.summary().reviews().get(0).status());
        assertTrue(savedSummary.summary().reviews().get(0).detail().contains("ambiguo"));
    }

    private static WorkforceGestorDto workforceGestor(String gestor,
                                                      Double minutas,
                                                      Double carga,
                                                      Double asientos,
                                                      Double pct,
                                                      Long annualSeats) {
        return new WorkforceGestorDto(
            gestor,
            1,
            1,
            0,
            minutas,
            carga,
            carga,
            asientos,
            pct,
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
            Map.of(2024, annualSeats)
        );
    }
}

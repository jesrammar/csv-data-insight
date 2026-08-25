package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.WorkforceGestorDto;
import com.asecon.enterpriseiq.dto.WorkforceGestorHistoryDto;
import com.asecon.enterpriseiq.dto.WorkforceGestorHistoryPointDto;
import com.asecon.enterpriseiq.dto.WorkforceHistoryDto;
import com.asecon.enterpriseiq.dto.WorkforceHistoryPointDto;
import com.asecon.enterpriseiq.dto.WorkforceImportDto;
import com.asecon.enterpriseiq.dto.WorkforceKpiDto;
import com.asecon.enterpriseiq.dto.WorkforceLaborCostCanonicalImportDto;
import com.asecon.enterpriseiq.dto.WorkforceLaborCostComparisonCountsDto;
import com.asecon.enterpriseiq.dto.WorkforceLaborCostComparisonDto;
import com.asecon.enterpriseiq.dto.WorkforceLaborCostGestorHistoryDto;
import com.asecon.enterpriseiq.dto.WorkforceLaborCostGestorHistoryPointDto;
import com.asecon.enterpriseiq.dto.WorkforceLaborCostHistoryDto;
import com.asecon.enterpriseiq.dto.WorkforceLaborCostHistoryPointDto;
import com.asecon.enterpriseiq.dto.WorkforceLaborCostComparisonManagerDto;
import com.asecon.enterpriseiq.dto.WorkforceLaborCostComparisonTotalsDto;
import com.asecon.enterpriseiq.dto.WorkforceLaborCostComparisonWorkerDto;
import com.asecon.enterpriseiq.dto.WorkforceLaborCostGestorDto;
import com.asecon.enterpriseiq.dto.WorkforceLaborCostImportSummaryDto;
import com.asecon.enterpriseiq.dto.WorkforceLaborCostReviewDto;
import com.asecon.enterpriseiq.dto.WorkforceLaborCostWorkerDto;
import com.asecon.enterpriseiq.dto.WorkforceLaborCostsDto;
import com.asecon.enterpriseiq.dto.WorkforceMonthlyCostDto;
import com.asecon.enterpriseiq.dto.WorkforceStatusBreakdownDto;
import com.asecon.enterpriseiq.dto.WorkforceSummaryDto;
import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.WorkforceImport;
import com.asecon.enterpriseiq.model.WorkforceImportKind;
import com.asecon.enterpriseiq.model.WorkforceImportStatus;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.asecon.enterpriseiq.repo.WorkforceImportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.AbstractMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkforceImportService {
    private static final Pattern YEAR_COLUMN_PATTERN = Pattern.compile("^nas(19|20)\\d{2}$");
    private static final Pattern YEAR_TOKEN_PATTERN = Pattern.compile("\\b(19|20)\\d{2}\\b");
    private static final String UNKNOWN_REFERENCE_PERIOD = "UNKNOWN";
    private static final List<String> MONTH_SEQUENCE = List.of(
        "ENERO", "FEBRERO", "MARZO", "ABRIL", "MAYO", "JUNIO",
        "JULIO", "AGOSTO", "SEPTIEMBRE", "OCTUBRE", "NOVIEMBRE", "DICIEMBRE"
    );

    private final CompanyRepository companyRepository;
    private final WorkforceImportRepository importRepository;
    private final TabularFileService tabularFileService;
    private final ObjectMapper objectMapper;

    public WorkforceImportService(CompanyRepository companyRepository,
                                  WorkforceImportRepository importRepository,
                                  TabularFileService tabularFileService,
                                  ObjectMapper objectMapper) {
        this.companyRepository = companyRepository;
        this.importRepository = importRepository;
        this.tabularFileService = tabularFileService;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkforceImportDto importFile(Long companyId, MultipartFile file) throws IOException {
        return importFile(companyId, file, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkforceImportDto importFile(Long companyId, MultipartFile file, String referencePeriod) throws IOException {
        Company company = companyRepository.findById(companyId).orElseThrow();
        TabularFileService.TabularCsv tabularCsv = tabularFileService.toCsv(file, null);
        byte[] bytes = tabularCsv.bytes();
        Charset charset = tabularCsv.charset() == null ? detectCharset(bytes) : tabularCsv.charset();
        String firstLine = stripBom(readFirstLine(bytes, charset));
        if (firstLine == null || firstLine.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fichero vacio");
        }

        int warnings = 0;
        int errors = 0;
        int rowCount = 0;
        List<String> warningDetails = new ArrayList<>();
        WorkforceSummaryDto summary;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), charset));
             CSVParser parser = buildParser(firstLine, reader)) {
            Map<String, String> headers = normalizeHeaders(parser.getHeaderMap().keySet());
            DetectedColumns detected = detectColumns(headers);
            if (!detected.missing().isEmpty()) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Faltan columnas requeridas para Trabajadores: " + String.join(", ", detected.missing())
                );
            }

            Map<String, GestorAccumulator> aggregates = new LinkedHashMap<>();
            for (CSVRecord record : parser) {
                ParsedRow row = parseRow(record, headers, detected, warningDetails);
                if (row.empty()) continue;
                rowCount++;
                warnings += row.warningCount();

                String gestor = isBlank(row.gestor()) ? "SIN GESTOR" : row.gestor().trim();
                GestorAccumulator accumulator = aggregates.computeIfAbsent(gestor, GestorAccumulator::new);
                accumulator.add(row);
            }

            if (rowCount == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se detectaron filas utiles en el fichero de trabajadores");
            }

            summary = buildSummary(detected, aggregates);
        }

        TemporalReference temporalReference = resolveWorkforceReference(referencePeriod, tabularCsv.filename());
        supersedeActiveImport(companyId, WorkforceImportKind.WORKFORCE, temporalReference.referencePeriod());

        WorkforceImport workforceImport = new WorkforceImport();
        workforceImport.setCompany(company);
        workforceImport.setFilename(tabularCsv.filename());
        workforceImport.setCreatedAt(Instant.now());
        workforceImport.setRowCount(rowCount);
        workforceImport.setWarningCount(warnings);
        workforceImport.setErrorCount(errors);
        workforceImport.setErrorSummary(warningDetails.isEmpty() ? null : String.join(" | ", warningDetails));
        workforceImport.setSummaryJson(objectMapper.writeValueAsString(summary));
        workforceImport.setImportKind(WorkforceImportKind.WORKFORCE);
        applyTemporalReference(workforceImport, temporalReference);
        workforceImport.setImportStatus(WorkforceImportStatus.ACTIVE);
        WorkforceImport saved = importRepository.save(workforceImport);
        return toDto(saved, summary.detectedColumns(), summary.activityYears(), versionNumber(saved));
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkforceImportDto importLaborCosts(Long companyId, MultipartFile file) throws IOException {
        return importLaborCosts(companyId, file, (String) null);
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkforceImportDto importLaborCosts(Long companyId, MultipartFile file, Integer referenceYear) throws IOException {
        return importLaborCosts(companyId, file, referenceYear == null ? null : String.valueOf(referenceYear));
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkforceImportDto importLaborCosts(Long companyId, MultipartFile file, String referencePeriod) throws IOException {
        Company company = companyRepository.findById(companyId).orElseThrow();
        TemporalReference explicitReference = parseWorkforceReference(referencePeriod);
        WorkforceContext workforceContext = resolveWorkforceContextForLaborCosts(companyId, explicitReference);
        WorkforceSummaryDto workforceSummary = workforceContext.summary();

        TabularFileService.TabularCsv tabularCsv = tabularFileService.toCsv(file, null);
        byte[] bytes = tabularCsv.bytes();
        Charset charset = tabularCsv.charset() == null ? detectCharset(bytes) : tabularCsv.charset();
        String firstLine = stripBom(readFirstLine(bytes, charset));
        if (firstLine == null || firstLine.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fichero vacio");
        }

        Map<String, List<String>> workforceLookup = buildWorkforceGestorLookup(workforceSummary.gestores());
        int warnings = 0;
        int errors = 0;
        int rowCount = 0;
        List<String> warningDetails = new ArrayList<>();
        WorkforceLaborCostImportSummaryDto summary;
        TemporalCoverageAccumulator coverage = new TemporalCoverageAccumulator(explicitReference);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), charset));
             CSVParser parser = buildParser(firstLine, reader)) {
            Map<String, String> headers = normalizeHeaders(parser.getHeaderMap().keySet());
            DetectedLaborCostColumns detected = detectLaborCostColumns(headers);
            if (!detected.missing().isEmpty()) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Faltan columnas requeridas para Costes laborales: " + String.join(", ", detected.missing())
                );
            }
            coverage.registerHeaderYear(detected.detectedYear());

            Map<String, CanonicalWorkerAccumulator> workers = new LinkedHashMap<>();

            for (CSVRecord record : parser) {
                ParsedLaborCostRow row = parseLaborCostRow(record, headers, detected, warningDetails, coverage);
                if (row.empty()) continue;
                rowCount++;
                warnings += row.warningCount();

                ResolvedWorkerIdentity workerIdentity = resolveWorkerIdentity(row);
                ResolvedGestor resolved = resolveGestor(row.sourceGestor(), workforceLookup);
                String workerKey = workerAggregationKey(workerIdentity, row, resolved);
                CanonicalWorkerAccumulator accumulator = workers.computeIfAbsent(
                    workerKey,
                    ignored -> new CanonicalWorkerAccumulator(workerIdentity, row, resolved)
                );
                accumulator.addRow(row, workerIdentity, resolved);
                if (!resolved.matched() || workerIdentity.review()) {
                    warnings++;
                    String detail = mergeDetails(workerIdentity.detail(), resolved.detail());
                    if (detail != null && warningDetails.size() < 5) {
                        warningDetails.add("Fila " + (record.getRecordNumber() + 1) + ": " + detail);
                    }
                }
            }

            if (rowCount == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se detectaron filas utiles en el fichero de costes laborales");
            }

            summary = buildLaborCostSummary(detected, workers, workforceContext.imported());
        }

        TemporalReference temporalReference = coverage.toTemporalReference(warningDetails);
        supersedeActiveImport(companyId, WorkforceImportKind.LABOR_COSTS, temporalReference.referencePeriod());

        WorkforceImport workforceImport = new WorkforceImport();
        workforceImport.setCompany(company);
        workforceImport.setFilename(tabularCsv.filename());
        workforceImport.setCreatedAt(Instant.now());
        workforceImport.setRowCount(rowCount);
        workforceImport.setWarningCount(warnings);
        workforceImport.setErrorCount(errors);
        workforceImport.setErrorSummary(warningDetails.isEmpty() ? null : String.join(" | ", warningDetails));
        workforceImport.setSummaryJson(objectMapper.writeValueAsString(summary));
        workforceImport.setImportKind(WorkforceImportKind.LABOR_COSTS);
        applyTemporalReference(workforceImport, temporalReference);
        workforceImport.setImportStatus(WorkforceImportStatus.ACTIVE);
        WorkforceImport saved = importRepository.save(workforceImport);
        return toDto(saved, summary.detectedColumns(), List.of(), versionNumber(saved));
    }

    @Transactional(readOnly = true)
    public WorkforceImportDto getLatestImport(Long companyId) {
        return optional(importRepository.findFirstByCompanyIdAndImportKindAndImportStatusOrderByCreatedAtDesc(
                companyId,
                WorkforceImportKind.WORKFORCE,
                WorkforceImportStatus.ACTIVE
            ))
            .or(() -> optional(importRepository.findFirstByCompanyIdAndImportKindOrderByCreatedAtDesc(companyId, WorkforceImportKind.WORKFORCE)))
            .flatMap(imp -> parseWorkforceSummary(imp).map(summary -> toDto(imp, summary.detectedColumns(), summary.activityYears(), versionNumber(imp))))
            .orElse(null);
    }

    @Transactional(readOnly = true)
    public WorkforceImportDto getLatestLaborCostsImport(Long companyId) {
        return optional(importRepository.findFirstByCompanyIdAndImportKindAndImportStatusOrderByCreatedAtDesc(
                companyId,
                WorkforceImportKind.LABOR_COSTS,
                WorkforceImportStatus.ACTIVE
            ))
            .or(() -> optional(importRepository.findFirstByCompanyIdAndImportKindOrderByCreatedAtDesc(companyId, WorkforceImportKind.LABOR_COSTS)))
            .flatMap(imp -> parseLaborCostImportSummary(imp).map(summary -> toDto(imp, summary.detectedColumns(), List.of(), versionNumber(imp))))
            .orElse(null);
    }

    @Transactional(readOnly = true)
    public WorkforceSummaryDto getSummary(Long companyId) {
        return getSummary(companyId, null);
    }

    @Transactional(readOnly = true)
    public WorkforceSummaryDto getSummary(Long companyId, Long workforceImportId) {
        List<WorkforceImport> workforceImports = listImports(companyId, WorkforceImportKind.WORKFORCE);
        List<WorkforceImport> laborCostImports = listImports(companyId, WorkforceImportKind.LABOR_COSTS);

        WorkforceImport selectedWorkforceImport = resolveSelectedWorkforceImport(companyId, workforceImportId, workforceImports).orElse(null);
        WorkforceSummaryDto workforceSummary = selectedWorkforceImport == null
            ? emptySummary()
            : parseWorkforceSummary(selectedWorkforceImport).orElse(emptySummary());

        WorkforceImport activeLaborImport = resolveSelectedLaborCostImport(companyId, null, null, laborCostImports).orElse(null);
        WorkforceLaborCostImportSummaryDto activeLaborImportSummary = activeLaborImport == null
            ? null
            : parseLaborCostImportSummary(activeLaborImport).orElse(null);

        WorkforceImport compatibleLaborImport = resolveCompatibleLaborImport(selectedWorkforceImport, workforceImports, laborCostImports).orElse(null);
        WorkforceLaborCostImportSummaryDto compatibleLaborImportSummary = compatibleLaborImport == null
            ? null
            : parseLaborCostImportSummary(compatibleLaborImport).orElse(null);

        WorkforceLaborCostsDto snapshotLaborCosts = selectedWorkforceImport == null
            ? activeLaborImportSummary == null ? null : activeLaborImportSummary.summary()
            : projectLaborCostsForWorkforce(selectedWorkforceImport, workforceSummary, compatibleLaborImport, compatibleLaborImportSummary);
        WorkforceImport snapshotLaborImport = selectedWorkforceImport == null
            ? activeLaborImport
            : snapshotLaborCosts == null ? null : compatibleLaborImport;

        WorkforceSummaryDto economicSummary = withLaborCosts(workforceSummary, snapshotLaborCosts);
        WorkforceSummaryDto combined = combineSummary(
            economicSummary,
            selectedWorkforceImport == null ? null : snapshotLaborCosts
        );
        WorkforceSummaryDto contextualized = withImportContext(
            combined,
            selectedWorkforceImport,
            snapshotLaborImport,
            selectedWorkforceImport == null ? null : snapshotLaborImport,
            workforceImports,
            laborCostImports
        );
        return withInsights(withHistory(companyId, contextualized));
    }

    @Transactional(readOnly = true)
    public WorkforceLaborCostComparisonDto getLaborCostComparison(Long companyId,
                                                                  String basePeriod,
                                                                  String comparisonPeriod,
                                                                  Long baseImportId,
                                                                  Long comparisonImportId) {
        String normalizedBasePeriod = normalizeReferencePeriod(basePeriod);
        String normalizedComparisonPeriod = normalizeReferencePeriod(comparisonPeriod);
        boolean sameImportSelection = baseImportId != null && comparisonImportId != null && Objects.equals(baseImportId, comparisonImportId);
        if (Objects.equals(normalizedBasePeriod, normalizedComparisonPeriod) && !sameImportSelection) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Los periodos base y comparado deben ser distintos.");
        }

        List<WorkforceImport> laborCostImports = listImports(companyId, WorkforceImportKind.LABOR_COSTS);
        WorkforceImport baseImport = resolveSelectedLaborCostImport(companyId, baseImportId, basePeriod, laborCostImports).orElse(null);
        WorkforceImport comparisonImport = resolveSelectedLaborCostImport(companyId, comparisonImportId, comparisonPeriod, laborCostImports).orElse(null);
        if (baseImport == null || comparisonImport == null) {
            return emptyComparison(
                normalizedBasePeriod,
                normalizedComparisonPeriod,
                baseImport,
                comparisonImport,
                "MISSING_IMPORT",
                "Falta al menos una importación activa de costes laborales para los periodos seleccionados."
            );
        }

        WorkforceLaborCostCanonicalImportDto baseCanonical = parseLaborCostImportSummary(baseImport)
            .map(WorkforceLaborCostImportSummaryDto::canonical)
            .orElse(null);
        WorkforceLaborCostCanonicalImportDto comparisonCanonical = parseLaborCostImportSummary(comparisonImport)
            .map(WorkforceLaborCostImportSummaryDto::canonical)
            .orElse(null);
        if (baseCanonical == null || comparisonCanonical == null) {
            return emptyComparison(
                normalizedBasePeriod,
                normalizedComparisonPeriod,
                baseImport,
                comparisonImport,
                "LEGACY_IMPORT",
                "Alguna importación de costes no dispone de detalle canónico por trabajador. Reimpórtala para activar la comparación."
            );
        }

        String baseCurrency = normalizeCurrencyCode(baseCanonical.currency());
        String comparisonCurrency = normalizeCurrencyCode(comparisonCanonical.currency());
        if (baseCanonical.multiCurrency() || comparisonCanonical.multiCurrency()) {
            return emptyComparison(
                normalizedBasePeriod,
                normalizedComparisonPeriod,
                baseImport,
                comparisonImport,
                "CURRENCY_MISMATCH",
                "No se puede comparar porque alguno de los periodos contiene múltiples monedas."
            );
        }
        if (baseCurrency != null && comparisonCurrency != null && !Objects.equals(baseCurrency, comparisonCurrency)) {
            return emptyComparison(
                normalizedBasePeriod,
                normalizedComparisonPeriod,
                baseImport,
                comparisonImport,
                "CURRENCY_MISMATCH",
                "No se puede comparar porque los periodos usan monedas distintas y no hay conversión trazable."
            );
        }

        return buildLaborCostComparison(
            normalizedBasePeriod,
            normalizedComparisonPeriod,
            baseImport,
            comparisonImport,
            baseCanonical,
            comparisonCanonical,
            firstNonBlank(baseCurrency, comparisonCurrency)
        );
    }

    private Optional<WorkforceSummaryDto> getActiveWorkforceSummary(Long companyId) {
        return optional(importRepository.findFirstByCompanyIdAndImportKindAndImportStatusOrderByCreatedAtDesc(
                companyId,
                WorkforceImportKind.WORKFORCE,
                WorkforceImportStatus.ACTIVE
            ))
            .or(() -> optional(importRepository.findFirstByCompanyIdAndImportKindOrderByCreatedAtDesc(companyId, WorkforceImportKind.WORKFORCE)))
            .flatMap(this::parseWorkforceSummary);
    }

    private WorkforceContext resolveWorkforceContextForLaborCosts(Long companyId, TemporalReference explicitReference) {
        List<WorkforceImport> workforceImports = listImports(companyId, WorkforceImportKind.WORKFORCE);
        WorkforceImport selectedImport = resolveWorkforceImportForLaborCosts(explicitReference, workforceImports).orElse(null);
        WorkforceSummaryDto summary = selectedImport == null
            ? null
            : parseWorkforceSummary(selectedImport)
                .filter(parsed -> parsed.gestores() != null && !parsed.gestores().isEmpty())
                .orElse(null);
        if (summary == null) {
            summary = getActiveWorkforceSummary(companyId)
                .filter(parsed -> parsed.gestores() != null && !parsed.gestores().isEmpty())
                .orElse(emptySummary());
            if (selectedImport == null) {
                selectedImport = resolveSelectedWorkforceImport(companyId, null, workforceImports).orElse(null);
            }
        }
        return new WorkforceContext(selectedImport, summary);
    }

    private List<WorkforceImport> listImports(Long companyId, WorkforceImportKind importKind) {
        List<WorkforceImport> imports = importRepository.findByCompanyIdAndImportKindOrderByCreatedAtDesc(companyId, importKind);
        if (imports != null && !imports.isEmpty()) {
            return imports;
        }
        List<WorkforceImport> topImports = importRepository.findTop12ByCompanyIdAndImportKindOrderByCreatedAtDesc(companyId, importKind);
        if (topImports != null && !topImports.isEmpty()) {
            return topImports;
        }
        return importRepository.findFirstByCompanyIdAndImportKindOrderByCreatedAtDesc(companyId, importKind)
            .map(List::of)
            .orElse(List.of());
    }

    private Optional<WorkforceImport> resolveSelectedWorkforceImport(Long companyId,
                                                                     Long workforceImportId,
                                                                     List<WorkforceImport> workforceImports) {
        if (workforceImportId != null) {
            Optional<WorkforceImport> stored = optional(
                importRepository.findByIdAndCompanyIdAndImportKind(workforceImportId, companyId, WorkforceImportKind.WORKFORCE)
            );
            if (stored.isPresent()) {
                return stored;
            }
            return workforceImports.stream()
                .filter(imported -> Objects.equals(imported.getId(), workforceImportId))
                .findFirst();
        }
        return workforceImports.stream()
            .filter(imported -> imported.getImportStatus() == WorkforceImportStatus.ACTIVE)
            .max(WorkforceImportService::compareImportsByReference)
            .or(() -> workforceImports.stream().max(WorkforceImportService::compareImportsByReference));
    }

    private Optional<WorkforceImport> resolveSelectedLaborCostImport(Long companyId,
                                                                     Long laborCostImportId,
                                                                     String referencePeriod,
                                                                     List<WorkforceImport> laborCostImports) {
        if (laborCostImportId != null) {
            Optional<WorkforceImport> stored = optional(
                importRepository.findByIdAndCompanyIdAndImportKind(laborCostImportId, companyId, WorkforceImportKind.LABOR_COSTS)
            );
            if (stored.isPresent()) {
                return stored;
            }
            return laborCostImports.stream()
                .filter(imported -> Objects.equals(imported.getId(), laborCostImportId))
                .findFirst();
        }
        String normalizedPeriod = normalizeReferencePeriod(referencePeriod);
        if (!UNKNOWN_REFERENCE_PERIOD.equals(normalizedPeriod)) {
            Optional<WorkforceImport> activeExact = laborCostImports.stream()
                .filter(imported -> Objects.equals(normalizeReferencePeriod(imported.getReferencePeriod()), normalizedPeriod))
                .filter(imported -> imported.getImportStatus() == WorkforceImportStatus.ACTIVE)
                .findFirst();
            if (activeExact.isPresent()) {
                return activeExact;
            }
            Optional<WorkforceImport> latestExact = laborCostImports.stream()
                .filter(imported -> Objects.equals(normalizeReferencePeriod(imported.getReferencePeriod()), normalizedPeriod))
                .findFirst();
            if (latestExact.isPresent()) {
                return latestExact;
            }
        }
        return laborCostImports.stream()
            .filter(imported -> imported.getImportStatus() == WorkforceImportStatus.ACTIVE)
            .max(WorkforceImportService::compareImportsByReference)
            .or(() -> laborCostImports.stream().max(WorkforceImportService::compareImportsByReference));
    }

    private Optional<WorkforceImport> resolveWorkforceImportForLaborCosts(TemporalReference explicitReference,
                                                                          List<WorkforceImport> workforceImports) {
        if (workforceImports == null || workforceImports.isEmpty()) {
            return Optional.empty();
        }
        if (explicitReference != null && explicitReference.known()) {
            String targetReference = normalizeReferencePeriod(explicitReference.referencePeriod());
            Optional<WorkforceImport> activeExact = workforceImports.stream()
                .filter(imported -> Objects.equals(normalizeReferencePeriod(imported.getReferencePeriod()), targetReference))
                .filter(imported -> imported.getImportStatus() == WorkforceImportStatus.ACTIVE)
                .findFirst();
            if (activeExact.isPresent()) {
                return activeExact;
            }
            Optional<WorkforceImport> latestExact = workforceImports.stream()
                .filter(imported -> Objects.equals(normalizeReferencePeriod(imported.getReferencePeriod()), targetReference))
                .findFirst();
            if (latestExact.isPresent()) {
                return latestExact;
            }
        }
        return workforceImports.stream()
            .filter(imported -> imported.getImportStatus() == WorkforceImportStatus.ACTIVE)
            .max(WorkforceImportService::compareImportsByReference)
            .or(() -> workforceImports.stream().max(WorkforceImportService::compareImportsByReference));
    }

    private Optional<WorkforceImport> resolveCompatibleLaborImport(WorkforceImport selectedWorkforceImport,
                                                                   List<WorkforceImport> workforceImports,
                                                                   List<WorkforceImport> laborCostImports) {
        if (selectedWorkforceImport == null) {
            return Optional.empty();
        }
        TemporalReference workforceReference = temporalReferenceOf(selectedWorkforceImport);
        if (!workforceReference.known()) {
            return Optional.empty();
        }

        int targetVersion = versionNumber(selectedWorkforceImport, workforceImports);
        List<WorkforceImport> compatible = laborCostImports.stream()
            .filter(imported -> isCompatible(workforceReference, temporalReferenceOf(imported)))
            .toList();
        if (compatible.isEmpty()) {
            return Optional.empty();
        }

        List<WorkforceImport> exactPeriod = compatible.stream()
            .filter(imported -> Objects.equals(imported.getReferencePeriod(), selectedWorkforceImport.getReferencePeriod()))
            .toList();
        Optional<WorkforceImport> sameVersionExact = exactPeriod.stream()
            .filter(imported -> versionNumber(imported, laborCostImports) == targetVersion)
            .findFirst();
        if (sameVersionExact.isPresent()) {
            return sameVersionExact;
        }

        Optional<WorkforceImport> activeExact = exactPeriod.stream()
            .filter(imported -> imported.getImportStatus() == WorkforceImportStatus.ACTIVE)
            .findFirst();
        if (activeExact.isPresent()) {
            return activeExact;
        }

        Comparator<WorkforceImport> compatibilityOrder = Comparator
            .comparingInt((WorkforceImport imported) -> coverageSpanMonths(temporalReferenceOf(imported)))
            .thenComparing((WorkforceImport imported) -> imported.getImportStatus() == WorkforceImportStatus.ACTIVE ? 0 : 1)
            .thenComparing(WorkforceImport::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(WorkforceImport::getId, Comparator.nullsLast(Comparator.reverseOrder()));

        Optional<WorkforceImport> sameVersionCompatible = compatible.stream()
            .filter(imported -> versionNumber(imported, laborCostImports) == targetVersion)
            .min(compatibilityOrder);
        if (sameVersionCompatible.isPresent()) {
            return sameVersionCompatible;
        }

        return compatible.stream().min(compatibilityOrder);
    }

    private WorkforceSummaryDto withImportContext(WorkforceSummaryDto summary,
                                                  WorkforceImport workforceImport,
                                                  WorkforceImport laborCostsImport,
                                                  WorkforceImport pairedLaborCostsImport,
                                                  List<WorkforceImport> workforceImports,
                                                  List<WorkforceImport> laborCostImports) {
        WorkforceImportDto workforceImportDto = workforceImport == null
            ? null
            : toDto(workforceImport, versionNumber(workforceImport, workforceImports));
        WorkforceImportDto laborCostsImportDto = adaptSnapshotLaborImportDto(
            workforceImport,
            laborCostsImport,
            summary.laborCosts(),
            laborCostImports
        );
        WorkforceImportDto pairedLaborCostsImportDto = adaptSnapshotLaborImportDto(
            workforceImport,
            pairedLaborCostsImport,
            summary.pairedLaborCosts(),
            laborCostImports
        );
        return new WorkforceSummaryDto(
            summary.kpis(),
            summary.gestores(),
            summary.detectedColumns(),
            summary.activityYears(),
            summary.laborCosts(),
            summary.pairedLaborCosts(),
            summary.history(),
            summary.laborCostsHistory(),
            summary.insights(),
            workforceImportDto,
            laborCostsImportDto,
            pairedLaborCostsImportDto,
            toDtoList(workforceImports),
            toDtoList(laborCostImports)
        );
    }

    private WorkforceSummaryDto withLaborCosts(WorkforceSummaryDto summary, WorkforceLaborCostsDto laborCosts) {
        return new WorkforceSummaryDto(
            summary.kpis(),
            summary.gestores(),
            summary.detectedColumns(),
            summary.activityYears(),
            laborCosts,
            summary.pairedLaborCosts(),
            summary.history(),
            summary.laborCostsHistory(),
            summary.insights(),
            summary.workforceImport(),
            summary.laborCostsImport(),
            summary.pairedLaborCostsImport(),
            summary.workforceImports(),
            summary.laborCostImports()
        );
    }

    private WorkforceSummaryDto combineSummary(WorkforceSummaryDto base, WorkforceLaborCostsDto laborCosts) {
        if (laborCosts == null || laborCosts.gestores() == null || laborCosts.gestores().isEmpty()) {
            List<WorkforceGestorDto> gestores = base.gestores() == null ? List.of() : base.gestores().stream()
                .map(row -> withCosts(row, null))
                .toList();
            return new WorkforceSummaryDto(
                base.kpis(),
                gestores,
                base.detectedColumns(),
                base.activityYears(),
                base.laborCosts(),
                laborCosts,
                base.history(),
                base.laborCostsHistory(),
                base.insights(),
                base.workforceImport(),
                base.laborCostsImport(),
                base.pairedLaborCostsImport(),
                base.workforceImports(),
                base.laborCostImports()
            );
        }

        Map<String, WorkforceLaborCostGestorDto> costByGestor = new LinkedHashMap<>();
        for (WorkforceLaborCostGestorDto row : laborCosts.gestores()) {
            if (row == null || isBlank(row.gestor())) continue;
            costByGestor.put(row.gestor().trim(), row);
        }

        List<WorkforceGestorDto> gestores = base.gestores() == null ? List.of() : base.gestores().stream()
            .map(row -> withCosts(row, costByGestor.get(row.gestor())))
            .toList();
        return new WorkforceSummaryDto(
            base.kpis(),
            gestores,
            base.detectedColumns(),
            base.activityYears(),
            base.laborCosts(),
            laborCosts,
            base.history(),
            base.laborCostsHistory(),
            base.insights(),
            base.workforceImport(),
            base.laborCostsImport(),
            base.pairedLaborCostsImport(),
            base.workforceImports(),
            base.laborCostImports()
        );
    }

    private WorkforceSummaryDto withHistory(Long companyId, WorkforceSummaryDto summary) {
        return new WorkforceSummaryDto(
            summary.kpis(),
            summary.gestores(),
            summary.detectedColumns(),
            summary.activityYears(),
            summary.laborCosts(),
            summary.pairedLaborCosts(),
            buildHistory(companyId),
            buildLaborCostHistory(companyId),
            summary.insights(),
            summary.workforceImport(),
            summary.laborCostsImport(),
            summary.pairedLaborCostsImport(),
            summary.workforceImports(),
            summary.laborCostImports()
        );
    }

    private WorkforceSummaryDto withInsights(WorkforceSummaryDto summary) {
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

    private WorkforceHistoryDto buildHistory(Long companyId) {
        List<WorkforceImport> storedImports = listImports(companyId, WorkforceImportKind.WORKFORCE);
        if (storedImports == null || storedImports.isEmpty()) {
            return new WorkforceHistoryDto(List.of(), List.of());
        }

        List<Map.Entry<WorkforceImport, WorkforceSummaryDto>> snapshots = storedImports
            .stream()
            .map(workforceImport -> parseWorkforceSummary(workforceImport)
                .map(summary -> Map.entry(workforceImport, summary)))
            .flatMap(Optional::stream)
            .sorted(Comparator.comparing(Map.Entry<WorkforceImport, WorkforceSummaryDto>::getKey, WorkforceImportService::compareImportsByReference))
            .toList();

        if (snapshots.isEmpty()) {
            return new WorkforceHistoryDto(List.of(), List.of());
        }

        List<WorkforceHistoryPointDto> imports = snapshots.stream()
            .map(entry -> toHistoryPoint(entry.getKey(), entry.getValue()))
            .toList();

        Map<String, List<WorkforceGestorHistoryPointDto>> pointsByGestor = new LinkedHashMap<>();
        for (Map.Entry<WorkforceImport, WorkforceSummaryDto> snapshot : snapshots) {
            WorkforceImport workforceImport = snapshot.getKey();
            WorkforceSummaryDto summary = snapshot.getValue();
            if (summary.gestores() == null) continue;
            for (WorkforceGestorDto row : summary.gestores()) {
                if (row == null || isBlank(row.gestor())) continue;
                pointsByGestor.computeIfAbsent(row.gestor(), ignored -> new ArrayList<>())
                    .add(toGestorHistoryPoint(workforceImport, row));
            }
        }

        List<WorkforceGestorHistoryDto> gestores = pointsByGestor.entrySet().stream()
            .map(entry -> new WorkforceGestorHistoryDto(entry.getKey(), List.copyOf(entry.getValue())))
            .sorted(Comparator.comparingLong((WorkforceGestorHistoryDto row) -> latestClients(row.points())).reversed()
                .thenComparing(WorkforceGestorHistoryDto::gestor, String.CASE_INSENSITIVE_ORDER))
            .toList();

        return new WorkforceHistoryDto(imports, gestores);
    }

    private static WorkforceHistoryPointDto toHistoryPoint(WorkforceImport workforceImport, WorkforceSummaryDto summary) {
        WorkforceKpiDto kpis = summary == null ? null : summary.kpis();
        return new WorkforceHistoryPointDto(
            workforceImport.getId(),
            workforceImport.getReferencePeriod(),
            temporalReferenceOf(workforceImport).label(),
            workforceImport.getFilename(),
            workforceImport.getCreatedAt(),
            kpis == null ? 0 : kpis.totalClients(),
            kpis == null ? 0 : kpis.activeClients(),
            kpis == null ? 0 : kpis.inactiveClients(),
            kpis == null ? null : kpis.totalMinutas(),
            kpis == null ? null : kpis.totalCarga(),
            kpis == null ? null : kpis.totalVolumenAsientos()
        );
    }

    private static WorkforceGestorHistoryPointDto toGestorHistoryPoint(WorkforceImport workforceImport, WorkforceGestorDto row) {
        return new WorkforceGestorHistoryPointDto(
            workforceImport.getId(),
            workforceImport.getReferencePeriod(),
            temporalReferenceOf(workforceImport).label(),
            workforceImport.getFilename(),
            workforceImport.getCreatedAt(),
            row.totalClients(),
            row.activeClients(),
            row.inactiveClients(),
            row.totalMinutas(),
            row.totalCarga(),
            row.cargaMedia(),
            row.totalVolumenAsientos(),
            row.pctContabilidadMedio()
        );
    }

    private static long latestClients(List<WorkforceGestorHistoryPointDto> points) {
        if (points == null || points.isEmpty()) return 0;
        return points.get(points.size() - 1).totalClients();
    }

    private WorkforceLaborCostHistoryDto buildLaborCostHistory(Long companyId) {
        List<WorkforceImport> storedImports = listImports(companyId, WorkforceImportKind.LABOR_COSTS);
        if (storedImports == null || storedImports.isEmpty()) {
            return new WorkforceLaborCostHistoryDto(List.of(), List.of());
        }

        List<Map.Entry<WorkforceImport, WorkforceLaborCostsDto>> snapshots = storedImports.stream()
            .map(workforceImport -> parseLaborCostImportSummary(workforceImport)
                .map(summary -> Map.entry(workforceImport, summary.summary())))
            .flatMap(Optional::stream)
            .sorted(Comparator.comparing(Map.Entry<WorkforceImport, WorkforceLaborCostsDto>::getKey, WorkforceImportService::compareImportsByReference))
            .toList();

        if (snapshots.isEmpty()) {
            return new WorkforceLaborCostHistoryDto(List.of(), List.of());
        }

        List<WorkforceLaborCostHistoryPointDto> imports = snapshots.stream()
            .map(entry -> toLaborCostHistoryPoint(entry.getKey(), entry.getValue()))
            .toList();

        Map<String, List<WorkforceLaborCostGestorHistoryPointDto>> pointsByGestor = new LinkedHashMap<>();
        for (Map.Entry<WorkforceImport, WorkforceLaborCostsDto> snapshot : snapshots) {
            WorkforceImport laborImport = snapshot.getKey();
            WorkforceLaborCostsDto laborCosts = snapshot.getValue();
            if (laborCosts == null || laborCosts.gestores() == null) continue;
            for (WorkforceLaborCostGestorDto gestor : laborCosts.gestores()) {
                if (gestor == null || isBlank(gestor.gestor())) continue;
                pointsByGestor.computeIfAbsent(gestor.gestor().trim(), ignored -> new ArrayList<>())
                    .add(toLaborCostGestorHistoryPoint(laborImport, gestor));
            }
        }

        List<WorkforceLaborCostGestorHistoryDto> gestores = pointsByGestor.entrySet().stream()
            .map(entry -> new WorkforceLaborCostGestorHistoryDto(entry.getKey(), List.copyOf(entry.getValue())))
            .sorted(Comparator.comparingDouble((WorkforceLaborCostGestorHistoryDto row) -> latestLaborCost(row.points())).reversed()
                .thenComparing(WorkforceLaborCostGestorHistoryDto::gestor, String.CASE_INSENSITIVE_ORDER))
            .toList();

        return new WorkforceLaborCostHistoryDto(imports, gestores);
    }

    private static WorkforceLaborCostHistoryPointDto toLaborCostHistoryPoint(WorkforceImport laborImport,
                                                                             WorkforceLaborCostsDto summary) {
        return new WorkforceLaborCostHistoryPointDto(
            laborImport.getId(),
            laborImport.getReferencePeriod(),
            temporalReferenceOf(laborImport).label(),
            laborImport.getFilename(),
            laborImport.getCreatedAt(),
            summary == null ? null : summary.totalCostePersonalAnual(),
            summary == null ? null : summary.totalSsEmpresaAnual(),
            summary == null ? null : summary.totalCosteLaboralAnual(),
            summary == null || summary.gestores() == null ? 0 : summary.gestores().size()
        );
    }

    private static WorkforceLaborCostGestorHistoryPointDto toLaborCostGestorHistoryPoint(WorkforceImport laborImport,
                                                                                          WorkforceLaborCostGestorDto gestor) {
        return new WorkforceLaborCostGestorHistoryPointDto(
            laborImport.getReferencePeriod(),
            temporalReferenceOf(laborImport).label(),
            laborImport.getCreatedAt(),
            gestor.costePersonalAnual(),
            gestor.ssEmpresaAnual(),
            gestor.costeLaboralAnual()
        );
    }

    private static double latestLaborCost(List<WorkforceLaborCostGestorHistoryPointDto> points) {
        if (points == null || points.isEmpty()) return 0d;
        Double latest = points.get(points.size() - 1).costeLaboralAnual();
        return latest == null ? 0d : latest;
    }

    private WorkforceLaborCostsDto projectLaborCostsForWorkforce(WorkforceImport workforceImport,
                                                                 WorkforceSummaryDto workforceSummary,
                                                                 WorkforceImport laborImport,
                                                                 WorkforceLaborCostImportSummaryDto importSummary) {
        if (importSummary == null || workforceImport == null || laborImport == null) {
            return null;
        }
        TemporalReference workforceReference = temporalReferenceOf(workforceImport);
        TemporalReference laborReference = temporalReferenceOf(laborImport);
        if (!isCompatible(workforceReference, laborReference)) {
            return null;
        }

        Set<String> allowedGestores = workforceSummary == null || workforceSummary.gestores() == null
            ? Set.of()
            : workforceSummary.gestores().stream()
                .map(WorkforceGestorDto::gestor)
                .map(WorkforceImportService::normalizeGestorName)
                .filter(Objects::nonNull)
                .filter(normalized -> !normalized.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (allowedGestores.isEmpty()) {
            return null;
        }

        List<WorkforceLaborCostWorkerDto> matchedWorkers = safeWorkerList(importSummary.canonical()).stream()
            .filter(worker -> worker != null && "MATCHED".equals(worker.matchingState()))
            .filter(worker -> allowedGestores.contains(normalizeGestorName(worker.canonicalGestor())))
            .toList();
        if (matchedWorkers.isEmpty()) {
            return null;
        }

        Map<String, LaborCostAccumulator> aggregatedByGestor = new LinkedHashMap<>();
        LaborCostAccumulator totals = new LaborCostAccumulator("__TOTAL__");
        for (WorkforceLaborCostWorkerDto worker : matchedWorkers) {
            String gestor = worker.canonicalGestor() == null ? null : worker.canonicalGestor().trim();
            if (isBlank(gestor)) {
                continue;
            }
            aggregatedByGestor.computeIfAbsent(gestor, LaborCostAccumulator::new)
                .add(worker.costePersonalMensual(), worker.ssEmpresaMensual());
            totals.add(worker.costePersonalMensual(), worker.ssEmpresaMensual());
        }

        List<WorkforceLaborCostGestorDto> gestorCosts = aggregatedByGestor.values().stream()
            .map(LaborCostAccumulator::toDto)
            .sorted(Comparator.comparing(WorkforceLaborCostGestorDto::costeLaboralAnual, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(WorkforceLaborCostGestorDto::gestor, String.CASE_INSENSITIVE_ORDER))
            .toList();
        if (gestorCosts.isEmpty()) {
            return null;
        }

        WorkforceLaborCostsDto summary = new WorkforceLaborCostsDto(
            totals.toDto().costePersonalAnual(),
            totals.toDto().ssEmpresaAnual(),
            totals.toDto().costeLaboralAnual(),
            averageNullable(gestorCosts.stream().map(WorkforceLaborCostGestorDto::costeLaboralAnual).toList()),
            totals.toMonthlyTotals(),
            gestorCosts,
            0L,
            List.of()
        );

        Integer maxMonth = workforceReference.referenceMonth() != null
            ? workforceReference.referenceMonth()
            : laborReference.endMonth();
        boolean annualMetricsAllowed = workforceReference.referenceMonth() == null && laborReference.annualCoverage();

        List<WorkforceMonthlyCostDto> monthlyTotals = summary.monthlyTotals() == null
            ? List.of()
            : summary.monthlyTotals().stream()
                .filter(row -> monthWithinSnapshot(row.month(), maxMonth))
                .toList();

        List<WorkforceLaborCostGestorDto> gestores = summary.gestores() == null
            ? List.of()
            : summary.gestores().stream()
                .map(row -> projectLaborCostGestor(row, maxMonth, annualMetricsAllowed))
                .toList();

        Double totalCostePersonal = annualMetricsAllowed
            ? summary.totalCostePersonalAnual()
            : sumMonthRows(monthlyTotals, WorkforceMonthlyCostDto::costePersonal);
        Double totalSsEmpresa = annualMetricsAllowed
            ? summary.totalSsEmpresaAnual()
            : sumMonthRows(monthlyTotals, WorkforceMonthlyCostDto::ssEmpresa);
        Double totalCosteLaboral = annualMetricsAllowed
            ? summary.totalCosteLaboralAnual()
            : sumMonthRows(monthlyTotals, WorkforceMonthlyCostDto::costeTotal);
        Double costeMedioPorGestor = annualMetricsAllowed
            ? summary.costeMedioPorGestor()
            : averageNullable(gestores.stream().map(WorkforceLaborCostGestorDto::costeLaboralAnual).toList());

        return new WorkforceLaborCostsDto(
            totalCostePersonal,
            totalSsEmpresa,
            totalCosteLaboral,
            costeMedioPorGestor,
            monthlyTotals,
            gestores,
            summary.reviewCount(),
            summary.reviews()
        );
    }

    private WorkforceImportDto adaptSnapshotLaborImportDto(WorkforceImport workforceImport,
                                                           WorkforceImport laborImport,
                                                           WorkforceLaborCostsDto snapshotLaborCosts,
                                                           List<WorkforceImport> laborCostImports) {
        if (laborImport == null || snapshotLaborCosts == null) {
            return null;
        }
        WorkforceImportDto base = toDto(laborImport, versionNumber(laborImport, laborCostImports));
        if (workforceImport == null) {
            return base;
        }

        TemporalReference projectedReference = projectLaborReferenceForSnapshot(
            temporalReferenceOf(workforceImport),
            temporalReferenceOf(laborImport)
        );
        if (projectedReference == null || !projectedReference.known()) {
            return base;
        }

        return new WorkforceImportDto(
            base.id(),
            base.companyId(),
            base.importKind(),
            base.filename(),
            base.createdAt(),
            base.rowCount(),
            base.warningCount(),
            base.errorCount(),
            base.errorSummary(),
            projectedReference.referencePeriod(),
            projectedReference.label(),
            base.status(),
            projectedReference.referenceYear(),
            projectedReference.referenceMonth(),
            projectedReference.startMonth(),
            projectedReference.endMonth(),
            projectedReference.annualCoverage(),
            base.versionNumber(),
            base.detectedColumns(),
            base.activityYears()
        );
    }

    private static TemporalReference projectLaborReferenceForSnapshot(TemporalReference workforceReference,
                                                                      TemporalReference laborReference) {
        if (!isCompatible(workforceReference, laborReference)) {
            return unknownTemporalReference();
        }
        if (workforceReference == null || laborReference == null || !workforceReference.known() || !laborReference.known()) {
            return unknownTemporalReference();
        }
        if (workforceReference.referenceMonth() == null || laborReference.startMonth() == null || laborReference.endMonth() == null) {
            return laborReference;
        }

        int startMonth = laborReference.startMonth();
        int endMonth = Math.min(workforceReference.referenceMonth(), laborReference.endMonth());
        String referencePeriod = workforceReference.referencePeriod();
        String label = referenceLabel(workforceReference.referenceYear(), startMonth, endMonth, false);
        return new TemporalReference(
            referencePeriod,
            workforceReference.referenceYear(),
            endMonth,
            startMonth,
            endMonth,
            false,
            label,
            true
        );
    }

    private WorkforceLaborCostGestorDto projectLaborCostGestor(WorkforceLaborCostGestorDto row,
                                                               Integer maxMonth,
                                                               boolean annualMetricsAllowed) {
        Map<String, Double> personal = filterMonthMap(row.costePersonalMensual(), maxMonth);
        Map<String, Double> ss = filterMonthMap(row.ssEmpresaMensual(), maxMonth);
        Map<String, Double> total = filterMonthMap(row.costeTotalMensual(), maxMonth);
        Double costePersonalAnual = annualMetricsAllowed ? row.costePersonalAnual() : sumNullable(List.copyOf(personal.values()));
        Double ssEmpresaAnual = annualMetricsAllowed ? row.ssEmpresaAnual() : sumNullable(List.copyOf(ss.values()));
        Double costeLaboralAnual = annualMetricsAllowed ? row.costeLaboralAnual() : sumNullable(List.copyOf(total.values()));
        return new WorkforceLaborCostGestorDto(
            row.gestor(),
            personal,
            ss,
            total,
            costePersonalAnual,
            ssEmpresaAnual,
            costeLaboralAnual
        );
    }

    private static Map<String, Double> filterMonthMap(Map<String, Double> source, Integer maxMonth) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : source.entrySet()) {
            if (!monthWithinSnapshot(entry.getKey(), maxMonth)) {
                continue;
            }
            filtered.put(entry.getKey(), entry.getValue());
        }
        return filtered;
    }

    private static Double sumMonthRows(List<WorkforceMonthlyCostDto> rows,
                                       java.util.function.Function<WorkforceMonthlyCostDto, Double> getter) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return sumNullable(rows.stream().map(getter).toList());
    }

    private static boolean monthWithinSnapshot(String month, Integer maxMonth) {
        if (month == null) {
            return false;
        }
        if (maxMonth == null) {
            return true;
        }
        Integer monthIndex = monthIndex(month);
        return monthIndex != null && monthIndex <= maxMonth;
    }

    private static boolean isCompatible(TemporalReference workforceReference, TemporalReference laborReference) {
        if (workforceReference == null || laborReference == null || !workforceReference.known() || !laborReference.known()) {
            return false;
        }
        if (!Objects.equals(workforceReference.referenceYear(), laborReference.referenceYear())) {
            return false;
        }
        if (workforceReference.referenceMonth() != null) {
            return laborReference.startMonth() != null
                && laborReference.endMonth() != null
                && workforceReference.referenceMonth() >= laborReference.startMonth()
                && workforceReference.referenceMonth() <= laborReference.endMonth();
        }
        if (workforceReference.annualCoverage()) {
            return laborReference.annualCoverage();
        }
        return Objects.equals(workforceReference.referencePeriod(), laborReference.referencePeriod());
    }

    private static int coverageSpanMonths(TemporalReference reference) {
        if (reference == null || reference.startMonth() == null || reference.endMonth() == null) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, reference.endMonth() - reference.startMonth() + 1);
    }

    private WorkforceGestorDto withCosts(WorkforceGestorDto row, WorkforceLaborCostGestorDto costs) {
        Double costePersonalAnual = costs == null ? null : costs.costePersonalAnual();
        Double ssEmpresaAnual = costs == null ? null : costs.ssEmpresaAnual();
        Double costeLaboralAnual = costs == null ? null : costs.costeLaboralAnual();
        Double costePorCliente = ratio(costeLaboralAnual, row.totalClients());
        Double costePor1000Minutas = ratioPerThousand(costeLaboralAnual, row.totalMinutas());
        Double costePor1000Asientos = ratioPerThousand(costeLaboralAnual, row.totalVolumenAsientos());
        Double minutasPor1000Coste = inversePerThousand(row.totalMinutas(), costeLaboralAnual);
        Double asientosPor1000Coste = inversePerThousand(row.totalVolumenAsientos(), costeLaboralAnual);

        return new WorkforceGestorDto(
            row.gestor(),
            row.totalClients(),
            row.activeClients(),
            row.inactiveClients(),
            row.totalMinutas(),
            row.totalCarga(),
            row.cargaMedia(),
            row.totalVolumenAsientos(),
            row.pctContabilidadMedio(),
            costePersonalAnual,
            ssEmpresaAnual,
            costeLaboralAnual,
            costePorCliente,
            costePor1000Minutas,
            costePor1000Asientos,
            minutasPor1000Coste,
            asientosPor1000Coste,
            row.contModelosOk(),
            row.isIrpfOk(),
            row.ddccOk(),
            row.librosOk(),
            row.contModelosStates(),
            row.isIrpfStates(),
            row.ddccStates(),
            row.librosStates(),
            row.annualSeatTotals()
        );
    }

    private WorkforceLaborCostImportSummaryDto buildLaborCostSummary(DetectedLaborCostColumns detected,
                                                                     Map<String, CanonicalWorkerAccumulator> workers,
                                                                     WorkforceImport matchedWorkforceImport) {
        List<WorkforceLaborCostWorkerDto> canonicalWorkers = workers.values().stream()
            .map(CanonicalWorkerAccumulator::toDto)
            .sorted(Comparator.comparing(WorkforceLaborCostWorkerDto::costeLaboralAnual, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(WorkforceLaborCostWorkerDto::workerLabel, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
            .toList();

        Map<String, LaborCostAccumulator> aggregatedByGestor = new LinkedHashMap<>();
        LaborCostAccumulator totals = new LaborCostAccumulator("__TOTAL__");
        Map<String, ReviewAccumulator> reviews = new LinkedHashMap<>();
        Set<String> currencies = new LinkedHashSet<>();

        for (WorkforceLaborCostWorkerDto worker : canonicalWorkers) {
            String currency = normalizeCurrencyCode(worker.currency());
            if (currency != null) {
                currencies.add(currency);
            }

            EconomicGestor economicGestor = economicGestorOf(worker);
            if (economicGestor != null) {
                LaborCostAccumulator accumulator = aggregatedByGestor.computeIfAbsent(
                    economicGestor.key(),
                    ignored -> new LaborCostAccumulator(economicGestor.label())
                );
                accumulator.add(worker.costePersonalMensual(), worker.ssEmpresaMensual());
                totals.add(worker.costePersonalMensual(), worker.ssEmpresaMensual());
            }

            if (!"MATCHED".equals(worker.matchingState())) {
                String reviewKey = worker.matchingState() + "|" + normalizeGestorName(worker.sourceGestor()) + "|" + normalize(worker.matchingDetail());
                reviews.computeIfAbsent(reviewKey, ignored -> new ReviewAccumulator(
                    worker.sourceGestor(),
                    worker.normalizedGestor(),
                    worker.matchingState(),
                    worker.matchingDetail()
                )).increment();
            }
        }

        List<WorkforceLaborCostGestorDto> gestores = aggregatedByGestor.values().stream()
            .map(LaborCostAccumulator::toDto)
            .sorted(Comparator.comparing(WorkforceLaborCostGestorDto::costeLaboralAnual, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(WorkforceLaborCostGestorDto::gestor, String.CASE_INSENSITIVE_ORDER))
            .toList();

        List<WorkforceMonthlyCostDto> monthlyTotals = totals.toMonthlyTotals();
        WorkforceLaborCostGestorDto totalRow = totals.toDto();
        List<WorkforceLaborCostReviewDto> reviewDtos = reviews.values().stream()
            .sorted(Comparator.comparingLong(ReviewAccumulator::rows).reversed()
                .thenComparing(ReviewAccumulator::sourceGestor, String.CASE_INSENSITIVE_ORDER))
            .map(ReviewAccumulator::toDto)
            .toList();

        WorkforceLaborCostsDto summary = new WorkforceLaborCostsDto(
            totalRow.costePersonalAnual(),
            totalRow.ssEmpresaAnual(),
            totalRow.costeLaboralAnual(),
            averageNullable(gestores.stream().map(WorkforceLaborCostGestorDto::costeLaboralAnual).toList()),
            monthlyTotals,
            gestores,
            reviewDtos.stream().mapToLong(WorkforceLaborCostReviewDto::rows).sum(),
            reviewDtos
        );

        TemporalReference matchedReference = temporalReferenceOf(matchedWorkforceImport);
        WorkforceLaborCostCanonicalImportDto canonical = new WorkforceLaborCostCanonicalImportDto(
            matchedWorkforceImport == null ? null : matchedWorkforceImport.getId(),
            matchedWorkforceImport == null ? null : matchedWorkforceImport.getReferencePeriod(),
            matchedWorkforceImport == null ? null : matchedReference.label(),
            currencies.size() == 1 ? currencies.iterator().next() : null,
            currencies.size() > 1,
            List.copyOf(currencies),
            canonicalWorkers
        );
        return new WorkforceLaborCostImportSummaryDto(summary, detected.detectedLabels(), canonical);
    }

    private static EconomicGestor economicGestorOf(WorkforceLaborCostWorkerDto worker) {
        if (worker == null) {
            return null;
        }
        String key = firstNonBlank(
            normalizeGestorName(worker.canonicalGestor()),
            worker.normalizedGestor(),
            normalizeGestorName(worker.sourceGestor()),
            normalizeGestorName(worker.workerLabel())
        );
        if (isBlank(key)) {
            return null;
        }
        String label = firstNonBlank(
            isBlank(worker.canonicalGestor()) ? null : worker.canonicalGestor().trim(),
            isBlank(worker.sourceGestor()) ? null : worker.sourceGestor().trim(),
            isBlank(worker.workerLabel()) ? null : worker.workerLabel().trim(),
            key
        );
        return new EconomicGestor(key, label);
    }

    private WorkforceLaborCostComparisonDto buildLaborCostComparison(String basePeriod,
                                                                     String comparisonPeriod,
                                                                     WorkforceImport baseImport,
                                                                     WorkforceImport comparisonImport,
                                                                     WorkforceLaborCostCanonicalImportDto baseCanonical,
                                                                     WorkforceLaborCostCanonicalImportDto comparisonCanonical,
                                                                     String currency) {
        List<WorkforceLaborCostWorkerDto> baseWorkers = safeWorkerList(baseCanonical);
        List<WorkforceLaborCostWorkerDto> comparisonWorkers = safeWorkerList(comparisonCanonical);

        Map<String, WorkforceLaborCostWorkerDto> baseMatchedRaw = matchedWorkerIndex(baseWorkers);
        Map<String, WorkforceLaborCostWorkerDto> comparisonMatchedRaw = matchedWorkerIndex(comparisonWorkers);
        Map<String, WorkforceLaborCostWorkerDto> baseInvalid = invalidWorkerIndex(baseWorkers);
        Map<String, WorkforceLaborCostWorkerDto> comparisonInvalid = invalidWorkerIndex(comparisonWorkers);

        Set<String> blockedIds = new LinkedHashSet<>();
        blockedIds.addAll(baseInvalid.keySet());
        blockedIds.addAll(comparisonInvalid.keySet());

        Map<String, WorkforceLaborCostWorkerDto> baseMatched = new LinkedHashMap<>(baseMatchedRaw);
        Map<String, WorkforceLaborCostWorkerDto> comparisonMatched = new LinkedHashMap<>(comparisonMatchedRaw);
        blockedIds.forEach(baseMatched::remove);
        blockedIds.forEach(comparisonMatched::remove);

        List<WorkforceLaborCostComparisonWorkerDto> comparableWorkers = new ArrayList<>();
        List<WorkforceLaborCostComparisonWorkerDto> onlyBaseWorkers = new ArrayList<>();
        List<WorkforceLaborCostComparisonWorkerDto> onlyComparisonWorkers = new ArrayList<>();

        Set<String> comparableIds = new LinkedHashSet<>(baseMatched.keySet());
        comparableIds.retainAll(comparisonMatched.keySet());
        for (String workerId : comparableIds) {
            comparableWorkers.add(toComparisonWorkerRow(baseMatched.get(workerId), comparisonMatched.get(workerId), "MATCHED"));
        }

        for (Map.Entry<String, WorkforceLaborCostWorkerDto> entry : baseMatched.entrySet()) {
            if (comparableIds.contains(entry.getKey())) {
                continue;
            }
            onlyBaseWorkers.add(toComparisonWorkerRow(entry.getValue(), null, "ONLY_BASE"));
        }
        for (Map.Entry<String, WorkforceLaborCostWorkerDto> entry : comparisonMatched.entrySet()) {
            if (comparableIds.contains(entry.getKey())) {
                continue;
            }
            onlyComparisonWorkers.add(toComparisonWorkerRow(null, entry.getValue(), "ONLY_COMPARISON"));
        }

        Map<String, WorkforceLaborCostWorkerDto> reviewIndex = new LinkedHashMap<>();
        mergeReviewIndex(reviewIndex, baseWorkers);
        mergeReviewIndex(reviewIndex, comparisonWorkers);
        List<WorkforceLaborCostComparisonWorkerDto> reviewWorkers = reviewIndex.values().stream()
            .map(worker -> toComparisonWorkerRow(
                reviewReference(worker, baseWorkers),
                reviewReference(worker, comparisonWorkers),
                "REVIEW"
            ))
            .sorted(Comparator.comparing(WorkforceLaborCostComparisonWorkerDto::comparisonState)
                .thenComparing(WorkforceLaborCostComparisonWorkerDto::workerLabel, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
            .toList();

        Map<String, ManagerComparisonAccumulator> baseManagers = aggregateManagers(baseMatched.values());
        Map<String, ManagerComparisonAccumulator> comparisonManagers = aggregateManagers(comparisonMatched.values());
        Set<String> gestorKeys = new LinkedHashSet<>();
        gestorKeys.addAll(baseManagers.keySet());
        gestorKeys.addAll(comparisonManagers.keySet());
        List<WorkforceLaborCostComparisonManagerDto> gestores = gestorKeys.stream()
            .map(gestor -> {
                ManagerComparisonAccumulator baseManager = baseManagers.get(gestor);
                ManagerComparisonAccumulator comparisonManager = comparisonManagers.get(gestor);
                Double baseTotal = baseManager == null ? null : baseManager.total();
                Double comparisonTotal = comparisonManager == null ? null : comparisonManager.total();
                return new WorkforceLaborCostComparisonManagerDto(
                    gestor,
                    baseManager == null ? 0 : baseManager.workers(),
                    comparisonManager == null ? 0 : comparisonManager.workers(),
                    baseTotal,
                    comparisonTotal,
                    delta(comparisonTotal, baseTotal),
                    deltaPct(baseTotal, comparisonTotal),
                    deltaPctState(baseTotal, comparisonTotal)
                );
            })
            .sorted(Comparator.comparing(WorkforceLaborCostComparisonManagerDto::comparisonCosteTotal, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(WorkforceLaborCostComparisonManagerDto::gestor, String.CASE_INSENSITIVE_ORDER))
            .toList();

        Double baseReconciledTotal = sumNullable(baseMatched.values().stream().map(WorkforceLaborCostWorkerDto::costeLaboralAnual).toList());
        Double comparisonReconciledTotal = sumNullable(comparisonMatched.values().stream().map(WorkforceLaborCostWorkerDto::costeLaboralAnual).toList());
        WorkforceLaborCostComparisonTotalsDto totals = new WorkforceLaborCostComparisonTotalsDto(
            sumNullable(baseWorkers.stream().map(WorkforceLaborCostWorkerDto::costePersonalAnual).toList()),
            sumNullable(comparisonWorkers.stream().map(WorkforceLaborCostWorkerDto::costePersonalAnual).toList()),
            delta(
                sumNullable(comparisonWorkers.stream().map(WorkforceLaborCostWorkerDto::costePersonalAnual).toList()),
                sumNullable(baseWorkers.stream().map(WorkforceLaborCostWorkerDto::costePersonalAnual).toList())
            ),
            deltaPct(
                sumNullable(baseWorkers.stream().map(WorkforceLaborCostWorkerDto::costePersonalAnual).toList()),
                sumNullable(comparisonWorkers.stream().map(WorkforceLaborCostWorkerDto::costePersonalAnual).toList())
            ),
            deltaPctState(
                sumNullable(baseWorkers.stream().map(WorkforceLaborCostWorkerDto::costePersonalAnual).toList()),
                sumNullable(comparisonWorkers.stream().map(WorkforceLaborCostWorkerDto::costePersonalAnual).toList())
            ),
            sumNullable(baseWorkers.stream().map(WorkforceLaborCostWorkerDto::ssEmpresaAnual).toList()),
            sumNullable(comparisonWorkers.stream().map(WorkforceLaborCostWorkerDto::ssEmpresaAnual).toList()),
            delta(
                sumNullable(comparisonWorkers.stream().map(WorkforceLaborCostWorkerDto::ssEmpresaAnual).toList()),
                sumNullable(baseWorkers.stream().map(WorkforceLaborCostWorkerDto::ssEmpresaAnual).toList())
            ),
            deltaPct(
                sumNullable(baseWorkers.stream().map(WorkforceLaborCostWorkerDto::ssEmpresaAnual).toList()),
                sumNullable(comparisonWorkers.stream().map(WorkforceLaborCostWorkerDto::ssEmpresaAnual).toList())
            ),
            deltaPctState(
                sumNullable(baseWorkers.stream().map(WorkforceLaborCostWorkerDto::ssEmpresaAnual).toList()),
                sumNullable(comparisonWorkers.stream().map(WorkforceLaborCostWorkerDto::ssEmpresaAnual).toList())
            ),
            sumNullable(baseWorkers.stream().map(WorkforceLaborCostWorkerDto::costeLaboralAnual).toList()),
            sumNullable(comparisonWorkers.stream().map(WorkforceLaborCostWorkerDto::costeLaboralAnual).toList()),
            delta(
                sumNullable(comparisonWorkers.stream().map(WorkforceLaborCostWorkerDto::costeLaboralAnual).toList()),
                sumNullable(baseWorkers.stream().map(WorkforceLaborCostWorkerDto::costeLaboralAnual).toList())
            ),
            deltaPct(
                sumNullable(baseWorkers.stream().map(WorkforceLaborCostWorkerDto::costeLaboralAnual).toList()),
                sumNullable(comparisonWorkers.stream().map(WorkforceLaborCostWorkerDto::costeLaboralAnual).toList())
            ),
            deltaPctState(
                sumNullable(baseWorkers.stream().map(WorkforceLaborCostWorkerDto::costeLaboralAnual).toList()),
                sumNullable(comparisonWorkers.stream().map(WorkforceLaborCostWorkerDto::costeLaboralAnual).toList())
            ),
            baseReconciledTotal,
            comparisonReconciledTotal
        );

        WorkforceLaborCostComparisonCountsDto counts = new WorkforceLaborCostComparisonCountsDto(
            baseWorkers.size(),
            comparisonWorkers.size(),
            comparableWorkers.size(),
            onlyBaseWorkers.size(),
            onlyComparisonWorkers.size(),
            reviewWorkers.size(),
            reviewWorkers.stream().filter(row -> "UNMATCHED".equals(row.baseMatchingState()) || "UNMATCHED".equals(row.comparisonMatchingState())).count(),
            baseMatched.size(),
            comparisonMatched.size()
        );

        return new WorkforceLaborCostComparisonDto(
            basePeriod,
            comparisonPeriod,
            toDto(baseImport, versionNumber(baseImport)),
            toDto(comparisonImport, versionNumber(comparisonImport)),
            currency,
            "READY",
            "Comparación lista a partir de las importaciones persistidas.",
            totals,
            counts,
            gestores,
            comparableWorkers.stream()
                .sorted(Comparator.comparing(WorkforceLaborCostComparisonWorkerDto::comparisonCosteTotal, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(WorkforceLaborCostComparisonWorkerDto::workerLabel, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList(),
            onlyBaseWorkers.stream()
                .sorted(Comparator.comparing(WorkforceLaborCostComparisonWorkerDto::baseCosteTotal, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(WorkforceLaborCostComparisonWorkerDto::workerLabel, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList(),
            onlyComparisonWorkers.stream()
                .sorted(Comparator.comparing(WorkforceLaborCostComparisonWorkerDto::comparisonCosteTotal, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(WorkforceLaborCostComparisonWorkerDto::workerLabel, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList(),
            reviewWorkers
        );
    }

    private WorkforceLaborCostComparisonDto emptyComparison(String basePeriod,
                                                           String comparisonPeriod,
                                                           WorkforceImport baseImport,
                                                           WorkforceImport comparisonImport,
                                                           String status,
                                                           String message) {
        return new WorkforceLaborCostComparisonDto(
            basePeriod,
            comparisonPeriod,
            baseImport == null ? null : toDto(baseImport, versionNumber(baseImport)),
            comparisonImport == null ? null : toDto(comparisonImport, versionNumber(comparisonImport)),
            null,
            status,
            message,
            new WorkforceLaborCostComparisonTotalsDto(null, null, null, null, "NO_BASELINE", null, null, null, null, "NO_BASELINE", null, null, null, null, "NO_BASELINE", null, null),
            new WorkforceLaborCostComparisonCountsDto(0, 0, 0, 0, 0, 0, 0, 0, 0),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static Map<String, WorkforceLaborCostWorkerDto> matchedWorkerIndex(List<WorkforceLaborCostWorkerDto> workers) {
        Map<String, WorkforceLaborCostWorkerDto> index = new LinkedHashMap<>();
        for (WorkforceLaborCostWorkerDto worker : workers) {
            if (worker == null || !"MATCHED".equals(worker.matchingState()) || isBlank(worker.canonicalWorkerId())) {
                continue;
            }
            index.putIfAbsent(worker.canonicalWorkerId(), worker);
        }
        return index;
    }

    private static Map<String, WorkforceLaborCostWorkerDto> invalidWorkerIndex(List<WorkforceLaborCostWorkerDto> workers) {
        Map<String, WorkforceLaborCostWorkerDto> index = new LinkedHashMap<>();
        for (WorkforceLaborCostWorkerDto worker : workers) {
            if (worker == null || "MATCHED".equals(worker.matchingState())) {
                continue;
            }
            if (isBlank(worker.canonicalWorkerId())) {
                continue;
            }
            index.putIfAbsent(worker.canonicalWorkerId(), worker);
        }
        return index;
    }

    private static void mergeReviewIndex(Map<String, WorkforceLaborCostWorkerDto> reviewIndex,
                                         List<WorkforceLaborCostWorkerDto> workers) {
        for (WorkforceLaborCostWorkerDto worker : workers) {
            if (worker == null || "MATCHED".equals(worker.matchingState())) {
                continue;
            }
            reviewIndex.putIfAbsent(reviewWorkerKey(worker), worker);
        }
    }

    private static String reviewWorkerKey(WorkforceLaborCostWorkerDto worker) {
        if (worker == null) {
            return "REVIEW|null";
        }
        if (!isBlank(worker.canonicalWorkerId())) {
            return worker.canonicalWorkerId();
        }
        return firstNonBlank(worker.identityStrategy(), "REVIEW")
            + "|" + normalizeGestorName(worker.sourceGestor())
            + "|" + normalizeGestorName(worker.workerLabel())
            + "|" + normalize(worker.matchingDetail());
    }

    private static WorkforceLaborCostWorkerDto reviewReference(WorkforceLaborCostWorkerDto key,
                                                               List<WorkforceLaborCostWorkerDto> workers) {
        String reviewKey = reviewWorkerKey(key);
        return workers.stream()
            .filter(candidate -> Objects.equals(reviewWorkerKey(candidate), reviewKey))
            .findFirst()
            .orElse(null);
    }

    private static Map<String, ManagerComparisonAccumulator> aggregateManagers(Iterable<WorkforceLaborCostWorkerDto> workers) {
        Map<String, ManagerComparisonAccumulator> managers = new LinkedHashMap<>();
        for (WorkforceLaborCostWorkerDto worker : workers) {
            if (worker == null || isBlank(worker.canonicalGestor())) {
                continue;
            }
            managers.computeIfAbsent(worker.canonicalGestor(), ManagerComparisonAccumulator::new).add(worker);
        }
        return managers;
    }

    private static WorkforceLaborCostComparisonWorkerDto toComparisonWorkerRow(WorkforceLaborCostWorkerDto base,
                                                                               WorkforceLaborCostWorkerDto comparison,
                                                                               String comparisonState) {
        String workerLabel = firstNonBlank(
            comparison == null ? null : comparison.workerLabel(),
            base == null ? null : base.workerLabel(),
            comparison == null ? null : comparison.sourceWorker(),
            base == null ? null : base.sourceWorker(),
            comparison == null ? null : comparison.sourceGestor(),
            base == null ? null : base.sourceGestor(),
            "Sin identidad"
        );
        Double basePersonal = base == null ? null : base.costePersonalAnual();
        Double comparisonPersonal = comparison == null ? null : comparison.costePersonalAnual();
        Double baseSs = base == null ? null : base.ssEmpresaAnual();
        Double comparisonSs = comparison == null ? null : comparison.ssEmpresaAnual();
        Double baseTotal = base == null ? null : base.costeLaboralAnual();
        Double comparisonTotal = comparison == null ? null : comparison.costeLaboralAnual();
        return new WorkforceLaborCostComparisonWorkerDto(
            firstNonBlank(comparison == null ? null : comparison.canonicalWorkerId(), base == null ? null : base.canonicalWorkerId()),
            workerLabel,
            firstNonBlank(comparison == null ? null : comparison.identityStrategy(), base == null ? null : base.identityStrategy()),
            base == null ? null : base.canonicalGestor(),
            comparison == null ? null : comparison.canonicalGestor(),
            base == null ? null : base.matchingState(),
            comparison == null ? null : comparison.matchingState(),
            comparisonState,
            mergeDetails(base == null ? null : base.matchingDetail(), comparison == null ? null : comparison.matchingDetail()),
            basePersonal,
            comparisonPersonal,
            delta(comparisonPersonal, basePersonal),
            baseSs,
            comparisonSs,
            delta(comparisonSs, baseSs),
            baseTotal,
            comparisonTotal,
            delta(comparisonTotal, baseTotal),
            deltaPct(baseTotal, comparisonTotal),
            deltaPctState(baseTotal, comparisonTotal)
        );
    }

    private static List<WorkforceLaborCostWorkerDto> safeWorkerList(WorkforceLaborCostCanonicalImportDto canonical) {
        return canonical == null || canonical.workers() == null ? List.of() : canonical.workers();
    }

    private WorkforceSummaryDto buildSummary(DetectedColumns detected, Map<String, GestorAccumulator> aggregates) {
        List<WorkforceGestorDto> gestores = aggregates.values().stream()
            .map(GestorAccumulator::toDto)
            .sorted(Comparator.comparingLong(WorkforceGestorDto::totalClients).reversed()
                .thenComparing(WorkforceGestorDto::gestor, String.CASE_INSENSITIVE_ORDER))
            .toList();

        long totalClients = gestores.stream().mapToLong(WorkforceGestorDto::totalClients).sum();
        long activeClients = gestores.stream().mapToLong(WorkforceGestorDto::activeClients).sum();
        long inactiveClients = gestores.stream().mapToLong(WorkforceGestorDto::inactiveClients).sum();
        Double totalMinutas = sumNullable(gestores.stream().map(WorkforceGestorDto::totalMinutas).toList());
        Double totalCarga = sumNullable(gestores.stream().map(WorkforceGestorDto::totalCarga).toList());
        Double totalVolumenAsientos = sumNullable(gestores.stream().map(WorkforceGestorDto::totalVolumenAsientos).toList());

        WorkforceKpiDto kpis = new WorkforceKpiDto(
            totalClients,
            gestores.size(),
            activeClients,
            inactiveClients,
            totalMinutas,
            totalCarga,
            totalVolumenAsientos
        );
        return new WorkforceSummaryDto(kpis, gestores, detected.detectedLabels(), detected.activityYears(), null, null, null, null, null, null, null, null, List.of(), List.of());
    }

    private ParsedRow parseRow(CSVRecord record,
                               Map<String, String> headers,
                               DetectedColumns detected,
                               List<String> warningDetails) {
        List<String> rowWarnings = new ArrayList<>();
        BigDecimal minutas = parseDecimal(trackRaw(record, headers, detected.minutasColumn(), rowWarnings, "MINUTAS"));
        BigDecimal carga = parseDecimal(trackRaw(record, headers, detected.cargaColumn(), rowWarnings, "CARGA DE TRABAJO"));
        BigDecimal pct = parseDecimal(trackRaw(record, headers, detected.pctContabilidadColumn(), rowWarnings, "% CONTABILIDAD"));
        BigDecimal promedio = parseDecimal(trackRaw(record, headers, detected.promedioColumn(), rowWarnings, "PROMEDIO"));
        String contModelosRaw = safeGet(record, headers, detected.contModelosColumn());
        String isIrpfRaw = safeGet(record, headers, detected.isIrpfColumn());
        String ddccRaw = safeGet(record, headers, detected.ddccColumn());
        String librosRaw = safeGet(record, headers, detected.librosColumn());
        WorkforceFieldStatus contModelos = parseStatus(contModelosRaw);
        WorkforceFieldStatus isIrpf = parseStatus(isIrpfRaw);
        WorkforceFieldStatus ddcc = parseStatus(ddccRaw);
        WorkforceFieldStatus libros = parseStatus(librosRaw);

        if (contModelosRaw != null && contModelos == WorkforceFieldStatus.UNKNOWN) rowWarnings.add("CONT/MODELOS");
        if (isIrpfRaw != null && isIrpf == WorkforceFieldStatus.UNKNOWN) rowWarnings.add("IS/IRPF");
        if (ddccRaw != null && ddcc == WorkforceFieldStatus.UNKNOWN) rowWarnings.add("DDCC");
        if (librosRaw != null && libros == WorkforceFieldStatus.UNKNOWN) rowWarnings.add("LIBROS");

        Map<Integer, Integer> annualSeatTotals = new TreeMap<>();
        for (Map.Entry<Integer, String> entry : detected.yearColumns().entrySet()) {
            String raw = safeGet(record, headers, entry.getValue());
            if (raw == null) continue;
            Integer value = parseInteger(raw);
            if (value == null) {
                rowWarnings.add("N AS " + entry.getKey());
                continue;
            }
            annualSeatTotals.put(entry.getKey(), value);
        }

        if (promedio == null && !annualSeatTotals.isEmpty()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (Integer value : annualSeatTotals.values()) {
                sum = sum.add(BigDecimal.valueOf(value));
            }
            promedio = sum.divide(BigDecimal.valueOf(annualSeatTotals.size()), 4, RoundingMode.HALF_UP);
        }

        if (!rowWarnings.isEmpty() && warningDetails.size() < 5) {
            warningDetails.add("Fila " + (record.getRecordNumber() + 1) + ": " + String.join(", ", rowWarnings));
        }

        return new ParsedRow(
            safeGet(record, headers, detected.gestorColumn()),
            safeGet(record, headers, normalize("f/baja")) != null,
            minutas,
            carga,
            pct,
            promedio,
            contModelos,
            isIrpf,
            ddcc,
            libros,
            annualSeatTotals,
            rowWarnings.size()
        );
    }

    private ParsedLaborCostRow parseLaborCostRow(CSVRecord record,
                                                 Map<String, String> headers,
                                                 DetectedLaborCostColumns detected,
                                                 List<String> warningDetails,
                                                 TemporalCoverageAccumulator coverage) {
        List<String> rowWarnings = new ArrayList<>();
        List<MonthlyCostValue> values = new ArrayList<>();

        boolean rowMode = detected.monthColumn() != null && detected.costePersonalColumn() != null && detected.ssEmpresaColumn() != null;
        if (rowMode) {
            String rawMonth = safeGet(record, headers, detected.monthColumn());
            String month = normalizeMonth(rawMonth);
            if (month == null) {
                month = coverage.fallbackMonth();
            }
            if (rawMonth != null && month == null) {
                rowWarnings.add("MES");
            }
            coverage.registerRawMonth(rawMonth, month);
            BigDecimal costePersonal = parseDecimal(trackRaw(record, headers, detected.costePersonalColumn(), rowWarnings, "COSTE PERSONAL"));
            BigDecimal ssEmpresa = parseDecimal(trackRaw(record, headers, detected.ssEmpresaColumn(), rowWarnings, "SS EMPRESA"));
            if (month != null && (costePersonal != null || ssEmpresa != null)) {
                values.add(new MonthlyCostValue(month, costePersonal, ssEmpresa));
            }
        }

        if (!rowMode) {
            for (Map.Entry<String, MonthMetricColumns> entry : detected.monthColumns().entrySet()) {
                MonthMetricColumns columns = entry.getValue();
                BigDecimal costePersonal = parseDecimal(trackRaw(
                    record,
                    headers,
                    columns.costePersonalColumn(),
                    rowWarnings,
                    "COSTE PERSONAL " + entry.getKey()
                ));
                BigDecimal ssEmpresa = parseDecimal(trackRaw(
                    record,
                    headers,
                    columns.ssEmpresaColumn(),
                    rowWarnings,
                    "SS EMPRESA " + entry.getKey()
                ));
                if (costePersonal != null || ssEmpresa != null) {
                    coverage.registerMonth(entry.getKey());
                    values.add(new MonthlyCostValue(entry.getKey(), costePersonal, ssEmpresa));
                }
            }
        }

        if (!rowWarnings.isEmpty() && warningDetails.size() < 5) {
            warningDetails.add("Fila " + (record.getRecordNumber() + 1) + ": " + String.join(", ", rowWarnings));
        }

        return new ParsedLaborCostRow(
            detected.gestorColumn() == null ? safeGet(record, headers, detected.workerColumn()) : safeGet(record, headers, detected.gestorColumn()),
            detected.workerColumn() == null ? safeGet(record, headers, detected.gestorColumn()) : safeGet(record, headers, detected.workerColumn()),
            safeGet(record, headers, detected.employeeIdColumn()),
            safeGet(record, headers, detected.externalIdColumn()),
            safeGet(record, headers, detected.emailColumn()),
            safeGet(record, headers, detected.currencyColumn()),
            dedupeMonthlyValues(values),
            rowWarnings.size()
        );
    }

    private static List<MonthlyCostValue> dedupeMonthlyValues(List<MonthlyCostValue> values) {
        if (values.isEmpty()) return List.of();
        Map<String, MonthlyCostValue> deduped = new LinkedHashMap<>();
        for (MonthlyCostValue value : values) {
            if (value == null || value.month() == null) continue;
            deduped.putIfAbsent(value.month(), value);
        }
        return List.copyOf(deduped.values());
    }

    private static CSVParser buildParser(String firstLine, BufferedReader reader) throws IOException {
        return CSVFormat.DEFAULT.builder()
            .setDelimiter(detectDelimiter(firstLine))
            .setHeader()
            .setSkipHeaderRecord(true)
            .setAllowMissingColumnNames(true)
            .setIgnoreEmptyLines(true)
            .build()
            .parse(reader);
    }

    private Optional<WorkforceSummaryDto> parseWorkforceSummary(WorkforceImport workforceImport) {
        if (workforceImport == null || workforceImport.getSummaryJson() == null || workforceImport.getSummaryJson().isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(workforceImport.getSummaryJson(), WorkforceSummaryDto.class));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private Optional<WorkforceLaborCostImportSummaryDto> parseLaborCostImportSummary(WorkforceImport workforceImport) {
        if (workforceImport == null || workforceImport.getSummaryJson() == null || workforceImport.getSummaryJson().isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(workforceImport.getSummaryJson(), WorkforceLaborCostImportSummaryDto.class));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private WorkforceImportDto toDto(WorkforceImport workforceImport, int versionNumber) {
        if (workforceImport.getImportKind() == WorkforceImportKind.LABOR_COSTS) {
            List<String> detectedColumns = parseLaborCostImportSummary(workforceImport)
                .map(WorkforceLaborCostImportSummaryDto::detectedColumns)
                .orElse(List.of());
            return toDto(workforceImport, detectedColumns, List.of(), versionNumber);
        }
        WorkforceSummaryDto summary = parseWorkforceSummary(workforceImport).orElse(null);
        return toDto(
            workforceImport,
            summary == null ? List.of() : summary.detectedColumns(),
            summary == null ? List.of() : summary.activityYears(),
            versionNumber
        );
    }

    private List<WorkforceImportDto> toDtoList(List<WorkforceImport> imports) {
        if (imports == null || imports.isEmpty()) {
            return List.of();
        }
        return imports.stream()
            .map(workforceImport -> toDto(workforceImport, versionNumber(workforceImport, imports)))
            .toList();
    }

    private WorkforceImportDto toDto(WorkforceImport workforceImport,
                                     List<String> detectedColumns,
                                     List<Integer> activityYears,
                                     int versionNumber) {
        TemporalReference reference = temporalReferenceOf(workforceImport);
        String referencePeriod = reference.known() ? reference.referencePeriod() : normalizeReferencePeriod(workforceImport.getReferencePeriod());
        Integer referenceYear = reference.referenceYear() != null ? reference.referenceYear() : workforceImport.getReferenceYear();
        Integer referenceMonth = reference.referenceMonth() != null ? reference.referenceMonth() : workforceImport.getReferenceMonth();
        Integer coverageStartMonth = reference.startMonth() != null ? reference.startMonth() : workforceImport.getCoverageStartMonth();
        Integer coverageEndMonth = reference.endMonth() != null ? reference.endMonth() : workforceImport.getCoverageEndMonth();
        boolean annualCoverage = reference.known() ? reference.annualCoverage() : Boolean.TRUE.equals(workforceImport.getCoverageCompleteYear());
        return new WorkforceImportDto(
            workforceImport.getId(),
            workforceImport.getCompany().getId(),
            workforceImport.getImportKind().name(),
            workforceImport.getFilename(),
            workforceImport.getCreatedAt(),
            workforceImport.getRowCount(),
            workforceImport.getWarningCount(),
            workforceImport.getErrorCount(),
            workforceImport.getErrorSummary(),
            referencePeriod,
            reference.label(),
            workforceImport.getImportStatus().name(),
            referenceYear,
            referenceMonth,
            coverageStartMonth,
            coverageEndMonth,
            annualCoverage,
            versionNumber,
            detectedColumns == null ? List.of() : detectedColumns,
            activityYears == null ? List.of() : activityYears
        );
    }

    private WorkforceSummaryDto emptySummary() {
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

    private static DetectedColumns detectColumns(Map<String, String> headers) {
        String gestor = findRequired(headers, "gestor", "manager");
        String minutas = findRequired(headers, "minutas", "minuta");
        String carga = findRequired(headers, "cargadetrabajo", "carga");
        String pct = findRequired(headers, "pctcontabilidad", "contabilidadpct");
        String contModelos = findRequired(headers, "contmodelos");
        String isIrpf = findRequired(headers, "isirpf");
        String ddcc = findRequired(headers, "ddcc");
        String libros = findRequired(headers, "libros");
        String promedio = findRequired(headers, "promedio");

        Map<Integer, String> years = new LinkedHashMap<>();
        for (String normalized : headers.keySet()) {
            if (!YEAR_COLUMN_PATTERN.matcher(normalized).matches()) continue;
            try {
                int year = Integer.parseInt(normalized.substring(3));
                years.put(year, normalized);
            } catch (NumberFormatException ignored) {
            }
        }

        List<String> missing = new ArrayList<>();
        if (gestor == null) missing.add("GESTOR");
        if (minutas == null) missing.add("MINUTAS");
        if (carga == null) missing.add("CARGA DE TRABAJO");
        if (pct == null) missing.add("% CONTABILIDAD");
        if (contModelos == null) missing.add("CONT/MODELOS");
        if (isIrpf == null) missing.add("IS/IRPF");
        if (ddcc == null) missing.add("DDCC");
        if (libros == null) missing.add("LIBROS");
        if (promedio == null) missing.add("PROMEDIO");
        if (years.isEmpty()) missing.add("N AS YYYY");

        List<String> detectedLabels = new ArrayList<>();
        if (gestor != null) detectedLabels.add(headers.get(gestor));
        if (minutas != null) detectedLabels.add(headers.get(minutas));
        if (carga != null) detectedLabels.add(headers.get(carga));
        if (pct != null) detectedLabels.add(headers.get(pct));
        if (contModelos != null) detectedLabels.add(headers.get(contModelos));
        if (isIrpf != null) detectedLabels.add(headers.get(isIrpf));
        if (ddcc != null) detectedLabels.add(headers.get(ddcc));
        if (libros != null) detectedLabels.add(headers.get(libros));
        for (String normalizedYear : years.values()) {
            detectedLabels.add(headers.get(normalizedYear));
        }
        if (promedio != null) detectedLabels.add(headers.get(promedio));

        return new DetectedColumns(
            gestor,
            minutas,
            carga,
            pct,
            contModelos,
            isIrpf,
            ddcc,
            libros,
            promedio,
            years,
            missing,
            detectedLabels.stream().filter(Objects::nonNull).toList(),
            years.keySet().stream().sorted().toList()
        );
    }

    private static DetectedLaborCostColumns detectLaborCostColumns(Map<String, String> headers) {
        String gestor = findRequired(headers, "gestor", "manager", "responsable", "supervisor");
        String worker = findRequired(headers, "trabajador", "worker", "empleado", "employee", "persona", "nombre");
        String employeeId = findRequired(headers, "idempleado", "empleadoid", "employeeid", "workerid", "codigoempleado", "employeecode");
        String externalId = findRequired(headers, "idexterno", "externalid", "externalcode");
        String email = findRequired(headers, "email", "correo", "correocorporativo", "workemail");
        String currency = findRequired(headers, "moneda", "divisa", "currency", "currencycode");
        String monthColumn = findRequired(headers, "mes", "month", "periodo", "period");
        String costePersonalColumn = findRequired(headers, "costepersonal", "costepersonalmensual");
        String ssEmpresaColumn = findRequired(headers, "ssempresa", "seguridadsocialempresa", "sspatronal");

        Map<String, MonthMetricColumns> monthColumns = detectMonthMetricColumns(headers);
        boolean hasMonthlyPersonal = monthColumns.values().stream().anyMatch(value -> value.costePersonalColumn() != null);
        boolean hasMonthlySs = monthColumns.values().stream().anyMatch(value -> value.ssEmpresaColumn() != null);

        List<String> missing = new ArrayList<>();
        if (gestor == null && worker == null) missing.add("GESTOR / TRABAJADOR");
        if (monthColumn == null && monthColumns.isEmpty()) missing.add("MES o columnas mensuales");
        if (monthColumn != null) {
            if (costePersonalColumn == null) missing.add("COSTE PERSONAL");
            if (ssEmpresaColumn == null) missing.add("SS EMPRESA");
        } else {
            if (!hasMonthlyPersonal) missing.add("COSTE PERSONAL");
            if (!hasMonthlySs) missing.add("SS EMPRESA");
        }

        Set<String> detectedLabels = new LinkedHashSet<>();
        if (gestor != null) detectedLabels.add(headers.get(gestor));
        if (worker != null) detectedLabels.add(headers.get(worker));
        if (employeeId != null) detectedLabels.add(headers.get(employeeId));
        if (externalId != null) detectedLabels.add(headers.get(externalId));
        if (email != null) detectedLabels.add(headers.get(email));
        if (currency != null) detectedLabels.add(headers.get(currency));
        if (monthColumn != null) detectedLabels.add(headers.get(monthColumn));
        if (costePersonalColumn != null) detectedLabels.add(headers.get(costePersonalColumn));
        if (ssEmpresaColumn != null) detectedLabels.add(headers.get(ssEmpresaColumn));
        for (MonthMetricColumns value : monthColumns.values()) {
            if (value.costePersonalColumn() != null) detectedLabels.add(headers.get(value.costePersonalColumn()));
            if (value.ssEmpresaColumn() != null) detectedLabels.add(headers.get(value.ssEmpresaColumn()));
        }

        return new DetectedLaborCostColumns(
            gestor,
            worker,
            employeeId,
            externalId,
            email,
            currency,
            monthColumn,
            costePersonalColumn,
            ssEmpresaColumn,
            monthColumns,
            missing,
            detectedLabels.stream().filter(Objects::nonNull).toList(),
            detectSingleYear(detectedLabels)
        );
    }

    private static Map<String, MonthMetricColumns> detectMonthMetricColumns(Map<String, String> headers) {
        Map<String, MonthMetricColumns> detected = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String rawHeader = entry.getValue();
            if (rawHeader == null) continue;
            String month = detectMonthFromHeader(rawHeader);
            if (month == null) continue;
            String normalized = normalize(entry.getKey());
            String personalColumn = isCostePersonalHeader(rawHeader, normalized) ? entry.getKey() : null;
            String ssColumn = isSsEmpresaHeader(rawHeader, normalized) ? entry.getKey() : null;
            if (personalColumn == null && ssColumn == null) continue;

            MonthMetricColumns current = detected.getOrDefault(month, new MonthMetricColumns(null, null));
            detected.put(month, new MonthMetricColumns(
                current.costePersonalColumn() == null ? personalColumn : current.costePersonalColumn(),
                current.ssEmpresaColumn() == null ? ssColumn : current.ssEmpresaColumn()
            ));
        }

        Map<String, MonthMetricColumns> ordered = new LinkedHashMap<>();
        for (String month : MONTH_SEQUENCE) {
            MonthMetricColumns value = detected.get(month);
            if (value != null) ordered.put(month, value);
        }
        return ordered;
    }

    private static Map<String, List<String>> buildWorkforceGestorLookup(List<WorkforceGestorDto> gestores) {
        Map<String, List<String>> lookup = new LinkedHashMap<>();
        if (gestores == null) return lookup;
        for (WorkforceGestorDto gestor : gestores) {
            if (gestor == null || isBlank(gestor.gestor())) continue;
            lookup.computeIfAbsent(normalizeGestorName(gestor.gestor()), ignored -> new ArrayList<>())
                .add(gestor.gestor().trim());
        }
        return lookup;
    }

    private static ResolvedGestor resolveGestor(String sourceGestor, Map<String, List<String>> workforceLookup) {
        String normalized = normalizeGestorName(sourceGestor);
        if (normalized.isBlank()) {
            return ResolvedGestor.review(sourceGestor, normalized, "REVIEW: gestor/trabajador vacio.");
        }
        List<String> candidates = workforceLookup.get(normalized);
        if (candidates == null || candidates.isEmpty()) {
            return ResolvedGestor.unmatched(sourceGestor, normalized, "UNMATCHED: sin match con Workforce para '" + sourceGestor + "'.");
        }
        if (candidates.size() > 1) {
            return ResolvedGestor.review(sourceGestor, normalized, "REVIEW: match ambiguo para '" + sourceGestor + "' -> " + String.join(", ", candidates));
        }
        return ResolvedGestor.match(candidates.get(0), normalized);
    }

    private static ResolvedWorkerIdentity resolveWorkerIdentity(ParsedLaborCostRow row) {
        String employeeId = normalizeIdentityToken(row.employeeId());
        if (!isBlank(employeeId)) {
            return new ResolvedWorkerIdentity("EMP:" + employeeId, "EMPLOYEE_ID", firstNonBlank(row.sourceWorker(), row.sourceGestor(), row.employeeId()), false, null);
        }

        String externalId = normalizeIdentityToken(row.externalId());
        if (!isBlank(externalId)) {
            return new ResolvedWorkerIdentity("EXT:" + externalId, "EXTERNAL_ID", firstNonBlank(row.sourceWorker(), row.sourceGestor(), row.externalId()), false, null);
        }

        String email = normalizeEmail(row.email());
        if (!isBlank(email)) {
            return new ResolvedWorkerIdentity("MAIL:" + email, "EMAIL", firstNonBlank(row.sourceWorker(), row.sourceGestor(), row.email()), false, null);
        }

        String normalizedName = normalizePersonName(firstNonBlank(row.sourceWorker(), row.sourceGestor()));
        if (!isBlank(normalizedName)) {
            return new ResolvedWorkerIdentity("NAME:" + normalizedName, "NAME", firstNonBlank(row.sourceWorker(), row.sourceGestor()), false, null);
        }

        return new ResolvedWorkerIdentity(
            null,
            "NONE",
            firstNonBlank(row.sourceWorker(), row.sourceGestor(), "Sin identidad"),
            true,
            "REVIEW: no se pudo construir una identidad estable del trabajador."
        );
    }

    private static String workerAggregationKey(ResolvedWorkerIdentity identity,
                                               ParsedLaborCostRow row,
                                               ResolvedGestor resolvedGestor) {
        if (identity != null && !isBlank(identity.canonicalWorkerId())) {
            return identity.canonicalWorkerId();
        }
        return firstNonBlank(identity == null ? null : identity.identityStrategy(), "REVIEW")
            + "|" + normalizeGestorName(row == null ? null : row.sourceGestor())
            + "|" + normalizePersonName(firstNonBlank(row == null ? null : row.sourceWorker(), row == null ? null : row.sourceGestor()))
            + "|" + normalize(resolvedGestor == null ? null : resolvedGestor.detail());
    }

    private static String normalizeIdentityToken(String value) {
        if (value == null) return null;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9]+", "")
            .trim();
        return normalized.isBlank() ? null : normalized;
    }

    private static String normalizePersonName(String value) {
        if (value == null) return null;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9]+", "")
            .trim();
        return normalized.isBlank() ? null : normalized;
    }

    private static String normalizeEmail(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private static String normalizeCurrencyCode(String value) {
        if (value == null) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        return normalized.isBlank() ? null : normalized;
    }

    private static String mergeDetails(String first, String second) {
        if (isBlank(first)) return isBlank(second) ? null : second;
        if (isBlank(second) || Objects.equals(first, second)) return first;
        return first + " | " + second;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static String findRequired(Map<String, String> headers, String... aliases) {
        for (String alias : aliases) {
            String normalized = normalize(alias);
            if (headers.containsKey(normalized)) return normalized;
        }
        return null;
    }

    private static Map<String, String> normalizeHeaders(Iterable<String> rawHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String rawHeader : rawHeaders) {
            headers.put(normalize(rawHeader), rawHeader);
        }
        return headers;
    }

    private static String normalize(String header) {
        if (header == null) return "";
        return Normalizer.normalize(header, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT)
            .replace("%", "pct")
            .replaceAll("[^a-z0-9]+", "")
            .trim();
    }

    private static String normalizeGestorName(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9]+", "")
            .trim();
    }

    private void supersedeActiveImport(Long companyId, WorkforceImportKind importKind, String referencePeriod) {
        String referenceKey = normalizeReferencePeriod(referencePeriod);
        boolean changed = false;
        for (WorkforceImport imported : listImports(companyId, importKind)) {
            if (imported.getImportStatus() != WorkforceImportStatus.ACTIVE) {
                continue;
            }
            if (!Objects.equals(normalizeReferencePeriod(imported.getReferencePeriod()), referenceKey)) {
                continue;
            }
            imported.setImportStatus(WorkforceImportStatus.SUPERSEDED);
            changed = true;
        }
        if (changed) {
            importRepository.flush();
        }
    }

    private void applyTemporalReference(WorkforceImport workforceImport, TemporalReference temporalReference) {
        workforceImport.setReferencePeriod(normalizeReferencePeriod(temporalReference.referencePeriod()));
        workforceImport.setReferenceYear(temporalReference.referenceYear());
        workforceImport.setReferenceMonth(temporalReference.referenceMonth());
        workforceImport.setCoverageStartMonth(temporalReference.startMonth());
        workforceImport.setCoverageEndMonth(temporalReference.endMonth());
        workforceImport.setCoverageCompleteYear(temporalReference.annualCoverage());
    }

    private int versionNumber(WorkforceImport workforceImport) {
        if (workforceImport == null || workforceImport.getCompany() == null || workforceImport.getCompany().getId() == null) {
            return 1;
        }
        return versionNumber(workforceImport, listImports(workforceImport.getCompany().getId(), workforceImport.getImportKind()));
    }

    private int versionNumber(WorkforceImport workforceImport, List<WorkforceImport> imports) {
        if (workforceImport == null) {
            return 1;
        }
        List<WorkforceImport> samePeriod = (imports == null ? List.<WorkforceImport>of() : imports).stream()
            .filter(imported -> imported.getImportKind() == workforceImport.getImportKind())
            .filter(imported -> Objects.equals(
                normalizeReferencePeriod(imported.getReferencePeriod()),
                normalizeReferencePeriod(workforceImport.getReferencePeriod())
            ))
            .sorted(Comparator.comparing(WorkforceImport::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(WorkforceImport::getId, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
        for (int index = 0; index < samePeriod.size(); index++) {
            if (sameImport(samePeriod.get(index), workforceImport)) {
                return index + 1;
            }
        }
        return samePeriod.size() + 1;
    }

    private static boolean sameImport(WorkforceImport left, WorkforceImport right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        if (left.getId() != null || right.getId() != null) {
            return Objects.equals(left.getId(), right.getId());
        }
        return Objects.equals(left.getCreatedAt(), right.getCreatedAt())
            && Objects.equals(left.getFilename(), right.getFilename())
            && left.getImportKind() == right.getImportKind()
            && Objects.equals(normalizeReferencePeriod(left.getReferencePeriod()), normalizeReferencePeriod(right.getReferencePeriod()));
    }

    private static <T> Optional<T> optional(Optional<T> value) {
        return value == null ? Optional.empty() : value;
    }

    private static TemporalReference parseWorkforceReference(String referencePeriod) {
        String raw = referencePeriod == null ? "" : referencePeriod.trim();
        if (raw.matches("\\d{4}-\\d{2}")) {
            int year = Integer.parseInt(raw.substring(0, 4));
            int month = Integer.parseInt(raw.substring(5, 7));
            if (month >= 1 && month <= 12) {
                return new TemporalReference(raw, year, month, month, month, false, referenceLabel(year, month, month, false), true);
            }
        }
        if (raw.matches("\\d{4}")) {
            int year = Integer.parseInt(raw);
            return new TemporalReference(raw, year, null, 1, 12, true, referenceLabel(year, 1, 12, true), true);
        }
        return unknownTemporalReference();
    }

    private static TemporalReference resolveWorkforceReference(String referencePeriod, String filename) {
        TemporalReference explicitReference = parseWorkforceReference(referencePeriod);
        if (explicitReference.known()) {
            return explicitReference;
        }
        return inferWorkforceReferenceFromFilename(filename);
    }

    private static TemporalReference inferWorkforceReferenceFromFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return unknownTemporalReference();
        }

        String raw = filename.trim();
        Integer year = detectSingleYear(List.of(raw));
        if (year == null) {
            return unknownTemporalReference();
        }

        String month = normalizeMonth(raw);
        Integer monthIndex = monthIndex(month);
        if (monthIndex != null) {
            String key = "%04d-%02d".formatted(year, monthIndex);
            return new TemporalReference(
                key,
                year,
                monthIndex,
                monthIndex,
                monthIndex,
                false,
                referenceLabel(year, monthIndex, monthIndex, false),
                true
            );
        }

        var yearMonthMatcher = Pattern.compile("(19|20)\\d{2}[-_/ ](0?[1-9]|1[0-2])").matcher(raw);
        if (yearMonthMatcher.find()) {
            int resolvedYear = Integer.parseInt(yearMonthMatcher.group().substring(0, 4));
            int resolvedMonth = Integer.parseInt(yearMonthMatcher.group(2));
            String key = "%04d-%02d".formatted(resolvedYear, resolvedMonth);
            return new TemporalReference(
                key,
                resolvedYear,
                resolvedMonth,
                resolvedMonth,
                resolvedMonth,
                false,
                referenceLabel(resolvedYear, resolvedMonth, resolvedMonth, false),
                true
            );
        }

        var monthYearMatcher = Pattern.compile("(0?[1-9]|1[0-2])[-_/ ]((19|20)\\d{2})").matcher(raw);
        if (monthYearMatcher.find()) {
            int resolvedMonth = Integer.parseInt(monthYearMatcher.group(1));
            int resolvedYear = Integer.parseInt(monthYearMatcher.group(2));
            String key = "%04d-%02d".formatted(resolvedYear, resolvedMonth);
            return new TemporalReference(
                key,
                resolvedYear,
                resolvedMonth,
                resolvedMonth,
                resolvedMonth,
                false,
                referenceLabel(resolvedYear, resolvedMonth, resolvedMonth, false),
                true
            );
        }

        return unknownTemporalReference();
    }

    private static TemporalReference temporalReferenceOf(WorkforceImport workforceImport) {
        if (workforceImport == null) {
            return unknownTemporalReference();
        }
        String referencePeriod = normalizeReferencePeriod(workforceImport.getReferencePeriod());
        Integer year = workforceImport.getReferenceYear();
        Integer month = workforceImport.getReferenceMonth();
        Integer startMonth = workforceImport.getCoverageStartMonth();
        Integer endMonth = workforceImport.getCoverageEndMonth();
        boolean annualCoverage = Boolean.TRUE.equals(workforceImport.getCoverageCompleteYear());
        boolean known = !UNKNOWN_REFERENCE_PERIOD.equals(referencePeriod) && year != null;
        if (!known && workforceImport.getImportKind() == WorkforceImportKind.WORKFORCE) {
            TemporalReference inferred = inferWorkforceReferenceFromFilename(workforceImport.getFilename());
            if (inferred.known()) {
                return inferred;
            }
        }
        return new TemporalReference(
            referencePeriod,
            year,
            month,
            startMonth,
            endMonth,
            annualCoverage,
            known ? referenceLabel(year, startMonth, endMonth, annualCoverage) : "Periodo desconocido",
            known
        );
    }

    private static TemporalReference unknownTemporalReference() {
        return new TemporalReference(UNKNOWN_REFERENCE_PERIOD, null, null, null, null, false, "Periodo desconocido", false);
    }

    private static String normalizeReferencePeriod(String referencePeriod) {
        if (referencePeriod == null || referencePeriod.isBlank()) {
            return UNKNOWN_REFERENCE_PERIOD;
        }
        return referencePeriod.trim();
    }

    private static int compareImportsByReference(WorkforceImport left, WorkforceImport right) {
        TemporalReference leftReference = temporalReferenceOf(left);
        TemporalReference rightReference = temporalReferenceOf(right);
        int referenceOrder = compareTemporalReferences(leftReference, rightReference);
        if (referenceOrder != 0) {
            return referenceOrder;
        }
        int createdOrder = Comparator.nullsLast(Comparator.<Instant>naturalOrder()).compare(
            left == null ? null : left.getCreatedAt(),
            right == null ? null : right.getCreatedAt()
        );
        if (createdOrder != 0) {
            return createdOrder;
        }
        return Comparator.nullsLast(Comparator.<Long>naturalOrder()).compare(
            left == null ? null : left.getId(),
            right == null ? null : right.getId()
        );
    }

    private static int compareTemporalReferences(TemporalReference left, TemporalReference right) {
        if (left == null && right == null) return 0;
        if (left == null) return 1;
        if (right == null) return -1;
        if (left.known() && right.known()) {
            int yearOrder = Comparator.<Integer>nullsLast(Comparator.naturalOrder()).compare(left.referenceYear(), right.referenceYear());
            if (yearOrder != 0) {
                return yearOrder;
            }
            int endMonthOrder = Comparator.<Integer>nullsLast(Comparator.naturalOrder()).compare(left.endMonth(), right.endMonth());
            if (endMonthOrder != 0) {
                return endMonthOrder;
            }
            int startMonthOrder = Comparator.<Integer>nullsLast(Comparator.naturalOrder()).compare(left.startMonth(), right.startMonth());
            if (startMonthOrder != 0) {
                return startMonthOrder;
            }
            int annualOrder = Boolean.compare(left.annualCoverage(), right.annualCoverage());
            if (annualOrder != 0) {
                return annualOrder;
            }
            return Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER).compare(left.referencePeriod(), right.referencePeriod());
        }
        if (left.known()) return -1;
        if (right.known()) return 1;
        return Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER).compare(left.referencePeriod(), right.referencePeriod());
    }

    private static String referenceLabel(Integer year, Integer startMonth, Integer endMonth, boolean annualCoverage) {
        if (year == null) {
            return "Periodo desconocido";
        }
        if (annualCoverage) {
            return String.valueOf(year);
        }
        if (startMonth != null && endMonth != null && startMonth.equals(endMonth)) {
            return monthLabel(startMonth) + " " + year;
        }
        if (startMonth != null && endMonth != null) {
            return monthLabel(startMonth) + "-" + monthLabel(endMonth) + " " + year;
        }
        return String.valueOf(year);
    }

    private static String monthLabel(int month) {
        return MONTH_SEQUENCE.get(month - 1).toLowerCase(Locale.ROOT);
    }

    private static Integer monthIndex(String month) {
        if (month == null || month.isBlank()) {
            return null;
        }
        for (int index = 0; index < MONTH_SEQUENCE.size(); index++) {
            if (MONTH_SEQUENCE.get(index).equalsIgnoreCase(month.trim())) {
                return index + 1;
            }
        }
        return null;
    }

    private static Integer detectSingleYear(Iterable<String> values) {
        Set<Integer> years = new LinkedHashSet<>();
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            for (String token : tokenizeHeader(value)) {
                if (!token.matches("(19|20)\\d{2}")) {
                    continue;
                }
                years.add(Integer.parseInt(token));
                if (years.size() > 1) {
                    return null;
                }
            }
        }
        return years.isEmpty() ? null : years.iterator().next();
    }

    private static String normalizeMonth(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9/\\-]+", " ")
            .trim();
        if (normalized.isBlank()) return null;

        String byHeader = detectMonthFromHeader(normalized);
        if (byHeader != null) return byHeader;

        if (normalized.matches("\\d{1,2}")) {
            int month = Integer.parseInt(normalized);
            return month >= 1 && month <= 12 ? MONTH_SEQUENCE.get(month - 1) : null;
        }
        if (normalized.matches("\\d{4}[-/]\\d{1,2}")) {
            String[] parts = normalized.split("[-/]");
            int month = Integer.parseInt(parts[1]);
            return month >= 1 && month <= 12 ? MONTH_SEQUENCE.get(month - 1) : null;
        }
        return null;
    }

    private static String detectMonthFromHeader(String rawHeader) {
        List<String> tokens = tokenizeHeader(rawHeader);
        for (String token : tokens) {
            String month = detectMonthToken(token);
            if (month != null) return month;
        }
        return null;
    }

    private static List<String> tokenizeHeader(String rawHeader) {
        if (rawHeader == null) return List.of();
        String normalized = Normalizer.normalize(rawHeader, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", " ")
            .trim();
        if (normalized.isBlank()) return List.of();
        return List.of(normalized.split("\\s+"));
    }

    private static String detectMonthToken(String token) {
        if (token == null || token.isBlank()) return null;
        return switch (token) {
            case "ene", "enero", "jan", "january" -> "ENERO";
            case "feb", "febrero", "february" -> "FEBRERO";
            case "mar", "marzo", "march" -> "MARZO";
            case "abr", "abril", "apr", "april" -> "ABRIL";
            case "may", "mayo" -> "MAYO";
            case "jun", "junio", "june" -> "JUNIO";
            case "jul", "julio", "july" -> "JULIO";
            case "ago", "agosto", "aug", "august" -> "AGOSTO";
            case "sep", "sept", "septiembre", "september" -> "SEPTIEMBRE";
            case "oct", "octubre", "october" -> "OCTUBRE";
            case "nov", "noviembre", "november" -> "NOVIEMBRE";
            case "dic", "diciembre", "dec", "december" -> "DICIEMBRE";
            default -> null;
        };
    }

    private static boolean isCostePersonalHeader(String rawHeader, String normalizedHeader) {
        if (normalizedHeader.contains("costepersonal")) return true;
        List<String> tokens = tokenizeHeader(rawHeader);
        return (tokens.contains("coste") || tokens.contains("costo") || tokens.contains("cost"))
            && (tokens.contains("personal") || tokens.contains("persona"));
    }

    private static boolean isSsEmpresaHeader(String rawHeader, String normalizedHeader) {
        if (normalizedHeader.contains("ssempresa") || normalizedHeader.contains("seguridadsocialempresa")) return true;
        List<String> tokens = tokenizeHeader(rawHeader);
        return (tokens.contains("ss") || tokens.contains("seguridad"))
            && (tokens.contains("empresa") || tokens.contains("social"));
    }

    private static String safeGet(CSVRecord record, Map<String, String> headers, String normalizedKey) {
        if (normalizedKey == null || normalizedKey.isBlank()) return null;
        String header = headers.get(normalizedKey);
        if (header == null || !record.isMapped(header)) return null;
        String value = record.get(header);
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isBlank() || "-".equals(trimmed) ? null : trimmed;
    }

    private static String trackRaw(CSVRecord record,
                                   Map<String, String> headers,
                                   String column,
                                   List<String> rowWarnings,
                                   String label) {
        String raw = safeGet(record, headers, column);
        if (raw == null) return null;
        if (parseDecimal(raw) == null) rowWarnings.add(label);
        return raw;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static BigDecimal parseDecimal(String raw) {
        if (raw == null) return null;
        String candidate = raw.trim().replace(" ", "");
        if (candidate.isEmpty()) return null;

        int lastComma = candidate.lastIndexOf(',');
        int lastDot = candidate.lastIndexOf('.');
        if (lastComma >= 0 && lastDot >= 0) {
            if (lastComma > lastDot) candidate = candidate.replace(".", "").replace(",", ".");
            else candidate = candidate.replace(",", "");
        } else if (lastComma >= 0) {
            candidate = candidate.replace(",", ".");
        }
        candidate = candidate.replaceAll("[^0-9.\\-]", "");
        if (candidate.isEmpty() || "-".equals(candidate) || ".".equals(candidate)) return null;
        try {
            return new BigDecimal(candidate);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer parseInteger(String raw) {
        BigDecimal decimal = parseDecimal(raw);
        if (decimal == null) return null;
        try {
            return decimal.setScale(0, RoundingMode.HALF_UP).intValueExact();
        } catch (ArithmeticException ex) {
            return decimal.setScale(0, RoundingMode.HALF_UP).intValue();
        }
    }

    private static WorkforceFieldStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "SI" -> WorkforceFieldStatus.OK;
            case "NO" -> WorkforceFieldStatus.NO;
            case "SI-NEGATIVO" -> WorkforceFieldStatus.NEGATIVE;
            case "SI-PDTE", "SI-PDTE DE PAGO" -> WorkforceFieldStatus.PENDING;
            default -> WorkforceFieldStatus.UNKNOWN;
        };
    }

    private static Double sumNullable(List<Double> values) {
        BigDecimal sum = BigDecimal.ZERO;
        int present = 0;
        for (Double value : values) {
            if (value == null) continue;
            sum = sum.add(BigDecimal.valueOf(value));
            present++;
        }
        if (present == 0) return null;
        return round(sum.doubleValue());
    }

    private static Double averageNullable(List<Double> values) {
        BigDecimal sum = BigDecimal.ZERO;
        int present = 0;
        for (Double value : values) {
            if (value == null) continue;
            sum = sum.add(BigDecimal.valueOf(value));
            present++;
        }
        if (present == 0) return null;
        return round(sum.divide(BigDecimal.valueOf(present), 4, RoundingMode.HALF_UP).doubleValue());
    }

    private static Double delta(Double current, Double previous) {
        if (current == null || previous == null) return null;
        return round(BigDecimal.valueOf(current).subtract(BigDecimal.valueOf(previous)).doubleValue());
    }

    private static Double deltaPct(Double base, Double comparison) {
        if (base == null || comparison == null || base == 0d) {
            return null;
        }
        return round(BigDecimal.valueOf(comparison)
            .subtract(BigDecimal.valueOf(base))
            .multiply(BigDecimal.valueOf(100d))
            .divide(BigDecimal.valueOf(base), 4, RoundingMode.HALF_UP)
            .doubleValue());
    }

    private static String deltaPctState(Double base, Double comparison) {
        if (base == null || comparison == null) {
            return "NO_BASELINE";
        }
        if (base == 0d && comparison == 0d) {
            return "ZERO_TO_ZERO";
        }
        if (base == 0d) {
            return "FROM_ZERO";
        }
        return "READY";
    }

    private static Double ratio(Double numerator, long denominator) {
        if (numerator == null || denominator <= 0) return null;
        return round(BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP).doubleValue());
    }

    private static Double ratioPerThousand(Double numerator, Double denominator) {
        if (numerator == null || denominator == null || denominator == 0d) return null;
        return round(BigDecimal.valueOf(numerator)
            .multiply(BigDecimal.valueOf(1000d))
            .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP)
            .doubleValue());
    }

    private static Double inversePerThousand(Double numerator, Double cost) {
        if (numerator == null || cost == null || cost == 0d) return null;
        return round(BigDecimal.valueOf(numerator)
            .multiply(BigDecimal.valueOf(1000d))
            .divide(BigDecimal.valueOf(cost), 4, RoundingMode.HALF_UP)
            .doubleValue());
    }

    private static String readFirstLine(byte[] bytes, Charset charset) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), charset))) {
            return reader.readLine();
        }
    }

    private static String stripBom(String value) {
        if (value == null || value.isEmpty()) return value;
        return value.charAt(0) == '\uFEFF' ? value.substring(1) : value;
    }

    private static char detectDelimiter(String line) {
        int semi = count(line, ';');
        int comma = count(line, ',');
        int tab = count(line, '\t');
        int pipe = count(line, '|');
        if (tab >= semi && tab >= comma && tab >= pipe) return '\t';
        if (semi >= comma && semi >= pipe) return ';';
        if (pipe >= comma) return '|';
        return ',';
    }

    private static int count(String line, char ch) {
        int matches = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == ch) matches++;
        }
        return matches;
    }

    private static Charset detectCharset(byte[] bytes) {
        if (bytes.length >= 3
            && (bytes[0] & 0xFF) == 0xEF
            && (bytes[1] & 0xFF) == 0xBB
            && (bytes[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (bytes.length >= 2) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            if (first == 0xFF && second == 0xFE) return StandardCharsets.UTF_16LE;
            if (first == 0xFE && second == 0xFF) return StandardCharsets.UTF_16BE;
        }
        return StandardCharsets.UTF_8;
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private record DetectedColumns(
        String gestorColumn,
        String minutasColumn,
        String cargaColumn,
        String pctContabilidadColumn,
        String contModelosColumn,
        String isIrpfColumn,
        String ddccColumn,
        String librosColumn,
        String promedioColumn,
        Map<Integer, String> yearColumns,
        List<String> missing,
        List<String> detectedLabels,
        List<Integer> activityYears
    ) {}

    private record DetectedLaborCostColumns(
        String gestorColumn,
        String workerColumn,
        String employeeIdColumn,
        String externalIdColumn,
        String emailColumn,
        String currencyColumn,
        String monthColumn,
        String costePersonalColumn,
        String ssEmpresaColumn,
        Map<String, MonthMetricColumns> monthColumns,
        List<String> missing,
        List<String> detectedLabels,
        Integer detectedYear
    ) {}

    private record MonthMetricColumns(
        String costePersonalColumn,
        String ssEmpresaColumn
    ) {}

    private record ParsedRow(
        String gestor,
        boolean inactive,
        BigDecimal minutas,
        BigDecimal carga,
        BigDecimal pctContabilidad,
        BigDecimal promedio,
        WorkforceFieldStatus contModelos,
        WorkforceFieldStatus isIrpf,
        WorkforceFieldStatus ddcc,
        WorkforceFieldStatus libros,
        Map<Integer, Integer> annualSeatTotals,
        int warningCount
    ) {
        private boolean empty() {
            return isBlank(gestor)
                && minutas == null
                && carga == null
                && pctContabilidad == null
                && promedio == null
                && contModelos == null
                && isIrpf == null
                && ddcc == null
                && libros == null
                && (annualSeatTotals == null || annualSeatTotals.isEmpty());
        }
    }

    private record ParsedLaborCostRow(
        String sourceGestor,
        String sourceWorker,
        String employeeId,
        String externalId,
        String email,
        String currency,
        List<MonthlyCostValue> monthValues,
        int warningCount
    ) {
        private boolean empty() {
            return isBlank(sourceGestor)
                && isBlank(sourceWorker)
                && isBlank(employeeId)
                && isBlank(externalId)
                && isBlank(email)
                && (monthValues == null || monthValues.isEmpty());
        }
    }

    private record MonthlyCostValue(
        String month,
        BigDecimal costePersonal,
        BigDecimal ssEmpresa
    ) {}

    private record ResolvedGestor(
        String gestor,
        String normalizedSource,
        String status,
        String detail
    ) {
        private boolean matched() {
            return "MATCHED".equals(status);
        }

        private static ResolvedGestor match(String gestor, String normalizedSource) {
            return new ResolvedGestor(gestor, normalizedSource, "MATCHED", null);
        }

        private static ResolvedGestor review(String sourceGestor, String normalizedSource, String detail) {
            return new ResolvedGestor(sourceGestor, normalizedSource, "REVIEW", detail);
        }

        private static ResolvedGestor unmatched(String sourceGestor, String normalizedSource, String detail) {
            return new ResolvedGestor(sourceGestor, normalizedSource, "UNMATCHED", detail);
        }
    }

    private record ResolvedWorkerIdentity(
        String canonicalWorkerId,
        String identityStrategy,
        String workerLabel,
        boolean review,
        String detail
    ) {}

    private record WorkforceContext(
        WorkforceImport imported,
        WorkforceSummaryDto summary
    ) {}

    private record EconomicGestor(
        String key,
        String label
    ) {}

    private record TemporalReference(
        String referencePeriod,
        Integer referenceYear,
        Integer referenceMonth,
        Integer startMonth,
        Integer endMonth,
        boolean annualCoverage,
        String label,
        boolean known
    ) {}

    private static final class TemporalCoverageAccumulator {
        private final TemporalReference explicitReference;
        private final Set<Integer> candidateYears = new LinkedHashSet<>();
        private final Set<Integer> months = new LinkedHashSet<>();

        private TemporalCoverageAccumulator(TemporalReference explicitReference) {
            this.explicitReference = explicitReference == null ? unknownTemporalReference() : explicitReference;
            if (this.explicitReference.known() && this.explicitReference.referenceYear() != null) {
                candidateYears.add(this.explicitReference.referenceYear());
            }
        }

        private void registerHeaderYear(Integer year) {
            if (year != null) {
                candidateYears.add(year);
            }
        }

        private void registerRawMonth(String rawMonth, String normalizedMonth) {
            registerMonth(normalizedMonth);
            Integer detectedYear = rawMonth == null ? null : detectSingleYear(List.of(rawMonth));
            if (detectedYear != null) {
                candidateYears.add(detectedYear);
            }
        }

        private void registerMonth(String normalizedMonth) {
            Integer month = monthIndex(normalizedMonth);
            if (month != null) {
                months.add(month);
            }
        }

        private String fallbackMonth() {
            if (!explicitReference.known() || explicitReference.referenceMonth() == null) {
                return null;
            }
            int index = explicitReference.referenceMonth() - 1;
            if (index < 0 || index >= MONTH_SEQUENCE.size()) {
                return null;
            }
            return MONTH_SEQUENCE.get(index);
        }

        private TemporalReference toTemporalReference(List<String> warningDetails) {
            if (months.isEmpty()) {
                if (explicitReference.known() && explicitReference.referenceMonth() != null) {
                    return explicitReference;
                }
                return unknownTemporalReference();
            }
            Integer detectedYear = candidateYears.size() == 1 ? candidateYears.iterator().next() : null;
            Integer explicitYear = explicitReference.known() ? explicitReference.referenceYear() : null;
            if (explicitYear != null && detectedYear != null && !explicitYear.equals(detectedYear)) {
                if (warningDetails.size() < 5) {
                    warningDetails.add("Cobertura temporal ambigua en costes laborales: ejercicio indicado y meses detectados no coinciden.");
                }
                return unknownTemporalReference();
            }
            if (explicitReference.known() && explicitReference.referenceMonth() != null) {
                if (!months.contains(explicitReference.referenceMonth())) {
                    if (warningDetails.size() < 5) {
                        warningDetails.add("Cobertura temporal ambigua en costes laborales: el mes indicado no coincide con los meses detectados.");
                    }
                    return unknownTemporalReference();
                }
                return explicitReference;
            }
            Integer resolvedYear = explicitYear != null ? explicitYear : detectedYear;
            if (resolvedYear == null) {
                return unknownTemporalReference();
            }
            int start = months.stream().min(Integer::compareTo).orElse(1);
            int end = months.stream().max(Integer::compareTo).orElse(start);
            boolean annual = months.size() == 12 && start == 1 && end == 12;
            if (annual) {
                return new TemporalReference(
                    String.valueOf(resolvedYear),
                    resolvedYear,
                    null,
                    1,
                    12,
                    true,
                    referenceLabel(resolvedYear, 1, 12, true),
                    true
                );
            }
            if (start == end) {
                String key = "%04d-%02d".formatted(resolvedYear, start);
                return new TemporalReference(
                    key,
                    resolvedYear,
                    start,
                    start,
                    end,
                    false,
                    referenceLabel(resolvedYear, start, end, false),
                    true
                );
            }
            String key = "%04d-%02d..%04d-%02d".formatted(resolvedYear, start, resolvedYear, end);
            return new TemporalReference(
                key,
                resolvedYear,
                end,
                start,
                end,
                false,
                referenceLabel(resolvedYear, start, end, false),
                true
            );
        }
    }

    private static final class ReviewAccumulator {
        private final String sourceGestor;
        private final String normalizedGestor;
        private final String status;
        private final String detail;
        private long rows;

        private ReviewAccumulator(String sourceGestor, String normalizedGestor, String status, String detail) {
            this.sourceGestor = sourceGestor;
            this.normalizedGestor = normalizedGestor;
            this.status = status;
            this.detail = detail;
        }

        private void increment() {
            rows++;
        }

        private long rows() {
            return rows;
        }

        private String sourceGestor() {
            return sourceGestor == null ? "SIN GESTOR" : sourceGestor;
        }

        private WorkforceLaborCostReviewDto toDto() {
            return new WorkforceLaborCostReviewDto(
                sourceGestor(),
                normalizedGestor == null || normalizedGestor.isBlank() ? null : normalizedGestor,
                status,
                detail,
                rows
            );
        }
    }

    private static final class LaborCostAccumulator {
        private final String gestor;
        private final Map<String, BigDecimal> personalByMonth = new LinkedHashMap<>();
        private final Map<String, BigDecimal> ssByMonth = new LinkedHashMap<>();

        private LaborCostAccumulator(String gestor) {
            this.gestor = gestor;
        }

        private void add(List<MonthlyCostValue> values) {
            if (values == null) return;
            for (MonthlyCostValue value : values) {
                if (value == null || value.month() == null) continue;
                if (value.costePersonal() != null) {
                    personalByMonth.merge(value.month(), value.costePersonal(), BigDecimal::add);
                }
                if (value.ssEmpresa() != null) {
                    ssByMonth.merge(value.month(), value.ssEmpresa(), BigDecimal::add);
                }
            }
        }

        private void add(Map<String, Double> personalValues, Map<String, Double> ssValues) {
            if (personalValues != null) {
                for (Map.Entry<String, Double> entry : personalValues.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) continue;
                    personalByMonth.merge(entry.getKey(), BigDecimal.valueOf(entry.getValue()), BigDecimal::add);
                }
            }
            if (ssValues != null) {
                for (Map.Entry<String, Double> entry : ssValues.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) continue;
                    ssByMonth.merge(entry.getKey(), BigDecimal.valueOf(entry.getValue()), BigDecimal::add);
                }
            }
        }

        private WorkforceLaborCostGestorDto toDto() {
            Double personalAnnual = sumMap(personalByMonth);
            Double ssAnnual = sumMap(ssByMonth);
            return new WorkforceLaborCostGestorDto(
                gestor,
                roundMonthMap(personalByMonth),
                roundMonthMap(ssByMonth),
                totalMonthMap(),
                personalAnnual,
                ssAnnual,
                sumNullable(List.of(personalAnnual, ssAnnual))
            );
        }

        private List<WorkforceMonthlyCostDto> toMonthlyTotals() {
            List<WorkforceMonthlyCostDto> rows = new ArrayList<>();
            for (String month : orderedMonths()) {
                Double personal = roundNullable(personalByMonth.get(month));
                Double ss = roundNullable(ssByMonth.get(month));
                Double total = sumNullable(List.of(personal, ss));
                if (personal == null && ss == null && total == null) continue;
                rows.add(new WorkforceMonthlyCostDto(month, personal, ss, total));
            }
            return rows;
        }

        private Map<String, Double> totalMonthMap() {
            Map<String, Double> out = new LinkedHashMap<>();
            for (String month : orderedMonths()) {
                Double personal = roundNullable(personalByMonth.get(month));
                Double ss = roundNullable(ssByMonth.get(month));
                Double total = sumNullable(List.of(personal, ss));
                if (total != null) out.put(month, total);
            }
            return out;
        }

        private Map<String, Double> roundMonthMap(Map<String, BigDecimal> input) {
            Map<String, Double> out = new LinkedHashMap<>();
            for (String month : orderedMonths()) {
                Double value = roundNullable(input.get(month));
                if (value != null) out.put(month, value);
            }
            return out;
        }

        private List<String> orderedMonths() {
            Set<String> seen = new LinkedHashSet<>();
            for (String month : MONTH_SEQUENCE) {
                if (personalByMonth.containsKey(month) || ssByMonth.containsKey(month)) seen.add(month);
            }
            seen.addAll(personalByMonth.keySet());
            seen.addAll(ssByMonth.keySet());
            return List.copyOf(seen);
        }

        private static Double sumMap(Map<String, BigDecimal> values) {
            if (values == null || values.isEmpty()) return null;
            BigDecimal sum = BigDecimal.ZERO;
            int present = 0;
            for (BigDecimal value : values.values()) {
                if (value == null) continue;
                sum = sum.add(value);
                present++;
            }
            if (present == 0) return null;
            return round(sum.doubleValue());
        }

        private static Double roundNullable(BigDecimal value) {
            return value == null ? null : round(value.doubleValue());
        }
    }

    private static final class CanonicalWorkerAccumulator {
        private final String canonicalWorkerId;
        private final String identityStrategy;
        private final String workerLabel;
        private final String sourceWorker;
        private final String sourceGestor;
        private final String normalizedGestor;
        private final Map<String, BigDecimal> personalByMonth = new LinkedHashMap<>();
        private final Map<String, BigDecimal> ssByMonth = new LinkedHashMap<>();
        private final Set<String> matchedGestores = new LinkedHashSet<>();
        private final Set<String> currencies = new LinkedHashSet<>();
        private final Set<String> issueDetails = new LinkedHashSet<>();
        private boolean review;
        private boolean unmatched;

        private CanonicalWorkerAccumulator(ResolvedWorkerIdentity identity,
                                           ParsedLaborCostRow row,
                                           ResolvedGestor gestor) {
            this.canonicalWorkerId = identity == null ? null : identity.canonicalWorkerId();
            this.identityStrategy = identity == null ? null : identity.identityStrategy();
            this.workerLabel = firstNonBlank(identity == null ? null : identity.workerLabel(), row == null ? null : row.sourceWorker(), row == null ? null : row.sourceGestor(), "Sin identidad");
            this.sourceWorker = row == null ? null : row.sourceWorker();
            this.sourceGestor = row == null ? null : row.sourceGestor();
            this.normalizedGestor = gestor == null ? null : gestor.normalizedSource();
            if (identity != null && identity.review() && !isBlank(identity.detail())) {
                review = true;
                issueDetails.add(identity.detail());
            }
            registerResolution(gestor);
        }

        private void addRow(ParsedLaborCostRow row,
                            ResolvedWorkerIdentity identity,
                            ResolvedGestor gestor) {
            if (row != null && row.monthValues() != null) {
                for (MonthlyCostValue value : row.monthValues()) {
                    if (value == null || value.month() == null) continue;
                    if (value.costePersonal() != null) {
                        personalByMonth.merge(value.month(), value.costePersonal(), BigDecimal::add);
                    }
                    if (value.ssEmpresa() != null) {
                        ssByMonth.merge(value.month(), value.ssEmpresa(), BigDecimal::add);
                    }
                }
            }
            String currency = normalizeCurrencyCode(row == null ? null : row.currency());
            if (currency != null) {
                currencies.add(currency);
            }
            if (identity != null && identity.review() && !isBlank(identity.detail())) {
                review = true;
                issueDetails.add(identity.detail());
            }
            registerResolution(gestor);
            if (currencies.size() > 1) {
                review = true;
                issueDetails.add("REVIEW: el trabajador aparece con múltiples monedas dentro del mismo periodo.");
            }
            if (matchedGestores.size() > 1) {
                review = true;
                issueDetails.add("REVIEW: el trabajador aparece asociado a varios gestores dentro del mismo periodo.");
            }
        }

        private void registerResolution(ResolvedGestor gestor) {
            if (gestor == null) {
                unmatched = true;
                return;
            }
            if ("MATCHED".equals(gestor.status())) {
                matchedGestores.add(gestor.gestor());
                return;
            }
            if ("UNMATCHED".equals(gestor.status())) {
                unmatched = true;
            } else {
                review = true;
            }
            if (!isBlank(gestor.detail())) {
                issueDetails.add(gestor.detail());
            }
        }

        private WorkforceLaborCostWorkerDto toDto() {
            String matchingState = review || matchedGestores.size() > 1
                ? "REVIEW"
                : unmatched || matchedGestores.isEmpty()
                    ? "UNMATCHED"
                    : "MATCHED";
            Double personalAnnual = LaborCostAccumulator.sumMap(personalByMonth);
            Double ssAnnual = LaborCostAccumulator.sumMap(ssByMonth);
            String currency = currencies.size() == 1 ? currencies.iterator().next() : null;
            return new WorkforceLaborCostWorkerDto(
                canonicalWorkerId,
                identityStrategy,
                workerLabel,
                sourceWorker,
                sourceGestor,
                normalizedGestor,
                matchingState.equals("MATCHED") && matchedGestores.size() == 1 ? matchedGestores.iterator().next() : null,
                matchingState,
                issueDetails.isEmpty() ? null : String.join(" | ", issueDetails),
                currency,
                roundMonthMap(personalByMonth),
                roundMonthMap(ssByMonth),
                totalMonthMap(personalByMonth, ssByMonth),
                personalAnnual,
                ssAnnual,
                sumNullable(List.of(personalAnnual, ssAnnual))
            );
        }

        private static Map<String, Double> roundMonthMap(Map<String, BigDecimal> input) {
            Map<String, Double> out = new LinkedHashMap<>();
            for (String month : orderedMonths(input, Map.of())) {
                Double value = LaborCostAccumulator.roundNullable(input.get(month));
                if (value != null) {
                    out.put(month, value);
                }
            }
            return out;
        }

        private static Map<String, Double> totalMonthMap(Map<String, BigDecimal> personal, Map<String, BigDecimal> ss) {
            Map<String, Double> out = new LinkedHashMap<>();
            for (String month : orderedMonths(personal, ss)) {
                Double total = sumNullable(List.of(
                    LaborCostAccumulator.roundNullable(personal.get(month)),
                    LaborCostAccumulator.roundNullable(ss.get(month))
                ));
                if (total != null) {
                    out.put(month, total);
                }
            }
            return out;
        }

        private static List<String> orderedMonths(Map<String, BigDecimal> personal, Map<String, BigDecimal> ss) {
            Set<String> seen = new LinkedHashSet<>();
            for (String month : MONTH_SEQUENCE) {
                if (personal.containsKey(month) || ss.containsKey(month)) {
                    seen.add(month);
                }
            }
            seen.addAll(personal.keySet());
            seen.addAll(ss.keySet());
            return List.copyOf(seen);
        }
    }

    private static final class ManagerComparisonAccumulator {
        private final String gestor;
        private long workers;
        private BigDecimal total = BigDecimal.ZERO;

        private ManagerComparisonAccumulator(String gestor) {
            this.gestor = gestor;
        }

        private void add(WorkforceLaborCostWorkerDto worker) {
            workers++;
            if (worker != null && worker.costeLaboralAnual() != null) {
                total = total.add(BigDecimal.valueOf(worker.costeLaboralAnual()));
            }
        }

        private long workers() {
            return workers;
        }

        private Double total() {
            return workers == 0 ? null : round(total.doubleValue());
        }
    }

    private static final class GestorAccumulator {
        private final String gestor;
        private long totalClients;
        private long activeClients;
        private long inactiveClients;
        private BigDecimal totalMinutas = BigDecimal.ZERO;
        private long totalMinutasCount;
        private BigDecimal totalCarga = BigDecimal.ZERO;
        private long totalCargaCount;
        private BigDecimal totalVolumenAsientos = BigDecimal.ZERO;
        private long totalVolumenCount;
        private BigDecimal pctContabilidadSum = BigDecimal.ZERO;
        private long pctContabilidadCount;
        private long contModelosOk;
        private long isIrpfOk;
        private long ddccOk;
        private long librosOk;
        private long contModelosNo;
        private long contModelosNegative;
        private long contModelosPending;
        private long contModelosUnknown;
        private long isIrpfNo;
        private long isIrpfNegative;
        private long isIrpfPending;
        private long isIrpfUnknown;
        private long ddccNo;
        private long ddccNegative;
        private long ddccPending;
        private long ddccUnknown;
        private long librosNo;
        private long librosNegative;
        private long librosPending;
        private long librosUnknown;
        private final Map<Integer, Long> annualSeatTotals = new TreeMap<>();

        private GestorAccumulator(String gestor) {
            this.gestor = gestor;
        }

        private void add(ParsedRow row) {
            totalClients++;
            if (row.inactive()) inactiveClients++;
            else activeClients++;
            if (row.minutas() != null) {
                totalMinutas = totalMinutas.add(row.minutas());
                totalMinutasCount++;
            }
            if (row.carga() != null) {
                totalCarga = totalCarga.add(row.carga());
                totalCargaCount++;
            }
            if (row.promedio() != null) {
                totalVolumenAsientos = totalVolumenAsientos.add(row.promedio());
                totalVolumenCount++;
            }
            if (row.pctContabilidad() != null) {
                pctContabilidadSum = pctContabilidadSum.add(row.pctContabilidad());
                pctContabilidadCount++;
            }
            applyStatus(row.contModelos(), FieldKind.CONT_MODELOS);
            applyStatus(row.isIrpf(), FieldKind.IS_IRPF);
            applyStatus(row.ddcc(), FieldKind.DDCC);
            applyStatus(row.libros(), FieldKind.LIBROS);
            if (row.annualSeatTotals() != null) {
                for (Map.Entry<Integer, Integer> entry : row.annualSeatTotals().entrySet()) {
                    annualSeatTotals.merge(entry.getKey(), entry.getValue().longValue(), Long::sum);
                }
            }
        }

        private void applyStatus(WorkforceFieldStatus status, FieldKind fieldKind) {
            if (status == null) return;
            switch (fieldKind) {
                case CONT_MODELOS -> {
                    switch (status) {
                        case OK -> contModelosOk++;
                        case NO -> contModelosNo++;
                        case NEGATIVE -> contModelosNegative++;
                        case PENDING -> contModelosPending++;
                        case UNKNOWN -> contModelosUnknown++;
                    }
                }
                case IS_IRPF -> {
                    switch (status) {
                        case OK -> isIrpfOk++;
                        case NO -> isIrpfNo++;
                        case NEGATIVE -> isIrpfNegative++;
                        case PENDING -> isIrpfPending++;
                        case UNKNOWN -> isIrpfUnknown++;
                    }
                }
                case DDCC -> {
                    switch (status) {
                        case OK -> ddccOk++;
                        case NO -> ddccNo++;
                        case NEGATIVE -> ddccNegative++;
                        case PENDING -> ddccPending++;
                        case UNKNOWN -> ddccUnknown++;
                    }
                }
                case LIBROS -> {
                    switch (status) {
                        case OK -> librosOk++;
                        case NO -> librosNo++;
                        case NEGATIVE -> librosNegative++;
                        case PENDING -> librosPending++;
                        case UNKNOWN -> librosUnknown++;
                    }
                }
            }
        }

        private WorkforceGestorDto toDto() {
            Double pctMedio = pctContabilidadCount == 0
                ? null
                : round(pctContabilidadSum.divide(BigDecimal.valueOf(pctContabilidadCount), 4, RoundingMode.HALF_UP).doubleValue());
            return new WorkforceGestorDto(
                gestor,
                totalClients,
                activeClients,
                inactiveClients,
                totalMinutasCount == 0 ? null : round(totalMinutas.doubleValue()),
                totalCargaCount == 0 ? null : round(totalCarga.doubleValue()),
                totalCargaCount == 0 ? null : round(totalCarga.divide(BigDecimal.valueOf(totalCargaCount), 4, RoundingMode.HALF_UP).doubleValue()),
                totalVolumenCount == 0 ? null : round(totalVolumenAsientos.doubleValue()),
                pctMedio,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                contModelosOk,
                isIrpfOk,
                ddccOk,
                librosOk,
                new WorkforceStatusBreakdownDto(contModelosOk, contModelosNo, contModelosNegative, contModelosPending, contModelosUnknown),
                new WorkforceStatusBreakdownDto(isIrpfOk, isIrpfNo, isIrpfNegative, isIrpfPending, isIrpfUnknown),
                new WorkforceStatusBreakdownDto(ddccOk, ddccNo, ddccNegative, ddccPending, ddccUnknown),
                new WorkforceStatusBreakdownDto(librosOk, librosNo, librosNegative, librosPending, librosUnknown),
                new TreeMap<>(annualSeatTotals)
            );
        }
    }

    private enum WorkforceFieldStatus {
        OK,
        NO,
        NEGATIVE,
        PENDING,
        UNKNOWN
    }

    private enum FieldKind {
        CONT_MODELOS,
        IS_IRPF,
        DDCC,
        LIBROS
    }
}

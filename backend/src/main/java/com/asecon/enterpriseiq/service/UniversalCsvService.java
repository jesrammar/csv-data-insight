package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.UniversalBucketDto;
import com.asecon.enterpriseiq.dto.UniversalColumnDto;
import com.asecon.enterpriseiq.dto.UniversalCorrelationDto;
import com.asecon.enterpriseiq.dto.UniversalInsightDto;
import com.asecon.enterpriseiq.dto.UniversalSummaryDto;
import com.asecon.enterpriseiq.dto.UniversalTopValueDto;
import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.model.UniversalImport;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.asecon.enterpriseiq.repo.UniversalImportRepository;
import com.asecon.enterpriseiq.metrics.ErrorTagger;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UniversalCsvService {
    private static final Logger log = LoggerFactory.getLogger(UniversalCsvService.class);

    private static final int MAX_VALUES_FOR_STATS = 20000;
    private static final int MAX_UNIQUE_VALUES = 2000;
    private static final int TOP_VALUES = 5;
    private static final int HISTOGRAM_BINS = 10;
    private static final int MAX_DATE_SERIES = 24;
    private static final int MAX_CORR_COLS = 8;
    private static final int MAX_CORR_ROWS = 5000;

    private final CompanyRepository companyRepository;
    private final UniversalImportRepository importRepository;
    private final ObjectMapper objectMapper;
    private final TabularFileService tabularFileService;
    private final UniversalStorageService universalStorageService;
    private final int maxAnalyzeRowsDefault;
    private final int maxAnalyzeSecondsDefault;
    private final int maxAnalyzeRowsBronze;
    private final int maxAnalyzeRowsGold;
    private final int maxAnalyzeRowsPlatinum;
    private final int maxAnalyzeSecondsBronze;
    private final int maxAnalyzeSecondsGold;
    private final int maxAnalyzeSecondsPlatinum;
    private final MeterRegistry meterRegistry;
    private final ErrorTagger errorTagger;

    public UniversalCsvService(CompanyRepository companyRepository,
                               UniversalImportRepository importRepository,
                               ObjectMapper objectMapper,
                               TabularFileService tabularFileService,
                               UniversalStorageService universalStorageService,
                               @Value("${app.universal.max-analyze-rows:50000}") int maxAnalyzeRows,
                               @Value("${app.universal.max-analyze-rows-by-plan.bronze:15000}") int maxAnalyzeRowsBronze,
                               @Value("${app.universal.max-analyze-rows-by-plan.gold:50000}") int maxAnalyzeRowsGold,
                               @Value("${app.universal.max-analyze-rows-by-plan.platinum:100000}") int maxAnalyzeRowsPlatinum,
                               @Value("${app.universal.max-seconds:25}") int maxAnalyzeSeconds,
                               @Value("${app.universal.max-seconds-by-plan.bronze:15}") int maxAnalyzeSecondsBronze,
                               @Value("${app.universal.max-seconds-by-plan.gold:25}") int maxAnalyzeSecondsGold,
                               @Value("${app.universal.max-seconds-by-plan.platinum:45}") int maxAnalyzeSecondsPlatinum,
                               MeterRegistry meterRegistry,
                               ErrorTagger errorTagger) {
        this.companyRepository = companyRepository;
        this.importRepository = importRepository;
        this.objectMapper = objectMapper;
        this.tabularFileService = tabularFileService;
        this.universalStorageService = universalStorageService;
        this.maxAnalyzeRowsDefault = Math.max(5_000, maxAnalyzeRows);
        this.maxAnalyzeSecondsDefault = Math.max(5, maxAnalyzeSeconds);
        this.maxAnalyzeRowsBronze = Math.max(1_000, maxAnalyzeRowsBronze);
        this.maxAnalyzeRowsGold = Math.max(5_000, maxAnalyzeRowsGold);
        this.maxAnalyzeRowsPlatinum = Math.max(5_000, maxAnalyzeRowsPlatinum);
        this.maxAnalyzeSecondsBronze = Math.max(5, maxAnalyzeSecondsBronze);
        this.maxAnalyzeSecondsGold = Math.max(5, maxAnalyzeSecondsGold);
        this.maxAnalyzeSecondsPlatinum = Math.max(5, maxAnalyzeSecondsPlatinum);
        this.meterRegistry = meterRegistry;
        this.errorTagger = errorTagger;
    }

    int effectiveMaxAnalyzeRows(Plan plan) {
        if (plan == null) return maxAnalyzeRowsDefault;
        return switch (plan) {
            case BRONZE -> maxAnalyzeRowsBronze > 0 ? maxAnalyzeRowsBronze : maxAnalyzeRowsDefault;
            case GOLD -> maxAnalyzeRowsGold > 0 ? maxAnalyzeRowsGold : maxAnalyzeRowsDefault;
            case PLATINUM -> maxAnalyzeRowsPlatinum > 0 ? maxAnalyzeRowsPlatinum : maxAnalyzeRowsDefault;
        };
    }

    int effectiveMaxAnalyzeSeconds(Plan plan) {
        if (plan == null) return maxAnalyzeSecondsDefault;
        return switch (plan) {
            case BRONZE -> maxAnalyzeSecondsBronze > 0 ? maxAnalyzeSecondsBronze : maxAnalyzeSecondsDefault;
            case GOLD -> maxAnalyzeSecondsGold > 0 ? maxAnalyzeSecondsGold : maxAnalyzeSecondsDefault;
            case PLATINUM -> maxAnalyzeSecondsPlatinum > 0 ? maxAnalyzeSecondsPlatinum : maxAnalyzeSecondsDefault;
        };
    }

    @Transactional
    public UniversalSummaryDto analyzeAndStore(Long companyId, MultipartFile file, Plan plan, TabularFileService.XlsxOptions xlsxOptions) throws IOException {
        Company company = companyRepository.findById(companyId).orElseThrow();

        var tabular = tabularFileService.toCsv(file, xlsxOptions);
        try {
            DistributionSummary.builder("ingestion.universal.upload.bytes")
                .baseUnit("bytes")
                .tag("convertedFromXlsx", String.valueOf(tabular.convertedFromXlsx()))
                .register(meterRegistry)
                .record(tabular.bytes().length);
            Counter.builder("ingestion.universal.upload.count")
                .tag("convertedFromXlsx", String.valueOf(tabular.convertedFromXlsx()))
                .register(meterRegistry)
                .increment();
        } catch (Exception ignored) {}

        String displayFilename = file.getOriginalFilename() == null
            ? (tabular.convertedFromXlsx() ? "data.xlsx" : "data.csv")
            : file.getOriginalFilename();

        Charset charset = tabular.charset();
        if (charset == null) {
            charset = detectCharset(tabular.bytes());
        }

        UniversalImport imp = new UniversalImport();
        imp.setCompany(company);
        imp.setFilename(displayFilename);
        imp.setCreatedAt(Instant.now());
        imp.setRowCount(0);
        imp.setColumnCount(0);
        imp.setSummaryJson("{}");
        imp = importRepository.save(imp);

        var csvPath = universalStorageService.writeNormalizedCsv(imp.getId(), tabular.bytes());
        imp.setStorageRef(csvPath.toString());

        AnalysisResult result;
        long analyzeStartNs = System.nanoTime();
        try {
            result = analyze(displayFilename, tabular.bytes(), charset, plan, imp.getCreatedAt());
        } catch (ResponseStatusException ex) {
            recordUniversalAnalyzeFailure(tabular.bytes().length, tabular.convertedFromXlsx(), analyzeStartNs, ex);
            throw ex;
        } catch (IllegalArgumentException ex) {
            recordUniversalAnalyzeFailure(tabular.bytes().length, tabular.convertedFromXlsx(), analyzeStartNs, ex);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (RuntimeException ex) {
            recordUniversalAnalyzeFailure(tabular.bytes().length, tabular.convertedFromXlsx(), analyzeStartNs, ex);
            throw ex;
        }
        String json = objectMapper.writeValueAsString(result.summary());

        imp.setRowCount(result.summary().rowCount());
        imp.setColumnCount(result.summary().columnCount());
        imp.setSummaryJson(json);
        imp = importRepository.save(imp);

        try {
            log.info(
                "METRIC ingestion.universal.analyze companyId={} universalImportId={} plan={} fileBytes={} charset={} delimiter={} durationMs={} totalRowsRead={} goodRows={} badRows={} analyzedRows={} sampled={} columns={} removedEmptyColumns={} correlations={}",
                companyId,
                imp.getId(),
                plan == null ? null : plan.name(),
                result.bytes(),
                result.charsetName(),
                String.valueOf(result.delimiter()),
                result.durationMs(),
                result.totalRowsRead(),
                result.goodRows(),
                result.badRows(),
                result.summary().rowCount(),
                result.sampled(),
                result.summary().columnCount(),
                result.removedEmptyColumns(),
                result.summary().correlations() == null ? 0 : result.summary().correlations().size()
            );
        } catch (Exception ignored) {}

        try {
            Timer.builder("ingestion.universal.analyze.duration")
                .tag("result", "ok")
                .tag("sampled", String.valueOf(result.sampled()))
                .tag("convertedFromXlsx", String.valueOf(tabular.convertedFromXlsx()))
                .register(meterRegistry)
                .record(result.durationMs(), java.util.concurrent.TimeUnit.MILLISECONDS);

            DistributionSummary.builder("ingestion.universal.analyze.bytes")
                .baseUnit("bytes")
                .register(meterRegistry)
                .record(result.bytes());
            DistributionSummary.builder("ingestion.universal.analyze.rows.total_read")
                .baseUnit("rows")
                .register(meterRegistry)
                .record(result.totalRowsRead());
            DistributionSummary.builder("ingestion.universal.analyze.rows.observed")
                .baseUnit("rows")
                .register(meterRegistry)
                .record(result.observedRows());
            DistributionSummary.builder("ingestion.universal.analyze.rows.analyzed")
                .baseUnit("rows")
                .register(meterRegistry)
                .record(result.summary().rowCount());
            DistributionSummary.builder("ingestion.universal.analyze.rows.good")
                .baseUnit("rows")
                .register(meterRegistry)
                .record(result.goodRows());
            DistributionSummary.builder("ingestion.universal.analyze.rows.bad")
                .baseUnit("rows")
                .register(meterRegistry)
                .record(result.badRows());
            Counter.builder("ingestion.universal.analyze.count")
                .tag("result", "ok")
                .tag("sampled", String.valueOf(result.sampled()))
                .register(meterRegistry)
                .increment();
        } catch (Exception ignored) {}

        return new UniversalSummaryDto(
            imp.getId(),
            result.summary().filename(),
            result.summary().createdAt(),
            result.summary().rowCount(),
            result.summary().columnCount(),
            result.summary().columns(),
            result.summary().correlations(),
            result.summary().insights() == null ? List.of() : result.summary().insights()
        );
    }

    private void recordUniversalAnalyzeFailure(long bytes, boolean convertedFromXlsx, long startNs, Exception ex) {
        try {
            long durMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            String error = safeErrorTag(ex);

            Timer.builder("ingestion.universal.analyze.duration")
                .tag("result", "failed")
                .tag("error", error)
                .tag("convertedFromXlsx", String.valueOf(convertedFromXlsx))
                .register(meterRegistry)
                .record(durMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            DistributionSummary.builder("ingestion.universal.analyze.bytes")
                .baseUnit("bytes")
                .register(meterRegistry)
                .record(bytes);
            Counter.builder("ingestion.universal.analyze.count")
                .tag("result", "failed")
                .tag("error", error)
                .register(meterRegistry)
                .increment();
        } catch (Exception ignored) {}
    }

    private String safeErrorTag(Exception ex) {
        return errorTagger.tag(ex);
    }

    @Transactional(readOnly = true)
    public Optional<UniversalSummaryDto> latest(Long companyId) {
        return importRepository.findFirstByCompanyIdOrderByCreatedAtDesc(companyId)
            .map(imp -> {
                try {
                    UniversalSummaryDto summary = objectMapper.readValue(imp.getSummaryJson(), UniversalSummaryDto.class);
                    return new UniversalSummaryDto(
                        imp.getId(),
                        summary.filename(),
                        summary.createdAt(),
                        summary.rowCount(),
                        summary.columnCount(),
                        summary.columns(),
                        summary.correlations(),
                        summary.insights() == null ? List.of() : summary.insights()
                    );
                } catch (Exception ex) {
                    return null;
                }
            });
    }

    @Transactional(readOnly = true)
    public Optional<UniversalSummaryDto> summary(Long companyId, Long importId) {
        if (importId == null) return latest(companyId);
        return importRepository.findByIdAndCompanyId(importId, companyId)
            .map(imp -> {
                try {
                    UniversalSummaryDto summary = objectMapper.readValue(imp.getSummaryJson(), UniversalSummaryDto.class);
                    return new UniversalSummaryDto(
                        imp.getId(),
                        summary.filename(),
                        summary.createdAt(),
                        summary.rowCount(),
                        summary.columnCount(),
                        summary.columns(),
                        summary.correlations(),
                        summary.insights() == null ? List.of() : summary.insights()
                    );
                } catch (Exception ex) {
                    return null;
                }
            });
    }

    private AnalysisResult analyze(String filename, byte[] bytes, Charset charset, Plan plan, Instant createdAt) throws IOException {
        long startNs = System.nanoTime();
        BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), charset));
        String firstLine = reader.readLine();
        if (firstLine == null) {
            throw new IllegalArgumentException("CSV vacío");
        }
        firstLine = stripBom(firstLine);
        char delimiter = detectDelimiter(firstLine);

        CSVParser parser;
        try {
            parser = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setAllowMissingColumnNames(true)
                .setIgnoreEmptyLines(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .build()
                .parse(new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), charset)));
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                "CSV malformado o no tabular. Si viene de Excel con varias tablas/gráficas, sube el XLSX original (recomendado) o re-exporta como CSV UTF-8 (una sola tabla con cabeceras en la primera fila).",
                ex
            );
        }

        List<String> headers = new ArrayList<>(parser.getHeaderMap().keySet()).stream()
            .map(UniversalCsvService::stripBom)
            .collect(Collectors.toList());
        if (headers.isEmpty()) {
            throw new IllegalArgumentException("CSV sin encabezados");
        }

        Map<String, ColumnStats> stats = new HashMap<>();
        for (String header : headers) {
            stats.put(header, new ColumnStats(header));
        }

        int rowCount = 0;
        int goodRows = 0;
        int badRows = 0;
        int corrRows = 0;
        int observedRows = 0;
        boolean sampled = false;
        List<String> numericHeaders = new ArrayList<>();
        int effectiveMaxRows = effectiveMaxAnalyzeRows(plan);
        int effectiveMaxSeconds = effectiveMaxAnalyzeSeconds(plan);

        for (CSVRecord record : parser) {
            rowCount++;
            if ((rowCount % 500) == 0) {
                long elapsedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
                if (elapsedMs > (long) effectiveMaxSeconds * 1000L) {
                    throw new ResponseStatusException(
                        HttpStatus.REQUEST_TIMEOUT,
                        "Tiempo de análisis agotado (" + effectiveMaxSeconds + "s). Recomendación: exporta menos filas/columnas o sube el fichero por periodos."
                    );
                }
            }
            if (record.size() < headers.size()) {
                badRows++;
                continue;
            }
            goodRows++;
            boolean doObserve = goodRows <= effectiveMaxRows;
            if (!doObserve) {
                sampled = true;
                continue;
            }
            observedRows++;
            for (String header : headers) {
                ColumnStats col = stats.get(header);
                String raw;
                try {
                    raw = clean(record.get(header));
                } catch (Exception ex) {
                    raw = null;
                }
                col.observe(raw);
            }
        }

        if (goodRows == 0) {
            throw new IllegalArgumentException(
                "No he podido leer ninguna fila válida. Esto suele pasar cuando el CSV tiene varias tablas, filas de títulos sueltas o comillas/saltos de línea mal exportados. Sube el XLSX original o exporta una sola tabla a CSV UTF-8."
            );
        }

        // Reportamos filas realmente analizadas (ignorando filas truncadas/irregulares).
        int totalRowsRead = rowCount;
        rowCount = sampled ? observedRows : goodRows;

        for (ColumnStats col : stats.values()) {
            col.finalizeType();
        }

        int removedEmptyColumns = 0;
        var it = stats.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if ("empty".equals(e.getValue().detectedType)) {
                it.remove();
                removedEmptyColumns++;
            }
        }

        numericHeaders = stats.values().stream()
            .filter(c -> "number".equals(c.detectedType))
            .map(c -> c.name)
            .collect(Collectors.toList());

        List<UniversalCorrelationDto> correlations = Collections.emptyList();
        boolean allowCorrelations = plan != null && plan.isAtLeast(Plan.GOLD);
        int maxCorrCols = plan != null && plan.isAtLeast(Plan.PLATINUM) ? 12 : MAX_CORR_COLS;
        if (allowCorrelations && numericHeaders.size() >= 2 && numericHeaders.size() <= maxCorrCols) {
            correlations = computeCorrelations(bytes, charset, delimiter, numericHeaders);
            corrRows = correlations.isEmpty() ? 0 : 1;
        }

        List<UniversalColumnDto> columns = stats.values().stream()
            .sorted(Comparator.comparing(c -> c.name.toLowerCase(Locale.ROOT)))
            .map(ColumnStats::toDto)
            .collect(Collectors.toList());

        if (plan == null || !plan.isAtLeast(Plan.GOLD)) {
            correlations = Collections.emptyList();
        }

        if (plan != null && plan == Plan.BRONZE) {
            columns = columns.stream()
                .map(c -> new UniversalColumnDto(
                    c.name(),
                    c.detectedType(),
                    c.totalCount(),
                    c.nullCount(),
                    c.uniqueCount(),
                    c.min(),
                    c.max(),
                    c.mean(),
                    null,
                    null,
                    c.dateMin(),
                    c.dateMax(),
                    c.topValues() == null ? List.of() : c.topValues().stream().limit(3).collect(Collectors.toList()),
                    List.of(),
                    List.of()
                ))
                .collect(Collectors.toList());
            correlations = Collections.emptyList();
        }

        List<UniversalInsightDto> insights = buildInsights(plan, headers, columns, correlations, bytes, charset, delimiter);
        if (sampled) {
            insights.add(0, new UniversalInsightDto(
                "info",
                "Muestreo por rendimiento",
                "He analizado " + rowCount + " fila(s) de muestra para calcular estadísticas más rápido (total leído: " + goodRows + "). Si necesitas precisión completa, sube el fichero por periodos o reduce columnas."
            ));
        }
        if (badRows > 0) {
            insights.add(0, new UniversalInsightDto(
                "warning",
                "CSV irregular",
                "He ignorado " + badRows + " fila(s) porque no tenían el mismo número de columnas que la cabecera. Para mejores resultados, sube el XLSX original o exporta una sola tabla limpia a CSV UTF-8."
            ));
        }
        if (removedEmptyColumns > 0) {
            insights.add(0, new UniversalInsightDto(
                "info",
                "Limpieza automática",
                "He ignorado " + removedEmptyColumns + " columna(s) vacía(s) para reducir ruido en el análisis."
            ));
        }
        if (plan == null || plan == Plan.BRONZE) {
            insights = insights.stream().limit(3).collect(Collectors.toList());
        } else if (plan == Plan.GOLD) {
            insights = insights.stream().limit(6).collect(Collectors.toList());
        }

        long durationMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
        UniversalSummaryDto summary = new UniversalSummaryDto(
            null,
            filename,
            createdAt == null ? Instant.now() : createdAt,
            rowCount,
            stats.size(),
            columns,
            correlations
            ,
            insights
        );

        return new AnalysisResult(
            summary,
            corrRows,
            totalRowsRead,
            goodRows,
            badRows,
            observedRows,
            sampled,
            removedEmptyColumns,
            durationMs,
            bytes.length,
            charset == null ? null : charset.name(),
            delimiter
        );
    }

    private List<UniversalInsightDto> buildInsights(
        Plan plan,
        List<String> headers,
        List<UniversalColumnDto> columns,
        List<UniversalCorrelationDto> correlations,
        byte[] bytes,
        Charset charset,
        char delimiter
    ) {
        List<UniversalInsightDto> insights = new ArrayList<>();

        // Calidad: columnas con muchos nulos
        UniversalColumnDto worstNull = columns.stream()
            .filter(c -> c.totalCount() > 0)
            .max(Comparator.comparingDouble(c -> (double) c.nullCount() / (double) c.totalCount()))
            .orElse(null);
        if (worstNull != null) {
            double ratio = (double) worstNull.nullCount() / (double) worstNull.totalCount();
            if (ratio >= 0.2) {
                insights.add(new UniversalInsightDto(
                    "warning",
                    "Calidad de datos",
                    "La columna '" + worstNull.name() + "' tiene muchos nulos (" + Math.round(ratio * 100) + "%). Considera completar, imputar o excluirla de métricas."
                ));
            }
        }

        // Oportunidad: correlaciones fuertes (GOLD+)
        if (plan != null && plan.isAtLeast(Plan.GOLD) && correlations != null && !correlations.isEmpty()) {
            UniversalCorrelationDto top = correlations.stream()
                .max(Comparator.comparingDouble(c -> Math.abs(c.correlation())))
                .orElse(null);
            if (top != null && Math.abs(top.correlation()) >= 0.75) {
                insights.add(new UniversalInsightDto(
                    "opportunity",
                    "Relación fuerte detectada",
                    "'" + top.columnA() + "' y '" + top.columnB() + "' están muy relacionadas (corr=" + String.format(Locale.ROOT, "%.2f", top.correlation()) + "). Útil para explicar drivers o construir modelos de predicción."
                ));
            }
        }

        // PLATINUM: heurística específica para presupuestos mensuales (ENERO..DICIEMBRE)
        if (plan != null && plan.isAtLeast(Plan.PLATINUM)) {
            var budget = tryBudgetLikeInsight(headers, bytes, charset, delimiter);
            if (budget != null) {
                insights.add(budget);
            }
        }

        if (insights.isEmpty()) {
            insights.add(new UniversalInsightDto(
                "info",
                "Siguiente paso",
                "Si me dices el objetivo (costes, ventas, tesorería, presupuesto…), puedo recomendar KPIs y cortes para convertir este dataset en un cuadro de mando accionable."
            ));
        }

        return insights;
    }

    private UniversalInsightDto tryBudgetLikeInsight(List<String> headers, byte[] bytes, Charset charset, char delimiter) {
        if (headers == null || headers.isEmpty()) return null;
        Map<String, String> monthMap = Map.ofEntries(
            Map.entry("ENERO", "Enero"),
            Map.entry("FEBRERO", "Febrero"),
            Map.entry("MARZO", "Marzo"),
            Map.entry("ABRIL", "Abril"),
            Map.entry("MAYO", "Mayo"),
            Map.entry("JUNIO", "Junio"),
            Map.entry("JULIO", "Julio"),
            Map.entry("AGOSTO", "Agosto"),
            Map.entry("SEPTIEMBRE", "Septiembre"),
            Map.entry("OCTUBRE", "Octubre"),
            Map.entry("NOVIEMBRE", "Noviembre"),
            Map.entry("DICIEMBRE", "Diciembre")
        );

        Map<String, String> normalizedToHeader = new HashMap<>();
        for (String h : headers) {
            if (h == null) continue;
            String norm = h.trim().toUpperCase(Locale.ROOT);
            if (monthMap.containsKey(norm)) {
                normalizedToHeader.put(norm, h);
            }
        }
        List<String> monthKeys = normalizedToHeader.keySet().stream().sorted().collect(Collectors.toList());

        if (monthKeys.size() < 6) return null;

        String labelHeader = headers.get(0);
        Map<String, Double> income = new HashMap<>();
        Map<String, Double> expense = new HashMap<>();

        try {
            CSVParser parser = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setAllowMissingColumnNames(true)
                .setIgnoreEmptyLines(true)
                .build()
                .parse(new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), charset)));

            int rows = 0;
            for (CSVRecord record : parser) {
                rows++;
                if (rows > 300) break;
                String label = clean(record.get(labelHeader));
                if (label == null) continue;
                String up = label.toUpperCase(Locale.ROOT);

                if (up.contains("TOTAL INGRESOS")) {
                    for (String mk : monthKeys) {
                        String header = normalizedToHeader.get(mk);
                        Double v = parseNumber(clean(record.get(header)));
                        if (v != null) income.put(mk, v);
                    }
                } else if (up.contains("GASTOS") && up.contains("EXPLOTACION")) {
                    for (String mk : monthKeys) {
                        String header = normalizedToHeader.get(mk);
                        Double v = parseNumber(clean(record.get(header)));
                        if (v != null) expense.put(mk, v);
                    }
                }
            }
        } catch (Exception ignored) {
            return null;
        }

        if (income.isEmpty() || expense.isEmpty()) return null;

        double totalIncome = monthKeys.stream().mapToDouble(m -> income.getOrDefault(m, 0.0)).sum();
        double totalExpense = monthKeys.stream().mapToDouble(m -> expense.getOrDefault(m, 0.0)).sum();
        double margin = totalIncome - totalExpense;

        String bestMonth = null;
        double best = Double.NEGATIVE_INFINITY;
        String worstMonth = null;
        double worst = Double.POSITIVE_INFINITY;
        for (String m : monthKeys) {
            double mm = income.getOrDefault(m, 0.0) - expense.getOrDefault(m, 0.0);
            if (mm > best) {
                best = mm;
                bestMonth = m;
            }
            if (mm < worst) {
                worst = mm;
                worstMonth = m;
            }
        }

        String msg = "Detectado formato de presupuesto mensual. Total ingresos="
            + String.format(Locale.ROOT, "%.0f", totalIncome)
            + ", total gastos="
            + String.format(Locale.ROOT, "%.0f", totalExpense)
            + ", margen="
            + String.format(Locale.ROOT, "%.0f", margin)
            + ". Mejor mes: "
            + monthMap.getOrDefault(bestMonth, bestMonth)
            + " (" + String.format(Locale.ROOT, "%.0f", best) + "), peor mes: "
            + monthMap.getOrDefault(worstMonth, worstMonth)
            + " (" + String.format(Locale.ROOT, "%.0f", worst) + ").";

        return new UniversalInsightDto("advisor", "Lectura de presupuesto", msg);
    }

    private List<UniversalCorrelationDto> computeCorrelations(byte[] bytes, Charset charset, char delimiter, List<String> numericHeaders) throws IOException {
        List<CorrelationAccumulator> pairs = new ArrayList<>();
        for (int i = 0; i < numericHeaders.size(); i++) {
            for (int j = i + 1; j < numericHeaders.size(); j++) {
                pairs.add(new CorrelationAccumulator(numericHeaders.get(i), numericHeaders.get(j)));
            }
        }

        CSVParser parser;
        try {
            parser = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setAllowMissingColumnNames(true)
                .setIgnoreEmptyLines(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .build()
                .parse(new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), charset)));
        } catch (Exception ex) {
            return List.of();
        }

        int rows = 0;
        for (CSVRecord record : parser) {
            rows++;
            if (rows > MAX_CORR_ROWS) break;
            Map<String, Double> rowValues = new HashMap<>();
            for (String header : numericHeaders) {
                String raw;
                try {
                    raw = record.get(header);
                } catch (Exception ex) {
                    continue;
                }
                Double v = parseNumber(clean(raw));
                if (v != null) {
                    rowValues.put(header, v);
                }
            }
            for (CorrelationAccumulator acc : pairs) {
                Double x = rowValues.get(acc.colA);
                Double y = rowValues.get(acc.colB);
                if (x != null && y != null) {
                    acc.add(x, y);
                }
            }
        }

        return pairs.stream()
            .filter(acc -> acc.count >= 5)
            .map(acc -> new UniversalCorrelationDto(acc.colA, acc.colB, acc.correlation()))
            .sorted(Comparator.comparingDouble((UniversalCorrelationDto c) -> Math.abs(c.correlation())).reversed())
            .collect(Collectors.toList());
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
        int c = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == ch) c++;
        }
        return c;
    }

    private static String clean(String raw) {
        if (raw == null) return null;
        String v = stripBom(raw).trim();
        return v.isEmpty() || "-".equals(v) ? null : v;
    }

    private static Charset detectCharset(byte[] bytes) {
        if (bytes.length >= 3
            && (bytes[0] & 0xFF) == 0xEF
            && (bytes[1] & 0xFF) == 0xBB
            && (bytes[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (bytes.length >= 2) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xFE) return StandardCharsets.UTF_16LE;
            if (b0 == 0xFE && b1 == 0xFF) return StandardCharsets.UTF_16BE;
        }

        // Heurística: si hay muchos NUL puede ser UTF-16 sin BOM (exportación "rara").
        int sample = Math.min(bytes.length, 200);
        int zeros = 0;
        int evenZeros = 0;
        int oddZeros = 0;
        for (int i = 0; i < sample; i++) {
            if (bytes[i] == 0) {
                zeros++;
                if ((i % 2) == 0) evenZeros++; else oddZeros++;
            }
        }
        if (sample >= 20 && zeros > (sample / 5)) { // > 20% NUL
            // En UTF-16LE, ASCII suele tener NUL en posiciones impares.
            return oddZeros >= evenZeros ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE;
        }

        return StandardCharsets.UTF_8;
    }

    private static String stripBom(String value) {
        if (value == null) return null;
        if (!value.isEmpty() && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }

    private static Double parseNumber(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replace(" ", "");
        int comma = cleaned.lastIndexOf(',');
        int dot = cleaned.lastIndexOf('.');
        if (comma > dot) {
            cleaned = cleaned.replace(".", "").replace(",", ".");
        } else {
            cleaned = cleaned.replace(",", "");
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null) return null;
        List<DateTimeFormatter> fmts = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM"),
            DateTimeFormatter.ofPattern("dd-MM-yy")
        );
        for (DateTimeFormatter fmt : fmts) {
            try {
                return LocalDate.parse(raw, fmt);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private static class ColumnStats {
        private final String name;
        private long total = 0;
        private long nulls = 0;
        private final Map<String, Long> freq = new HashMap<>();
        private final List<Double> numbers = new ArrayList<>();
        private final List<LocalDate> dates = new ArrayList<>();
        private String detectedType = "text";

        ColumnStats(String name) {
            this.name = name == null || name.isBlank() ? "column" : name;
        }

        void observe(String raw) {
            total++;
            if (raw == null) {
                nulls++;
                return;
            }
            if (freq.size() < MAX_UNIQUE_VALUES) {
                freq.merge(raw, 1L, Long::sum);
            }
            Double n = parseNumber(raw);
            if (n != null && numbers.size() < MAX_VALUES_FOR_STATS) {
                numbers.add(n);
            }
            LocalDate d = parseDate(raw);
            if (d != null && dates.size() < MAX_VALUES_FOR_STATS) {
                dates.add(d);
            }
        }

        void finalizeType() {
            long nonNull = total - nulls;
            if (nonNull == 0) {
                detectedType = "empty";
                return;
            }
            double numRatio = nonNull == 0 ? 0 : (double) numbers.size() / nonNull;
            double dateRatio = nonNull == 0 ? 0 : (double) dates.size() / nonNull;
            if (numRatio >= 0.7) {
                detectedType = "number";
            } else if (dateRatio >= 0.7) {
                detectedType = "date";
            } else {
                detectedType = "text";
            }
        }

        UniversalColumnDto toDto() {
            long unique = freq.size();
            List<UniversalTopValueDto> topValues = freq.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(TOP_VALUES)
                .map(e -> new UniversalTopValueDto(e.getKey(), e.getValue()))
                .collect(Collectors.toList());

            Double min = null;
            Double max = null;
            Double mean = null;
            Double median = null;
            Double p90 = null;
            List<UniversalBucketDto> histogram = List.of();

            if ("number".equals(detectedType) && !numbers.isEmpty()) {
                List<Double> sorted = new ArrayList<>(numbers);
                Collections.sort(sorted);
                min = sorted.get(0);
                max = sorted.get(sorted.size() - 1);
                double sum = 0;
                for (double v : sorted) sum += v;
                mean = sum / sorted.size();
                median = sorted.get(sorted.size() / 2);
                p90 = sorted.get((int) Math.floor(sorted.size() * 0.9) - 1);
                histogram = buildHistogram(sorted, min, max);
            }

            String dateMin = null;
            String dateMax = null;
            List<UniversalBucketDto> dateSeries = List.of();
            if ("date".equals(detectedType) && !dates.isEmpty()) {
                List<LocalDate> sorted = new ArrayList<>(dates);
                Collections.sort(sorted);
                dateMin = sorted.get(0).toString();
                dateMax = sorted.get(sorted.size() - 1).toString();
                dateSeries = buildDateSeries(sorted);
            }

            return new UniversalColumnDto(
                name,
                detectedType,
                total,
                nulls,
                unique,
                min,
                max,
                mean,
                median,
                p90,
                dateMin,
                dateMax,
                topValues,
                histogram,
                dateSeries
            );
        }
    }

    private static class CorrelationAccumulator {
        private final String colA;
        private final String colB;
        private int count = 0;
        private double sumX = 0;
        private double sumY = 0;
        private double sumXY = 0;
        private double sumX2 = 0;
        private double sumY2 = 0;

        CorrelationAccumulator(String colA, String colB) {
            this.colA = colA;
            this.colB = colB;
        }

        void add(double x, double y) {
            count++;
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
            sumY2 += y * y;
        }

        double correlation() {
            double numerator = count * sumXY - sumX * sumY;
            double denomA = count * sumX2 - sumX * sumX;
            double denomB = count * sumY2 - sumY * sumY;
            if (denomA <= 0 || denomB <= 0) return 0.0;
            return numerator / Math.sqrt(denomA * denomB);
        }
    }

    private record AnalysisResult(UniversalSummaryDto summary,
                                  int corrRows,
                                  int totalRowsRead,
                                  int goodRows,
                                  int badRows,
                                  int observedRows,
                                  boolean sampled,
                                  int removedEmptyColumns,
                                  long durationMs,
                                  long bytes,
                                  String charsetName,
                                  char delimiter) {}

    private static List<UniversalBucketDto> buildHistogram(List<Double> sorted, double min, double max) {
        if (sorted.isEmpty() || min == max) {
            return List.of(new UniversalBucketDto(formatNumber(min), sorted.size()));
        }
        double range = max - min;
        double step = range / HISTOGRAM_BINS;
        long[] bins = new long[HISTOGRAM_BINS];
        for (double v : sorted) {
            int idx = (int) Math.floor((v - min) / step);
            if (idx >= HISTOGRAM_BINS) idx = HISTOGRAM_BINS - 1;
            if (idx < 0) idx = 0;
            bins[idx]++;
        }
        List<UniversalBucketDto> out = new ArrayList<>();
        for (int i = 0; i < HISTOGRAM_BINS; i++) {
            double start = min + (i * step);
            double end = (i == HISTOGRAM_BINS - 1) ? max : (start + step);
            String label = formatNumber(start) + " - " + formatNumber(end);
            out.add(new UniversalBucketDto(label, bins[i]));
        }
        return out;
    }

    private static List<UniversalBucketDto> buildDateSeries(List<LocalDate> sorted) {
        Map<String, Long> counts = new HashMap<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM");
        for (LocalDate d : sorted) {
            String key = d.format(fmt);
            counts.merge(key, 1L, Long::sum);
        }
        return counts.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .limit(MAX_DATE_SERIES)
            .map(e -> new UniversalBucketDto(e.getKey(), e.getValue()))
            .collect(Collectors.toList());
    }

    private static String formatNumber(double value) {
        if (Math.abs(value) >= 1000) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        if (Math.abs(value) >= 100) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}

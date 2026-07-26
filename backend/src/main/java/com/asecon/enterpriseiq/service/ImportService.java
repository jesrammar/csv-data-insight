package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.ImportJob;
import com.asecon.enterpriseiq.model.ImportStatus;
import com.asecon.enterpriseiq.model.AutomationJobType;
import com.asecon.enterpriseiq.model.StagingTransaction;
import com.asecon.enterpriseiq.model.Transaction;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.asecon.enterpriseiq.repo.ImportJobRepository;
import com.asecon.enterpriseiq.repo.StagingTransactionRepository;
import com.asecon.enterpriseiq.repo.TransactionRepository;
import com.asecon.enterpriseiq.metrics.ErrorTagger;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.io.InputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HashMap;
import java.util.stream.Collectors;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ImportService {
    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final ImportJobRepository importJobRepository;
    private final CompanyRepository companyRepository;
    private final StagingTransactionRepository stagingRepository;
    private final TransactionRepository transactionRepository;
    private final KpiService kpiService;
    private final AlertService alertService;
    private final ObjectMapper objectMapper;
    private final Path importsRoot;
    private final TabularFileService tabularFileService;
    private final MeterRegistry meterRegistry;
    private final ErrorTagger errorTagger;
    private final CompanySettingsService companySettingsService;
    private final AutomationJobService automationJobService;
    private final PeriodWorkflowService periodWorkflowService;
    private final int minValidRows;
    private final double maxWarningRate;
    private final double maxDuplicateRate;
    private final boolean blockOnOutsidePeriodRows;

    public ImportService(ImportJobRepository importJobRepository,
                         CompanyRepository companyRepository,
                         StagingTransactionRepository stagingRepository,
                         TransactionRepository transactionRepository,
                         KpiService kpiService,
                         AlertService alertService,
                         ObjectMapper objectMapper,
                         @Value("${app.storage.imports}") String importsRoot,
                         TabularFileService tabularFileService,
                         MeterRegistry meterRegistry,
                         ErrorTagger errorTagger,
                         CompanySettingsService companySettingsService,
                         AutomationJobService automationJobService,
                         PeriodWorkflowService periodWorkflowService,
                         @Value("${app.import-guard.min-valid-rows:1}") int minValidRows,
                         @Value("${app.import-guard.max-warning-rate:0.15}") double maxWarningRate,
                         @Value("${app.import-guard.max-duplicate-rate:0.5}") double maxDuplicateRate,
                         @Value("${app.import-guard.block-on-outside-period-rows:true}") boolean blockOnOutsidePeriodRows) {
        this.importJobRepository = importJobRepository;
        this.companyRepository = companyRepository;
        this.stagingRepository = stagingRepository;
        this.transactionRepository = transactionRepository;
        this.kpiService = kpiService;
        this.alertService = alertService;
        this.objectMapper = objectMapper;
        this.importsRoot = Path.of(importsRoot).toAbsolutePath().normalize();
        this.tabularFileService = tabularFileService;
        this.meterRegistry = meterRegistry;
        this.errorTagger = errorTagger;
        this.companySettingsService = companySettingsService;
        this.automationJobService = automationJobService;
        this.periodWorkflowService = periodWorkflowService;
        this.minValidRows = Math.max(1, minValidRows);
        this.maxWarningRate = Math.max(0d, maxWarningRate);
        this.maxDuplicateRate = Math.max(0d, maxDuplicateRate);
        this.blockOnOutsidePeriodRows = blockOnOutsidePeriodRows;
        try {
            Files.createDirectories(this.importsRoot);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot create imports storage dir: " + this.importsRoot, ex);
        }
    }

    public ImportJob createImport(Long companyId, String period, MultipartFile file) throws IOException {
        Timer.Sample uploadSample = Timer.start(meterRegistry);
        Company company = companyRepository.findById(companyId).orElseThrow();
        ImportJob job = initializeJob(company, period, safeFilename(file.getOriginalFilename()), file.getContentType(), null);
        job = importJobRepository.save(job);
        periodWorkflowService.syncFromImport(job);

        Files.createDirectories(importsRoot);
        String ext = detectExtension(job.getOriginalFilename(), file.getContentType());
        String storageRef = "import-" + job.getId() + "-" + UUID.randomUUID() + ext;
        Path target = importsRoot.resolve(storageRef).toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        String contentHash;
        try (InputStream is = file.getInputStream()) {
            contentHash = copyToFileAndSha256(is, target);
        }
        job.setContentHash(contentHash);

        try {
            requireTxnHeaders(target);
        } catch (ResponseStatusException ex) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) {}
            markRejectedValidation(job, "INVALID_IMPORT_FILE", ex.getReason() != null ? ex.getReason() : ex.getMessage());
            importJobRepository.save(job);
            periodWorkflowService.syncFromImport(job);
            throw ex;
        }

        try {
            long fileBytes = Files.size(target);
            log.info("METRIC ingestion.import.upload companyId={} importId={} period={} fileBytes={} originalFilename={} contentType={}",
                companyId, job.getId(), job.getPeriod(), fileBytes, job.getOriginalFilename(), job.getContentType());

            DistributionSummary.builder("ingestion.import.upload.bytes")
                .baseUnit("bytes")
                .tag("kind", "raw")
                .tag("contentType", safeContentType(job.getContentType()))
                .register(meterRegistry)
                .record(fileBytes);
            Counter.builder("ingestion.import.upload.count")
                .tag("kind", "raw")
                .register(meterRegistry)
                .increment();
        } catch (Exception ignored) {}

        job.setStorageRef(storageRef);
        job.setUpdatedAt(Instant.now());
        maybeBlockExactDuplicate(job);
        importJobRepository.save(job);
        periodWorkflowService.syncFromImport(job);

        uploadSample.stop(Timer.builder("ingestion.import.upload.duration")
            .tag("kind", "raw")
            .register(meterRegistry));
        return job;
    }

    public ImportJob createImportMapped(Long companyId,
                                        String period,
                                        MultipartFile file,
                                        String txnDateCol,
                                        String amountCol,
                                        String descriptionCol,
                                        String counterpartyCol,
                                        String balanceEndCol,
                                        Integer sheetIndex,
                                        Integer headerRow1Based) throws IOException {
        Timer.Sample uploadSample = Timer.start(meterRegistry);
        if (txnDateCol == null || txnDateCol.isBlank() || amountCol == null || amountCol.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Faltan columnas requeridas (fecha e importe).");
        }

        Company company = companyRepository.findById(companyId).orElseThrow();
        ImportJob job = initializeJob(company, period, safeFilename(file.getOriginalFilename()), "text/csv", null);
        job = importJobRepository.save(job);
        periodWorkflowService.syncFromImport(job);

        TabularFileService.XlsxOptions xlsxOptions = null;
        if (TabularFileService.isXlsx(file) && (sheetIndex != null || headerRow1Based != null)) {
            xlsxOptions = new TabularFileService.XlsxOptions(sheetIndex, headerRow1Based);
        }

        TabularFileService.TabularCsv csv = tabularFileService.toCsv(file, xlsxOptions);
        byte[] bytes = csv.bytes();
        char delimiter = detectDelimiter(bytes);

        String storageRef = "import-" + job.getId() + "-" + UUID.randomUUID() + ".csv";
        Path target = importsRoot.resolve(storageRef).toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        String contentHash = sha256Hex(bytes);

        int warnings = 0;
        int errors = 0;
        BigDecimal lastBalanceEnd = null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
             StringWriter sw = new StringWriter();
             CSVPrinter printer = CSVFormat.DEFAULT.builder().setHeader("txn_date", "amount", "description", "counterparty", "balance_end").build().print(sw)) {

            CSVParser parser = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(reader);

            Map<String, Integer> headerMap = parser.getHeaderMap();
            if (!headerMap.containsKey(txnDateCol) || !headerMap.containsKey(amountCol)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Las columnas seleccionadas no existen en el fichero.");
            }

            long mappedRows = 0;
            for (CSVRecord record : parser) {
                String rawDate = safeGet(record, txnDateCol);
                String rawAmount = safeGet(record, amountCol);
                String rawDesc = descriptionCol == null ? "" : safeGet(record, descriptionCol);
                String rawCp = counterpartyCol == null ? "" : safeGet(record, counterpartyCol);
                String rawBal = balanceEndCol == null ? "" : safeGet(record, balanceEndCol);

                if ((rawDate == null || rawDate.isBlank()) && (rawAmount == null || rawAmount.isBlank())) continue;

                LocalDate d;
                try {
                    d = parseFlexibleDate(rawDate);
                } catch (Exception ex) {
                    warnings++;
                    continue;
                }

                BigDecimal amt;
                try {
                    amt = parseAmount(rawAmount);
                } catch (Exception ex) {
                    warnings++;
                    continue;
                }

                if (rawBal != null && !rawBal.isBlank()) {
                    try {
                        lastBalanceEnd = parseAmount(rawBal);
                    } catch (Exception ignored) {
                        warnings++;
                    }
                }

                printer.printRecord(
                    d.toString(),
                    amt == null ? "" : amt.toPlainString(),
                    rawDesc == null ? "" : rawDesc,
                    rawCp == null ? "" : rawCp,
                    rawBal == null ? "" : rawBal
                );
                mappedRows++;
            }

            DistributionSummary.builder("ingestion.import.mapped.rows")
                .baseUnit("rows")
                .register(meterRegistry)
                .record(mappedRows);

            printer.flush();
            Files.writeString(target, sw.toString(), StandardCharsets.UTF_8);
        } catch (ResponseStatusException ex) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) {}
            markRejectedValidation(job, "INVALID_IMPORT_MAPPING", ex.getReason() != null ? ex.getReason() : ex.getMessage());
            importJobRepository.save(job);
            periodWorkflowService.syncFromImport(job);
            throw ex;
        } catch (Exception ex) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) {}
            errors++;
            markRejectedValidation(job, "IMPORT_MAPPING_FAILED", ex.getClass().getSimpleName() + ": " + ex.getMessage());
            job.setErrorCount(errors);
            job.setWarningCount(warnings);
            importJobRepository.save(job);
            periodWorkflowService.syncFromImport(job);
            throw ex;
        }

        try {
            long mappedBytes = Files.size(target);
            log.info("METRIC ingestion.import.upload_mapped companyId={} importId={} period={} fileBytes={} originalFilename={} mappedBytes={} warnings={} errors={}",
                companyId, job.getId(), job.getPeriod(), file.getSize(), job.getOriginalFilename(), mappedBytes, warnings, errors);

            DistributionSummary.builder("ingestion.import.upload.bytes")
                .baseUnit("bytes")
                .tag("kind", "mapped")
                .tag("contentType", "text/csv")
                .register(meterRegistry)
                .record(mappedBytes);
            Counter.builder("ingestion.import.upload.count")
                .tag("kind", "mapped")
                .register(meterRegistry)
                .increment();
        } catch (Exception ignored) {}

        job.setStorageRef(storageRef);
        job.setWarningCount(warnings);
        job.setErrorCount(errors);
        job.setContentHash(contentHash);
        if (warnings > 0) {
            job.setErrorSummary("Warnings: " + warnings + " invalid rows skipped.");
        }
        job.setUpdatedAt(Instant.now());
        maybeBlockExactDuplicate(job);
        importJobRepository.save(job);
        periodWorkflowService.syncFromImport(job);

        uploadSample.stop(Timer.builder("ingestion.import.upload.duration")
            .tag("kind", "mapped")
            .register(meterRegistry));
        return job;
    }

    public ImportJob createImportFromPath(Long companyId, String period, Path csvFile) throws IOException {
        Company company = companyRepository.findById(companyId).orElseThrow();
        ImportJob job = initializeJob(company, period, safeFilename(csvFile.getFileName().toString()), "text/csv", null);
        job = importJobRepository.save(job);
        periodWorkflowService.syncFromImport(job);

        Files.createDirectories(importsRoot);
        String ext = extensionOf(job.getOriginalFilename());
        String storageRef = "import-" + job.getId() + "-" + UUID.randomUUID() + ext;
        Path target = importsRoot.resolve(storageRef);
        String contentHash;
        try (InputStream is = Files.newInputStream(csvFile)) {
            contentHash = copyToFileAndSha256(is, target);
        }
        job.setStorageRef(storageRef);
        job.setContentHash(contentHash);
        try {
            long fileBytes = Files.size(target);
            log.info("METRIC ingestion.import.upload_path companyId={} importId={} period={} fileBytes={} originalFilename={}",
                companyId, job.getId(), job.getPeriod(), fileBytes, job.getOriginalFilename());

            DistributionSummary.builder("ingestion.import.upload.bytes")
                .baseUnit("bytes")
                .tag("kind", "path")
                .tag("contentType", "text/csv")
                .register(meterRegistry)
                .record(fileBytes);
            Counter.builder("ingestion.import.upload.count")
                .tag("kind", "path")
                .register(meterRegistry)
                .increment();
        } catch (Exception ignored) {}
        job.setUpdatedAt(Instant.now());
        maybeBlockExactDuplicate(job);
        importJobRepository.save(job);
        periodWorkflowService.syncFromImport(job);
        return job;
    }

    public List<ImportJob> listByCompany(Long companyId) {
        return importJobRepository.findByCompanyIdOrderByCreatedAtDesc(companyId);
    }

    @Transactional
    public void processImport(Long importJobId) {
        long startNs = System.nanoTime();
        ImportJob job = importJobRepository.findById(importJobId).orElse(null);
        if (job == null) return;
        if (job.getStatus() != ImportStatus.RUNNING
            && job.getStatus() != ImportStatus.PENDING
            && job.getStatus() != ImportStatus.RETRY) {
            return;
        }

        if (job.getStatus() != ImportStatus.RUNNING) {
            job.setStatus(ImportStatus.RUNNING);
            job.setUpdatedAt(Instant.now());
            importJobRepository.save(job);
            periodWorkflowService.syncFromImport(job);
        }

        int warnings = 0;
        int errors = 0;
        BigDecimal lastBalanceEnd = null;
        StringBuilder errorSummary = new StringBuilder();

        Path filePath = resolveImportPath(job);
        if (!Files.exists(filePath)) {
            handleFailure(job, new IOException("Import file missing: " + filePath.getFileName()));
            return;
        }

        stagingRepository.deleteByImportJobId(job.getId());

        try {
            List<Transaction> normalized = new ArrayList<>();
            List<StagingTransaction> staging = new ArrayList<>();
            int nonEmptyRows = 0;
            int validRows = 0;
            int outsidePeriodRows = 0;
            int duplicateRows = 0;
            Set<String> seenRows = new HashSet<>();
            List<String> canonicalRows = new ArrayList<>();
            YearMonth importPeriod = parseImportPeriod(job.getPeriod());

            for (var row : readRows(filePath)) {
                String txnDateRaw = row.getOrDefault("txn_date", "");
                String amountRaw = row.getOrDefault("amount", "");
                if ((txnDateRaw == null || txnDateRaw.isBlank()) && (amountRaw == null || amountRaw.isBlank())) {
                    continue;
                }
                nonEmptyRows++;
                LocalDate txnDate;
                BigDecimal amount;

                try {
                    txnDate = parseFlexibleDate(txnDateRaw);
                } catch (Exception ex) {
                    warnings++;
                    continue;
                }

                try {
                    amount = parseAmount(amountRaw);
                } catch (Exception ex) {
                    warnings++;
                    continue;
                }
                if (amount == null) {
                    warnings++;
                    continue;
                }

                String description = row.getOrDefault("description", "");
                String counterparty = row.get("counterparty");

                StagingTransaction st = new StagingTransaction();
                st.setImportJob(job);
                st.setCompany(job.getCompany());
                st.setTxnDate(txnDate);
                st.setDescription(description);
                st.setAmount(amount);
                st.setCounterparty(counterparty);
                st.setRawJson(objectMapper.writeValueAsString(row));
                staging.add(st);

                Transaction tx = new Transaction();
                tx.setCompany(job.getCompany());
                tx.setPeriod(job.getPeriod());
                tx.setTxnDate(txnDate);
                tx.setDescription(description);
                tx.setAmount(amount);
                tx.setCounterparty(counterparty);
                normalized.add(tx);
                validRows++;

                if (importPeriod != null && !importPeriod.equals(YearMonth.from(txnDate))) {
                    outsidePeriodRows++;
                }
                String canonicalRow = canonicalRow(txnDate, amount, description, counterparty);
                canonicalRows.add(canonicalRow);
                if (!seenRows.add(canonicalRow)) {
                    duplicateRows++;
                }

                if (row.containsKey("balance_end")) {
                    try {
                        lastBalanceEnd = parseAmount(row.get("balance_end"));
                    } catch (Exception ignored) {
                        warnings++;
                    }
                }
            }

            if (normalized.isEmpty()) {
                job.setRowsReceived(nonEmptyRows);
                job.setRowsValid(0);
                if (nonEmptyRows == 0) {
                    markBlocked(job, "NO_VALID_ROWS", "Import vacio o sin filas con datos (txn_date/amount). No se han modificado transacciones.", warnings, 1);
                } else {
                    markBlocked(job, "NO_VALID_ROWS", "0 filas validas. Revisa formato de fecha/importe. No se han modificado transacciones.", warnings, 1);
                }
                importJobRepository.save(job);
                periodWorkflowService.syncFromImport(job);
                return;
            }

            job.setRowsReceived(nonEmptyRows);
            job.setRowsValid(validRows);
            String normalizedHash = normalizedHash(canonicalRows);
            job.setNormalizedHash(normalizedHash);

            BlockingDecision blocking = evaluateBlocking(job, nonEmptyRows, validRows, warnings, outsidePeriodRows, duplicateRows);
            if (blocking != null) {
                markBlocked(job, blocking.code(), blocking.reason(), warnings, Math.max(1, warnings));
                importJobRepository.save(job);
                periodWorkflowService.syncFromImport(job);
                return;
            }

            ImportJob appliedDuplicate = latestAppliedDuplicate(job, normalizedHash);
            if (appliedDuplicate != null) {
                markBlocked(
                    job,
                    "DUPLICATE_NORMALIZED_HASH",
                    "Este import no se aplico porque ya existe una version efectiva con el mismo contenido normalizado (import #" + appliedDuplicate.getId() + ").",
                    warnings,
                    0
                );
                job.setDuplicateOfImportId(appliedDuplicate.getId());
                importJobRepository.save(job);
                periodWorkflowService.syncFromImport(job);
                return;
            }

            ImportJob previousApplied = latestAppliedImport(job.getCompany().getId(), job.getPeriod());

            transactionRepository.deleteByCompanyIdAndPeriod(job.getCompany().getId(), job.getPeriod());
            stagingRepository.saveAll(staging);
            transactionRepository.saveAll(normalized);

            ImportStatus status = warnings > 0 ? ImportStatus.WARNING : ImportStatus.OK;
            job.setStatus(status);
            job.setWarningCount(warnings);
            job.setErrorCount(errors);
            job.setSupersedesImportId(previousApplied == null ? null : previousApplied.getId());
            job.setDuplicateOfImportId(null);
            job.setBlockingCode(null);
            job.setBlockingReason(null);
            job.setAppliedAt(Instant.now());
            if (warnings > 0) {
                errorSummary.append("Warnings: ").append(warnings).append(" invalid rows skipped.");
            }
            job.setErrorSummary(errorSummary.toString());
            job.setProcessedAt(Instant.now());
            job.setUpdatedAt(job.getProcessedAt());
            job.setLastError(null);
            importJobRepository.save(job);
            periodWorkflowService.syncFromImport(job);

            var kpi = kpiService.recompute(job.getCompany(), job.getPeriod(), lastBalanceEnd);
            alertService.evaluateMonthly(job.getCompany(), kpi);
            if (status == ImportStatus.OK) {
                maybeEnqueueDefaultCloseFlow(job.getCompany().getId(), job.getPeriod());
            }

            try {
                long durMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
                long fileBytes = Files.size(filePath);
                log.info(
                    "METRIC ingestion.import.processed companyId={} importId={} period={} fileBytes={} durationMs={} nonEmptyRows={} validRows={} warnings={} status={}",
                    job.getCompany().getId(),
                    job.getId(),
                    job.getPeriod(),
                    fileBytes,
                    durMs,
                    nonEmptyRows,
                    validRows,
                    warnings,
                    status
                );

                Timer.builder("ingestion.import.process.duration")
                    .tag("result", status == ImportStatus.WARNING ? "warning" : "ok")
                    .register(meterRegistry)
                    .record(durMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                DistributionSummary.builder("ingestion.import.process.bytes")
                    .baseUnit("bytes")
                    .register(meterRegistry)
                    .record(fileBytes);
                DistributionSummary.builder("ingestion.import.process.rows.non_empty")
                    .baseUnit("rows")
                    .register(meterRegistry)
                    .record(nonEmptyRows);
                DistributionSummary.builder("ingestion.import.process.rows.valid")
                    .baseUnit("rows")
                    .register(meterRegistry)
                    .record(validRows);
                DistributionSummary.builder("ingestion.import.process.warnings")
                    .baseUnit("rows")
                    .register(meterRegistry)
                    .record(warnings);
                Counter.builder("ingestion.import.process.count")
                    .tag("result", status == ImportStatus.WARNING ? "warning" : "ok")
                    .register(meterRegistry)
                    .increment();
            } catch (Exception ignored) {}
        } catch (Exception ex) {
            try {
                long durMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
                Timer.builder("ingestion.import.process.duration")
                    .tag("result", "failed")
                    .tag("error", safeErrorTag(ex))
                    .register(meterRegistry)
                    .record(durMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                Counter.builder("ingestion.import.process.count")
                    .tag("result", "failed")
                    .tag("error", safeErrorTag(ex))
                    .register(meterRegistry)
                    .increment();
            } catch (Exception ignored) {}
            handleFailure(job, ex);
        }
    }

    private void maybeEnqueueDefaultCloseFlow(Long companyId, String period) {
        try {
            if (companyId == null || period == null || period.isBlank()) return;
            if (!companySettingsService.autoMonthlyReportEnabled(companyId)) return;
            if (automationJobService.hasActiveJobForPeriod(companyId, AutomationJobType.ORCHESTRATE_PERIOD_CLOSE, period)) return;

            Map<String, Object> payload = new HashMap<>();
            payload.put("period", period.trim());
            String json = objectMapper.writeValueAsString(payload);
            automationJobService.enqueue(companyId, AutomationJobType.ORCHESTRATE_PERIOD_CLOSE, Instant.now(), json);
        } catch (Exception ignored) {}
    }

    @Transactional
    public ImportJob retry(Long companyId, Long importJobId) {
        ImportJob job = importJobRepository.findById(importJobId).orElseThrow();
        if (!job.getCompany().getId().equals(companyId)) throw new IllegalArgumentException("Import job not in company");
        Path file = resolveImportPath(job).toAbsolutePath().normalize();
        if (job.getStorageRef() == null || job.getStorageRef().isBlank() || !Files.exists(file)) {
            throw new ResponseStatusException(
                HttpStatus.GONE,
                "No se puede reintentar: el fichero del import ya no existe (retención/limpieza). Vuelve a subir el archivo."
            );
        }
        job.setStatus(ImportStatus.RETRY);
        job.setRunAfter(Instant.now());
        job.setUpdatedAt(Instant.now());
        job.setLastError(null);
        job.setBlockingCode(null);
        job.setBlockingReason(null);
        job.setErrorSummary("Manual retry requested.");
        job = importJobRepository.save(job);
        periodWorkflowService.syncFromImport(job);
        return job;
    }

    private ImportJob initializeJob(Company company,
                                    String period,
                                    String originalFilename,
                                    String contentType,
                                    String contentHash) {
        ImportJob job = new ImportJob();
        job.setCompany(company);
        job.setPeriod(period);
        job.setStatus(ImportStatus.PENDING);
        job.setCreatedAt(Instant.now());
        job.setUpdatedAt(job.getCreatedAt());
        job.setRunAfter(job.getCreatedAt());
        job.setAttempts(0);
        job.setMaxAttempts(3);
        job.setOriginalFilename(originalFilename);
        job.setContentType(contentType);
        job.setContentHash(contentHash);
        job.setVersionNo(nextVersionNo(company.getId(), period));
        return job;
    }

    private int nextVersionNo(Long companyId, String period) {
        return importJobRepository.findFirstByCompanyIdAndPeriodOrderByVersionNoDescCreatedAtDesc(companyId, period)
            .map(ImportJob::getVersionNo)
            .map(v -> Math.max(1, v + 1))
            .orElse(1);
    }

    private void maybeBlockExactDuplicate(ImportJob job) {
        if (job == null || job.getContentHash() == null || job.getContentHash().isBlank()) return;
        List<ImportJob> duplicates = importJobRepository.findDuplicatesByContentHash(
            job.getCompany().getId(),
            job.getPeriod(),
            job.getContentHash(),
            job.getId()
        );
        if (duplicates.isEmpty()) return;
        ImportJob previous = duplicates.get(0);
        markBlocked(
            job,
            "DUPLICATE_CONTENT_HASH",
            "El fichero es identico a un import previo (import #" + previous.getId() + ", version " + safeVersion(previous) + ").",
            0,
            0
        );
        job.setDuplicateOfImportId(previous.getId());
    }

    private ImportJob latestAppliedImport(Long companyId, String period) {
        return importJobRepository.findFirstByCompanyIdAndPeriodAndAppliedAtNotNullOrderByAppliedAtDesc(companyId, period).orElse(null);
    }

    private ImportJob latestAppliedDuplicate(ImportJob job, String normalizedHash) {
        if (job == null || normalizedHash == null || normalizedHash.isBlank()) return null;
        return importJobRepository.findAppliedDuplicatesByNormalizedHash(
            job.getCompany().getId(),
            job.getPeriod(),
            normalizedHash,
            job.getId()
        ).stream().findFirst().orElse(null);
    }

    private BlockingDecision evaluateBlocking(ImportJob job,
                                              int rowsReceived,
                                              int rowsValid,
                                              int warnings,
                                              int outsidePeriodRows,
                                              int duplicateRows) {
        if (rowsValid < minValidRows) {
            return new BlockingDecision(
                "MIN_VALID_ROWS",
                "El import se bloqueo porque solo hay " + rowsValid + " fila(s) valida(s); el minimo configurado es " + minValidRows + "."
            );
        }

        double warningRate = rowsReceived <= 0 ? 0d : (double) warnings / (double) rowsReceived;
        if (warningRate > maxWarningRate) {
            return new BlockingDecision(
                "HIGH_WARNING_RATE",
                "El import se bloqueo porque el " + pct(warningRate) + " de las filas tiene errores; el maximo permitido es " + pct(maxWarningRate) + "."
            );
        }

        if (blockOnOutsidePeriodRows && outsidePeriodRows > 0) {
            return new BlockingDecision(
                "OUTSIDE_PERIOD_ROWS",
                "El import se bloqueo porque contiene " + outsidePeriodRows + " fila(s) fuera del periodo " + job.getPeriod() + "."
            );
        }

        double duplicateRate = rowsValid <= 0 ? 0d : (double) duplicateRows / (double) rowsValid;
        if (duplicateRate > maxDuplicateRate) {
            return new BlockingDecision(
                "HIGH_DUPLICATE_RATE",
                "El import se bloqueo porque el " + pct(duplicateRate) + " de las filas validas parece duplicado; el maximo permitido es " + pct(maxDuplicateRate) + "."
            );
        }

        return null;
    }

    private void markRejectedValidation(ImportJob job, String code, String reason) {
        markBlocked(job, code, reason, 0, 1);
        job.setStatus(ImportStatus.DEAD);
    }

    private void markBlocked(ImportJob job, String code, String reason, int warnings, int errors) {
        Instant now = Instant.now();
        job.setStatus(ImportStatus.BLOCKED);
        job.setProcessedAt(now);
        job.setUpdatedAt(now);
        job.setRunAfter(null);
        job.setAppliedAt(null);
        job.setBlockingCode(code);
        job.setBlockingReason(reason);
        job.setLastError(code);
        job.setErrorSummary(reason);
        job.setWarningCount(Math.max(0, warnings));
        job.setErrorCount(Math.max(0, errors));
    }

    private static String safeVersion(ImportJob job) {
        return job == null || job.getVersionNo() == null ? "?" : String.valueOf(job.getVersionNo());
    }

    private static String pct(double value) {
        return String.format(java.util.Locale.ROOT, "%.0f%%", value * 100d);
    }

    private void handleFailure(ImportJob job, Exception ex) {
        try {
            log.warn("METRIC ingestion.import.failed companyId={} importId={} period={} attempts={} err={}",
                job == null || job.getCompany() == null ? null : job.getCompany().getId(),
                job == null ? null : job.getId(),
                job == null ? null : job.getPeriod(),
                job == null ? null : job.getAttempts(),
                ex == null ? null : ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } catch (Exception ignored) {}

        try {
            Counter.builder("ingestion.import.failed.count")
                .tag("error", safeErrorTag(ex))
                .register(meterRegistry)
                .increment();
        } catch (Exception ignored) {}

        int nextAttempts = (job.getAttempts() == null ? 0 : job.getAttempts()) + 1;
        job.setAttempts(nextAttempts);
        job.setUpdatedAt(Instant.now());
        job.setLastError(ex.getClass().getSimpleName() + ": " + ex.getMessage());

        if (job.getMaxAttempts() != null && nextAttempts >= job.getMaxAttempts()) {
            job.setStatus(ImportStatus.DEAD);
            job.setProcessedAt(Instant.now());
            job.setErrorCount(1);
            job.setErrorSummary("Import failed permanently: " + job.getLastError());
            importJobRepository.save(job);
            periodWorkflowService.syncFromImport(job);
            return;
        }

        long backoffSeconds = Math.min(3600, (long) Math.pow(2, Math.min(10, nextAttempts)) * 5L);
        job.setStatus(ImportStatus.RETRY);
        job.setRunAfter(Instant.now().plusSeconds(backoffSeconds));
        job.setErrorSummary("Retry scheduled in " + backoffSeconds + "s. " + job.getLastError());
        importJobRepository.save(job);
        periodWorkflowService.syncFromImport(job);
    }

    private Path resolveImportPath(ImportJob job) {
        String ref = job.getStorageRef();
        if (ref == null || ref.isBlank()) return importsRoot.resolve("import-" + job.getId() + ".csv");
        return importsRoot.resolve(ref);
    }

    private static String copyToFileAndSha256(InputStream input, Path target) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream dis = new DigestInputStream(input, digest)) {
                Files.copy(dis, target);
            }
            return hex(digest.digest());
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot compute SHA-256", ex);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(bytes));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot compute SHA-256", ex);
        }
    }

    private static String normalizedHash(List<String> canonicalRows) {
        if (canonicalRows == null || canonicalRows.isEmpty()) return null;
        List<String> copy = new ArrayList<>(canonicalRows);
        Collections.sort(copy);
        return sha256Hex(String.join("\n", copy).getBytes(StandardCharsets.UTF_8));
    }

    private static String canonicalRow(LocalDate txnDate, BigDecimal amount, String description, String counterparty) {
        return txnDate
            + "|"
            + amount.stripTrailingZeros().toPlainString()
            + "|"
            + normalizeTiny(description)
            + "|"
            + normalizeTiny(counterparty);
    }

    private static String normalizeTiny(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        s = s.replaceAll("\\s+", " ");
        return s;
    }

    private static YearMonth parseImportPeriod(String period) {
        if (period == null || period.isBlank()) return null;
        try {
            return YearMonth.parse(period.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    private static String safeContentType(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        String v = raw.trim().toLowerCase();
        if (v.length() > 80) v = v.substring(0, 80);
        int semi = v.indexOf(';');
        if (semi > 0) v = v.substring(0, semi).trim();
        return v.isEmpty() ? "unknown" : v;
    }

    private String safeErrorTag(Exception ex) {
        return errorTagger.tag(ex);
    }

    private List<Map<String, String>> readRows(Path filePath) throws IOException {
        String name = filePath.getFileName().toString().toLowerCase();
        if (name.endsWith(".xlsx")) return readXlsx(filePath);
        return readCsv(filePath);
    }

    private static char detectDelimiter(byte[] bytes) {
        String head = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.UTF_8);
        int eol = head.indexOf('\n');
        if (eol >= 0) head = head.substring(0, eol);
        int commas = count(head, ',');
        int semis = count(head, ';');
        int tabs = count(head, '\t');
        int pipes = count(head, '|');
        int max = commas;
        char best = ',';
        if (semis > max) { max = semis; best = ';'; }
        if (tabs > max) { max = tabs; best = '\t'; }
        if (pipes > max) { max = pipes; best = '|'; }
        return best;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    private static String safeGet(CSVRecord record, String header) {
        if (header == null || header.isBlank()) return "";
        try {
            if (!record.isMapped(header)) return "";
            String v = record.get(header);
            return v == null ? "" : v.trim();
        } catch (Exception ex) {
            return "";
        }
    }

    private static final List<DateTimeFormatter> FLEX_DATES = List.of(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("d/M/uuuu"),
        DateTimeFormatter.ofPattern("dd/MM/uuuu"),
        DateTimeFormatter.ofPattern("d-M-uuuu"),
        DateTimeFormatter.ofPattern("dd-MM-uuuu")
    );

    private static LocalDate parseFlexibleDate(String raw) {
        if (raw == null) throw new IllegalArgumentException("empty date");
        String s = raw.trim();
        if (s.isBlank()) throw new IllegalArgumentException("empty date");
        for (DateTimeFormatter f : FLEX_DATES) {
            try {
                return LocalDate.parse(s, f);
            } catch (DateTimeParseException ignored) {}
        }
        throw new IllegalArgumentException("invalid date: " + raw);
    }

    private void requireTxnHeaders(Path filePath) throws IOException {
        Set<String> headers = readHeaders(filePath);
        if (!headers.contains("txn_date") || !headers.contains("amount")) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Formato incorrecto. Se esperan columnas: txn_date, amount (opcionales: description, counterparty, balance_end)."
            );
        }
    }

    private Set<String> readHeaders(Path filePath) throws IOException {
        String name = filePath.getFileName().toString().toLowerCase();
        if (name.endsWith(".xlsx")) return readXlsxHeaders(filePath);
        return readCsvHeaders(filePath);
    }

    private Set<String> readCsvHeaders(Path filePath) throws IOException {
        char delimiter = detectDelimiter(filePath);
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            var parser = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(reader);
            return parser.getHeaderMap().keySet().stream()
                .map((h) -> h == null ? "" : h.trim().toLowerCase())
                .filter((h) -> !h.isBlank())
                .collect(Collectors.toSet());
        }
    }

    private Set<String> readXlsxHeaders(Path filePath) throws IOException {
        try (InputStream is = Files.newInputStream(filePath); var wb = new XSSFWorkbook(is)) {
            Sheet sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : null;
            if (sheet == null) return Set.of();
            DataFormatter formatter = new DataFormatter();
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) return Set.of();
            int lastCell = headerRow.getLastCellNum();
            var out = new java.util.HashSet<String>();
            for (int i = 0; i < lastCell; i++) {
                String h = formatter.formatCellValue(headerRow.getCell(i));
                if (h != null) {
                    String norm = h.trim().toLowerCase();
                    if (!norm.isBlank()) out.add(norm);
                }
            }
            return out;
        }
    }

    private List<Map<String, String>> readCsv(Path filePath) throws IOException {
        char delimiter = detectDelimiter(filePath);
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            var parser = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(reader);

            Map<String, Integer> headers = parser.getHeaderMap();
            var normalizedHeaders = headers.keySet().stream().map(ImportService::normalizeHeader).collect(Collectors.toSet());
            if (!normalizedHeaders.contains("txn_date") || !normalizedHeaders.contains("amount")) {
                throw new IOException("Missing required columns: txn_date and/or amount");
            }

            List<Map<String, String>> out = new ArrayList<>();
            for (CSVRecord record : parser) {
                Map<String, String> normalized = new LinkedHashMap<>();
                for (var e : record.toMap().entrySet()) {
                    String key = normalizeHeader(e.getKey());
                    String val = e.getValue();
                    normalized.put(key, val == null ? "" : val.trim());
                }
                out.add(normalized);
            }
            return out;
        }
    }

    private List<Map<String, String>> readXlsx(Path filePath) throws IOException {
        try (InputStream is = Files.newInputStream(filePath); var wb = new XSSFWorkbook(is)) {
            Sheet sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : null;
            if (sheet == null) throw new IOException("XLSX without sheets");

            DataFormatter formatter = new DataFormatter();
            int firstRow = sheet.getFirstRowNum();
            Row headerRow = sheet.getRow(firstRow);
            if (headerRow == null) throw new IOException("XLSX missing header row");

            List<String> headers = new ArrayList<>();
            int lastCell = headerRow.getLastCellNum();
            for (int i = 0; i < lastCell; i++) {
                String h = formatter.formatCellValue(headerRow.getCell(i));
                headers.add(normalizeHeader(h));
            }

            if (!headers.contains("txn_date") || !headers.contains("amount")) {
                throw new IOException("Missing required columns: txn_date and/or amount");
            }

            List<Map<String, String>> out = new ArrayList<>();
            for (int r = headerRow.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                boolean any = false;
                Map<String, String> map = new LinkedHashMap<>();
                for (int c = 0; c < headers.size(); c++) {
                    String key = headers.get(c);
                    var cell = row.getCell(c);
                    String val = cellToString(cell, formatter);
                    if (val != null && !val.isBlank()) any = true;
                    map.put(key, val == null ? "" : val.trim());
                }
                if (any) out.add(map);
            }
            return out;
        }
    }

    private static String cellToString(org.apache.poi.ss.usermodel.Cell cell, DataFormatter formatter) {
        if (cell == null) return "";
        try {
            if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                var dt = cell.getLocalDateTimeCellValue();
                return dt == null ? "" : dt.toLocalDate().toString();
            }
        } catch (Exception ignored) {}
        return formatter.formatCellValue(cell);
    }

    private static BigDecimal parseAmount(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isBlank()) return null;
        s = s.replace("€", "").replace(" ", "");

        boolean hasDot = s.contains(".");
        boolean hasComma = s.contains(",");
        if (hasDot && hasComma) {
            if (s.lastIndexOf(',') > s.lastIndexOf('.')) {
                s = s.replace(".", "").replace(",", ".");
            } else {
                s = s.replace(",", "");
            }
        } else if (hasComma) {
            s = s.replace(".", "").replace(",", ".");
        }
        return new BigDecimal(s);
    }

    private static char detectDelimiter(Path filePath) {
        try {
            String first;
            try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
                first = reader.readLine();
            }
            if (first == null) first = "";
            long commas = first.chars().filter(ch -> ch == ',').count();
            long semis = first.chars().filter(ch -> ch == ';').count();
            return semis > commas ? ';' : ',';
        } catch (Exception ignored) {
            return ',';
        }
    }

    private static String normalizeHeader(String header) {
        if (header == null) return "";
        return header.trim().toLowerCase();
    }

    private static String safeFilename(String name) {
        if (name == null) return null;
        String s = name.trim().replace("\\", "/");
        int idx = s.lastIndexOf('/');
        s = idx >= 0 ? s.substring(idx + 1) : s;
        return s.length() > 255 ? s.substring(0, 255) : s;
    }

    private static String detectExtension(String filename, String contentType) {
        String ext = extensionOf(filename);
        if (ext.equals(".xlsx") || ext.equals(".csv")) return ext;
        if (contentType != null && contentType.toLowerCase().contains("sheet")) return ".xlsx";
        return ".csv";
    }

    private static String extensionOf(String filename) {
        if (filename == null) return ".csv";
        String s = filename.toLowerCase().trim();
        int dot = s.lastIndexOf('.');
        if (dot < 0) return ".csv";
        String ext = s.substring(dot);
        if (ext.equals(".xlsx") || ext.equals(".csv")) return ext;
        return ".csv";
    }

    private record BlockingDecision(String code, String reason) {}
}

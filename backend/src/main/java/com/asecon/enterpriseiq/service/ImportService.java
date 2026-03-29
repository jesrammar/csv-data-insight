package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.ImportJob;
import com.asecon.enterpriseiq.model.ImportStatus;
import com.asecon.enterpriseiq.model.StagingTransaction;
import com.asecon.enterpriseiq.model.Transaction;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.asecon.enterpriseiq.repo.ImportJobRepository;
import com.asecon.enterpriseiq.repo.StagingTransactionRepository;
import com.asecon.enterpriseiq.repo.TransactionRepository;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ImportService {
    private final ImportJobRepository importJobRepository;
    private final CompanyRepository companyRepository;
    private final StagingTransactionRepository stagingRepository;
    private final TransactionRepository transactionRepository;
    private final KpiService kpiService;
    private final AlertService alertService;
    private final ObjectMapper objectMapper;
    private final Path importsRoot;
    private final TabularFileService tabularFileService;

    public ImportService(ImportJobRepository importJobRepository,
                         CompanyRepository companyRepository,
                         StagingTransactionRepository stagingRepository,
                         TransactionRepository transactionRepository,
                         KpiService kpiService,
                         AlertService alertService,
                         ObjectMapper objectMapper,
                         @Value("${app.storage.imports}") String importsRoot,
                         TabularFileService tabularFileService) {
        this.importJobRepository = importJobRepository;
        this.companyRepository = companyRepository;
        this.stagingRepository = stagingRepository;
        this.transactionRepository = transactionRepository;
        this.kpiService = kpiService;
        this.alertService = alertService;
        this.objectMapper = objectMapper;
        this.importsRoot = Path.of(importsRoot).toAbsolutePath().normalize();
        this.tabularFileService = tabularFileService;
        try {
            Files.createDirectories(this.importsRoot);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot create imports storage dir: " + this.importsRoot, ex);
        }
    }

    public ImportJob createImport(Long companyId, String period, MultipartFile file) throws IOException {
        Company company = companyRepository.findById(companyId).orElseThrow();
        ImportJob job = new ImportJob();
        job.setCompany(company);
        job.setPeriod(period);
        job.setStatus(ImportStatus.PENDING);
        job.setCreatedAt(Instant.now());
        job.setUpdatedAt(job.getCreatedAt());
        job.setRunAfter(job.getCreatedAt());
        job.setAttempts(0);
        job.setMaxAttempts(3);
        job.setOriginalFilename(safeFilename(file.getOriginalFilename()));
        job.setContentType(file.getContentType());
        job = importJobRepository.save(job);

        Files.createDirectories(importsRoot);
        String ext = detectExtension(job.getOriginalFilename(), file.getContentType());
        String storageRef = "import-" + job.getId() + "-" + UUID.randomUUID() + ext;
        Path target = importsRoot.resolve(storageRef).toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        file.transferTo(target);

        try {
            requireTxnHeaders(target);
        } catch (ResponseStatusException ex) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) {}
            job.setStatus(ImportStatus.DEAD);
            job.setProcessedAt(Instant.now());
            job.setUpdatedAt(job.getProcessedAt());
            job.setErrorCount(1);
            job.setWarningCount(0);
            job.setLastError(ex.getReason() != null ? ex.getReason() : ex.getMessage());
            job.setErrorSummary("Invalid import file: " + job.getLastError());
            importJobRepository.save(job);
            throw ex;
        }

        job.setStorageRef(storageRef);
        job.setUpdatedAt(Instant.now());
        importJobRepository.save(job);
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
        if (txnDateCol == null || txnDateCol.isBlank() || amountCol == null || amountCol.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Faltan columnas requeridas (fecha e importe).");
        }

        Company company = companyRepository.findById(companyId).orElseThrow();
        ImportJob job = new ImportJob();
        job.setCompany(company);
        job.setPeriod(period);
        job.setStatus(ImportStatus.PENDING);
        job.setCreatedAt(Instant.now());
        job.setUpdatedAt(job.getCreatedAt());
        job.setRunAfter(job.getCreatedAt());
        job.setAttempts(0);
        job.setMaxAttempts(3);
        job.setOriginalFilename(safeFilename(file.getOriginalFilename()));
        job.setContentType("text/csv");
        job = importJobRepository.save(job);

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
            }

            printer.flush();
            Files.writeString(target, sw.toString(), StandardCharsets.UTF_8);
        } catch (ResponseStatusException ex) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) {}
            job.setStatus(ImportStatus.DEAD);
            job.setProcessedAt(Instant.now());
            job.setUpdatedAt(job.getProcessedAt());
            job.setErrorCount(1);
            job.setWarningCount(0);
            job.setLastError(ex.getReason() != null ? ex.getReason() : ex.getMessage());
            job.setErrorSummary("Invalid import mapping: " + job.getLastError());
            importJobRepository.save(job);
            throw ex;
        } catch (Exception ex) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) {}
            errors++;
            job.setStatus(ImportStatus.DEAD);
            job.setProcessedAt(Instant.now());
            job.setUpdatedAt(job.getProcessedAt());
            job.setErrorCount(errors);
            job.setWarningCount(warnings);
            job.setLastError(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            job.setErrorSummary("Import mapping failed: " + job.getLastError());
            importJobRepository.save(job);
            throw ex;
        }

        job.setStorageRef(storageRef);
        job.setWarningCount(warnings);
        job.setErrorCount(errors);
        if (warnings > 0) {
            job.setErrorSummary("Warnings: " + warnings + " invalid rows skipped.");
        }
        job.setUpdatedAt(Instant.now());
        importJobRepository.save(job);
        return job;
    }

    public ImportJob createImportFromPath(Long companyId, String period, Path csvFile) throws IOException {
        Company company = companyRepository.findById(companyId).orElseThrow();
        ImportJob job = new ImportJob();
        job.setCompany(company);
        job.setPeriod(period);
        job.setStatus(ImportStatus.PENDING);
        job.setCreatedAt(Instant.now());
        job.setUpdatedAt(job.getCreatedAt());
        job.setRunAfter(job.getCreatedAt());
        job.setAttempts(0);
        job.setMaxAttempts(3);
        job.setOriginalFilename(safeFilename(csvFile.getFileName().toString()));
        job = importJobRepository.save(job);

        Files.createDirectories(importsRoot);
        String ext = extensionOf(job.getOriginalFilename());
        String storageRef = "import-" + job.getId() + "-" + UUID.randomUUID() + ext;
        Path target = importsRoot.resolve(storageRef);
        Files.copy(csvFile, target);
        job.setStorageRef(storageRef);
        job.setUpdatedAt(Instant.now());
        importJobRepository.save(job);
        return job;
    }

    public List<ImportJob> listByCompany(Long companyId) {
        return importJobRepository.findByCompanyIdOrderByCreatedAtDesc(companyId);
    }

    @Transactional
    public void processImport(Long importJobId) {
        ImportJob job = importJobRepository.findById(importJobId).orElse(null);
        if (job == null) return;

        if (job.getStatus() != ImportStatus.RUNNING) {
            job.setStatus(ImportStatus.RUNNING);
            job.setUpdatedAt(Instant.now());
            importJobRepository.save(job);
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
        transactionRepository.deleteByCompanyIdAndPeriod(job.getCompany().getId(), job.getPeriod());

        try {
            List<Transaction> normalized = new ArrayList<>();
            List<StagingTransaction> staging = new ArrayList<>();

            for (var row : readRows(filePath)) {
                String txnDateRaw = row.getOrDefault("txn_date", "");
                String amountRaw = row.getOrDefault("amount", "");
                LocalDate txnDate;
                BigDecimal amount;

                try {
                    txnDate = LocalDate.parse(txnDateRaw);
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

                if (row.containsKey("balance_end")) {
                    try {
                        lastBalanceEnd = parseAmount(row.get("balance_end"));
                    } catch (Exception ignored) {
                        warnings++;
                    }
                }
            }

            stagingRepository.saveAll(staging);
            transactionRepository.saveAll(normalized);

            ImportStatus status = warnings > 0 ? ImportStatus.WARNING : ImportStatus.OK;
            job.setStatus(status);
            job.setWarningCount(warnings);
            job.setErrorCount(errors);
            if (warnings > 0) {
                errorSummary.append("Warnings: ").append(warnings).append(" invalid rows skipped.");
            }
            job.setErrorSummary(errorSummary.toString());
            job.setProcessedAt(Instant.now());
            job.setUpdatedAt(job.getProcessedAt());
            job.setLastError(null);
            importJobRepository.save(job);

            var kpi = kpiService.recompute(job.getCompany(), job.getPeriod(), lastBalanceEnd);
            alertService.evaluateThreshold(job.getCompany(), kpi);
        } catch (Exception ex) {
            handleFailure(job, ex);
        }
    }

    @Transactional
    public ImportJob retry(Long companyId, Long importJobId) {
        ImportJob job = importJobRepository.findById(importJobId).orElseThrow();
        if (!job.getCompany().getId().equals(companyId)) throw new IllegalArgumentException("Import job not in company");
        job.setStatus(ImportStatus.RETRY);
        job.setRunAfter(Instant.now());
        job.setUpdatedAt(Instant.now());
        job.setLastError(null);
        job.setErrorSummary("Manual retry requested.");
        return importJobRepository.save(job);
    }

    private void handleFailure(ImportJob job, Exception ex) {
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
            return;
        }

        long backoffSeconds = Math.min(3600, (long) Math.pow(2, Math.min(10, nextAttempts)) * 5L);
        job.setStatus(ImportStatus.RETRY);
        job.setRunAfter(Instant.now().plusSeconds(backoffSeconds));
        job.setErrorSummary("Retry scheduled in " + backoffSeconds + "s. " + job.getLastError());
        importJobRepository.save(job);
    }

    private Path resolveImportPath(ImportJob job) {
        String ref = job.getStorageRef();
        if (ref == null || ref.isBlank()) return importsRoot.resolve("import-" + job.getId() + ".csv");
        return importsRoot.resolve(ref);
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
            if (!headers.containsKey("txn_date") || !headers.containsKey("amount")) {
                throw new IOException("Missing required columns: txn_date and/or amount");
            }

            List<Map<String, String>> out = new ArrayList<>();
            for (CSVRecord record : parser) out.add(record.toMap());
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
                String h = formatter.formatCellValue(headerRow.getCell(i)).trim();
                headers.add(h);
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
            String first = Files.readAllLines(filePath, StandardCharsets.UTF_8).stream().findFirst().orElse("");
            long commas = first.chars().filter(ch -> ch == ',').count();
            long semis = first.chars().filter(ch -> ch == ';').count();
            return semis > commas ? ';' : ',';
        } catch (Exception ignored) {
            return ',';
        }
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
}

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
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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

    public ImportService(ImportJobRepository importJobRepository,
                         CompanyRepository companyRepository,
                         StagingTransactionRepository stagingRepository,
                         TransactionRepository transactionRepository,
                         KpiService kpiService,
                         AlertService alertService,
                         ObjectMapper objectMapper,
                         @Value("${app.storage.imports}") String importsRoot) {
        this.importJobRepository = importJobRepository;
        this.companyRepository = companyRepository;
        this.stagingRepository = stagingRepository;
        this.transactionRepository = transactionRepository;
        this.kpiService = kpiService;
        this.alertService = alertService;
        this.objectMapper = objectMapper;
        this.importsRoot = Path.of(importsRoot);
    }

    public ImportJob createImport(Long companyId, String period, MultipartFile file) throws IOException {
        Company company = companyRepository.findById(companyId).orElseThrow();
        ImportJob job = new ImportJob();
        job.setCompany(company);
        job.setPeriod(period);
        job.setStatus(ImportStatus.PENDING);
        job.setCreatedAt(Instant.now());
        job = importJobRepository.save(job);

        Files.createDirectories(importsRoot);
        Path target = importsRoot.resolve("import-" + job.getId() + ".csv");
        file.transferTo(target);
        return job;
    }

    public List<ImportJob> listByCompany(Long companyId) {
        return importJobRepository.findByCompanyIdOrderByCreatedAtDesc(companyId);
    }

    @Transactional
    public void processPendingImports() {
        List<ImportJob> pending = importJobRepository.findByStatusOrderByCreatedAtAsc(ImportStatus.PENDING);
        for (ImportJob job : pending) {
            processImport(job);
        }
    }

    @Transactional
    public void processImport(ImportJob job) {
        int warnings = 0;
        int errors = 0;
        BigDecimal lastBalanceEnd = null;
        StringBuilder errorSummary = new StringBuilder();

        Path filePath = importsRoot.resolve("import-" + job.getId() + ".csv");
        if (!Files.exists(filePath)) {
            job.setStatus(ImportStatus.ERROR);
            job.setErrorSummary("CSV file missing for import " + job.getId());
            job.setProcessedAt(Instant.now());
            job.setErrorCount(1);
            importJobRepository.save(job);
            return;
        }

        stagingRepository.deleteByImportJobId(job.getId());
        transactionRepository.deleteByCompanyIdAndPeriod(job.getCompany().getId(), job.getPeriod());

        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            var parser = CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(reader);
            Map<String, Integer> headers = parser.getHeaderMap();
            if (!headers.containsKey("txn_date") || !headers.containsKey("amount")) {
                job.setStatus(ImportStatus.ERROR);
                job.setErrorSummary("Missing required columns: txn_date and/or amount");
                job.setProcessedAt(Instant.now());
                job.setErrorCount(1);
                importJobRepository.save(job);
                return;
            }

            List<Transaction> normalized = new ArrayList<>();
            List<StagingTransaction> staging = new ArrayList<>();

            for (CSVRecord record : parser) {
                String txnDateRaw = record.get("txn_date");
                String amountRaw = record.get("amount");
                LocalDate txnDate;
                BigDecimal amount;

                try {
                    txnDate = LocalDate.parse(txnDateRaw);
                } catch (Exception ex) {
                    warnings++;
                    continue;
                }

                try {
                    amount = new BigDecimal(amountRaw);
                } catch (Exception ex) {
                    warnings++;
                    continue;
                }

                String description = record.isMapped("description") ? record.get("description") : "";
                String counterparty = record.isMapped("counterparty") ? record.get("counterparty") : null;

                StagingTransaction st = new StagingTransaction();
                st.setImportJob(job);
                st.setCompany(job.getCompany());
                st.setTxnDate(txnDate);
                st.setDescription(description);
                st.setAmount(amount);
                st.setCounterparty(counterparty);
                st.setRawJson(objectMapper.writeValueAsString(record.toMap()));
                staging.add(st);

                Transaction tx = new Transaction();
                tx.setCompany(job.getCompany());
                tx.setPeriod(job.getPeriod());
                tx.setTxnDate(txnDate);
                tx.setDescription(description);
                tx.setAmount(amount);
                tx.setCounterparty(counterparty);
                normalized.add(tx);

                if (record.isMapped("balance_end")) {
                    try {
                        lastBalanceEnd = new BigDecimal(record.get("balance_end"));
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
            importJobRepository.save(job);

            var kpi = kpiService.recompute(job.getCompany(), job.getPeriod(), lastBalanceEnd);
            alertService.evaluateThreshold(job.getCompany(), kpi);
        } catch (Exception ex) {
            job.setStatus(ImportStatus.ERROR);
            job.setErrorSummary("Error processing CSV: " + ex.getMessage());
            job.setProcessedAt(Instant.now());
            job.setErrorCount(1);
            importJobRepository.save(job);
        }
    }
}

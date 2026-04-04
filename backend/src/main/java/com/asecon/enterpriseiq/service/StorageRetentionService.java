package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.ImportJob;
import com.asecon.enterpriseiq.model.ImportStatus;
import com.asecon.enterpriseiq.model.Report;
import com.asecon.enterpriseiq.model.UniversalImport;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.asecon.enterpriseiq.repo.ImportJobRepository;
import com.asecon.enterpriseiq.repo.ReportRepository;
import com.asecon.enterpriseiq.repo.UniversalImportRepository;
import com.asecon.enterpriseiq.repo.UniversalViewRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StorageRetentionService {
    private static final Logger log = LoggerFactory.getLogger(StorageRetentionService.class);
    private static final EnumSet<ImportStatus> FINAL_IMPORT_STATUSES = EnumSet.of(ImportStatus.OK, ImportStatus.WARNING, ImportStatus.ERROR, ImportStatus.DEAD);

    private final CompanyRepository companyRepository;
    private final ImportJobRepository importJobRepository;
    private final ReportRepository reportRepository;
    private final UniversalImportRepository universalImportRepository;
    private final UniversalViewRepository universalViewRepository;

    private final boolean enabled;
    private final long importsDays;
    private final long reportsDays;
    private final long universalDays;
    private final int keepImportFilesPerCompany;
    private final int keepReportFilesPerCompany;
    private final int keepUniversalFilesPerCompany;

    private final Path importsRoot;
    private final Path reportsRoot;
    private final Path universalRoot;

    private volatile CleanupResult lastResult;

    public StorageRetentionService(CompanyRepository companyRepository,
                                  ImportJobRepository importJobRepository,
                                  ReportRepository reportRepository,
                                  UniversalImportRepository universalImportRepository,
                                  UniversalViewRepository universalViewRepository,
                                  @Value("${app.storage.retention.enabled:true}") boolean enabled,
                                  @Value("${app.storage.retention.imports-days:30}") long importsDays,
                                  @Value("${app.storage.retention.reports-days:180}") long reportsDays,
                                  @Value("${app.storage.retention.universal-days:30}") long universalDays,
                                  @Value("${app.storage.retention.keep-import-files-per-company:10}") int keepImportFilesPerCompany,
                                  @Value("${app.storage.retention.keep-report-files-per-company:24}") int keepReportFilesPerCompany,
                                  @Value("${app.storage.retention.keep-universal-files-per-company:3}") int keepUniversalFilesPerCompany,
                                  @Value("${app.storage.imports}") String importsRoot,
                                  @Value("${app.storage.reports}") String reportsRoot,
                                  @Value("${app.storage.universal}") String universalRoot) {
        this.companyRepository = companyRepository;
        this.importJobRepository = importJobRepository;
        this.reportRepository = reportRepository;
        this.universalImportRepository = universalImportRepository;
        this.universalViewRepository = universalViewRepository;
        this.enabled = enabled;
        this.importsDays = importsDays;
        this.reportsDays = reportsDays;
        this.universalDays = universalDays;
        this.keepImportFilesPerCompany = Math.max(0, keepImportFilesPerCompany);
        this.keepReportFilesPerCompany = Math.max(0, keepReportFilesPerCompany);
        this.keepUniversalFilesPerCompany = Math.max(1, keepUniversalFilesPerCompany); // always keep latest
        this.importsRoot = Path.of(importsRoot).toAbsolutePath().normalize();
        this.reportsRoot = Path.of(reportsRoot).toAbsolutePath().normalize();
        this.universalRoot = Path.of(universalRoot).toAbsolutePath().normalize();
    }

    @Transactional
    public CleanupResult cleanupNow() {
        Instant startedAt = Instant.now();
        Instant now = startedAt;
        CleanupStats stats = new CleanupStats();

        if (!enabled) {
            CleanupResult result = new CleanupResult(
                startedAt,
                Instant.now(),
                false,
                stats.imports.snapshot(),
                stats.reports.snapshot(),
                stats.universal.snapshot(),
                stats.errors
            );
            lastResult = result;
            return result;
        }

        try {
            cleanupImports(now, stats);
        } catch (Exception ex) {
            stats.errors++;
            log.warn("storage retention: imports cleanup failed: {}", ex.getMessage());
        }
        try {
            cleanupReports(now, stats);
        } catch (Exception ex) {
            stats.errors++;
            log.warn("storage retention: reports cleanup failed: {}", ex.getMessage());
        }
        try {
            cleanupUniversal(now, stats);
        } catch (Exception ex) {
            stats.errors++;
            log.warn("storage retention: universal cleanup failed: {}", ex.getMessage());
        }

        CleanupResult result = new CleanupResult(
            startedAt,
            Instant.now(),
            true,
            stats.imports.snapshot(),
            stats.reports.snapshot(),
            stats.universal.snapshot(),
            stats.errors
        );
        lastResult = result;
        return result;
    }

    public CleanupResult getLastResult() {
        return lastResult;
    }

    private void cleanupImports(Instant now, CleanupStats stats) {
        if (importsDays <= 0) return;
        Instant cutoff = now.minus(importsDays, ChronoUnit.DAYS);

        for (var company : companyRepository.findAll()) {
            Long companyId = company.getId();
            List<ImportJob> jobs = importJobRepository.findByCompanyIdOrderByCreatedAtDesc(companyId);
            Set<String> keep = new HashSet<>();
            for (ImportJob j : jobs) {
                if (j.getStorageRef() == null || j.getStorageRef().isBlank()) continue;
                keep.add(j.getStorageRef());
                if (keep.size() >= keepImportFilesPerCompany) break;
            }

            for (ImportJob j : jobs) {
                String ref = j.getStorageRef();
                if (ref == null || ref.isBlank()) continue;
                if (keep.contains(ref)) continue;
                if (j.getStatus() == null || !FINAL_IMPORT_STATUSES.contains(j.getStatus())) continue;

                Instant t = j.getProcessedAt() != null ? j.getProcessedAt() : j.getCreatedAt();
                if (t != null && t.isAfter(cutoff)) continue;

                Path file = importsRoot.resolve(ref).toAbsolutePath().normalize();
                if (!safeDelete(importsRoot, file)) continue;
                j.setStorageRef(null);
                importJobRepository.save(j);
                stats.imports.refsCleared++;
            }
        }
    }

    private void cleanupReports(Instant now, CleanupStats stats) {
        if (reportsDays <= 0) return;
        Instant cutoff = now.minus(reportsDays, ChronoUnit.DAYS);

        for (var company : companyRepository.findAll()) {
            Long companyId = company.getId();
            List<Report> reports = reportRepository.findByCompanyIdOrderByCreatedAtDesc(companyId);
            int kept = 0;
            for (Report r : reports) {
                String ref = r.getStorageRef();
                if (ref == null || ref.isBlank()) continue;
                if (kept < keepReportFilesPerCompany) {
                    kept++;
                    continue;
                }
                Instant created = r.getCreatedAt();
                if (created != null && created.isAfter(cutoff)) continue;

                Path file = Path.of(ref).toAbsolutePath().normalize();
                if (!safeDelete(reportsRoot, file)) continue;
                r.setStorageRef(null);
                reportRepository.save(r);
                stats.reports.refsCleared++;
            }
        }
    }

    private void cleanupUniversal(Instant now, CleanupStats stats) {
        if (universalDays <= 0) return;
        Instant cutoff = now.minus(universalDays, ChronoUnit.DAYS);

        for (var company : companyRepository.findAll()) {
            Long companyId = company.getId();
            List<UniversalImport> imports = universalImportRepository.findByCompanyIdOrderByCreatedAtDesc(companyId);
            int kept = 0;
            for (UniversalImport imp : imports) {
                String ref = imp.getStorageRef();
                if (ref == null || ref.isBlank()) continue;
                if (kept < keepUniversalFilesPerCompany) {
                    kept++;
                    continue;
                }
                Instant created = imp.getCreatedAt();
                if (created != null && created.isAfter(cutoff)) continue;

                // Do not delete Universal datasets that are referenced by saved dashboards (snapshots).
                if (imp.getId() != null && universalViewRepository.existsByCompanyIdAndSourceUniversalImportId(companyId, imp.getId())) {
                    continue;
                }

                Path file = Path.of(ref).toAbsolutePath().normalize();
                if (!safeDelete(universalRoot, file)) continue;
                imp.setStorageRef(null);
                universalImportRepository.save(imp);
                stats.universal.refsCleared++;
            }
        }
    }

    private static boolean safeDelete(Path root, Path file) {
        try {
            Path absRoot = root.toAbsolutePath().normalize();
            Path absFile = file.toAbsolutePath().normalize();
            if (!absFile.startsWith(absRoot)) return false;
            if (!Files.exists(absFile)) return true;
            Files.delete(absFile);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public record CleanupCounts(long refsCleared) {}

    public record CleanupResult(Instant startedAt,
                                Instant finishedAt,
                                boolean enabled,
                                CleanupCounts imports,
                                CleanupCounts reports,
                                CleanupCounts universal,
                                long errors) {}

    private static class CleanupStats {
        private CleanupCountsMutable imports = new CleanupCountsMutable();
        private CleanupCountsMutable reports = new CleanupCountsMutable();
        private CleanupCountsMutable universal = new CleanupCountsMutable();
        private long errors = 0;
    }

    private static class CleanupCountsMutable {
        private long refsCleared = 0;
        private CleanupCounts snapshot() { return new CleanupCounts(refsCleared); }
    }
}

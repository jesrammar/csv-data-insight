package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.ImportStatus;
import com.asecon.enterpriseiq.repo.ImportJobRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ImportWorker {
    private static final Logger log = LoggerFactory.getLogger(ImportWorker.class);

    private final ImportJobRepository importJobRepository;
    private final ImportService importService;
    private final TransactionTemplate txNew;

    public ImportWorker(ImportJobRepository importJobRepository,
                        ImportService importService,
                        PlatformTransactionManager transactionManager) {
        this.importJobRepository = importJobRepository;
        this.importService = importService;
        this.txNew = new TransactionTemplate(transactionManager);
        this.txNew.setPropagationBehaviorName("PROPAGATION_REQUIRES_NEW");
    }

    @Scheduled(fixedDelayString = "${app.scheduler.import-fixed-delay-ms:120000}")
    public void tick() {
        var due = importJobRepository.findTop25ByStatusInAndRunAfterBeforeOrderByRunAfterAscIdAsc(
            List.of(ImportStatus.PENDING, ImportStatus.RETRY),
            Instant.now()
        );
        for (var job : due) {
            boolean claimed = Boolean.TRUE.equals(txNew.execute(status ->
                importJobRepository.claim(job.getId(), List.of(ImportStatus.PENDING, ImportStatus.RETRY), ImportStatus.RUNNING, Instant.now()) == 1
            ));
            if (!claimed) continue;
            txNew.execute(status -> {
                try {
                    importService.processImport(job.getId());
                } catch (Exception ex) {
                    log.warn("import worker failed jobId={} err={}", job.getId(), ex.getMessage());
                }
                return null;
            });
        }
    }
}


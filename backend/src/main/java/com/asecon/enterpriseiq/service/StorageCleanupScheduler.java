package com.asecon.enterpriseiq.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class StorageCleanupScheduler {
    private static final Logger log = LoggerFactory.getLogger(StorageCleanupScheduler.class);

    private final StorageRetentionService storageRetentionService;

    public StorageCleanupScheduler(StorageRetentionService storageRetentionService) {
        this.storageRetentionService = storageRetentionService;
    }

    @Scheduled(fixedDelayString = "${app.scheduler.storage-cleanup-fixed-delay-ms:86400000}")
    public void tick() {
        var r = storageRetentionService.cleanupNow();
        log.info(
            "storage cleanup finished enabled={} errors={} imports.refsCleared={} reports.refsCleared={} universal.refsCleared={}",
            r.enabled(),
            r.errors(),
            r.imports().refsCleared(),
            r.reports().refsCleared(),
            r.universal().refsCleared()
        );
    }
}

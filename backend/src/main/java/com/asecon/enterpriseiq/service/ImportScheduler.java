package com.asecon.enterpriseiq.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ImportScheduler {
    private final ImportService importService;

    public ImportScheduler(ImportService importService) {
        this.importService = importService;
    }

    @Scheduled(fixedDelayString = "${app.scheduler.import-fixed-delay-ms}")
    public void run() {
        importService.processPendingImports();
    }
}

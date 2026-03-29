package com.asecon.enterpriseiq.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MacroRefreshScheduler {
    private static final Logger log = LoggerFactory.getLogger(MacroRefreshScheduler.class);

    private final MacroDataService macroDataService;

    public MacroRefreshScheduler(MacroDataService macroDataService) {
        this.macroDataService = macroDataService;
    }

    @Scheduled(cron = "${app.scheduler.macro-daily-cron:0 5 2 * * *}")
    public void refreshDaily() {
        try {
            macroDataService.refreshAll();
            log.info("macro refresh ok");
        } catch (Exception ex) {
            log.warn("macro refresh failed: {}", ex.getMessage());
        }
    }
}


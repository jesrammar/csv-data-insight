package com.asecon.enterpriseiq.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "app.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class InboxImportScheduler {
    private static final Logger log = LoggerFactory.getLogger(InboxImportScheduler.class);

    private final PipelineInboxService pipelineInboxService;

    public InboxImportScheduler(PipelineInboxService pipelineInboxService) {
        this.pipelineInboxService = pipelineInboxService;
    }

    @Scheduled(fixedDelayString = "${app.scheduler.inbox-fixed-delay-ms:30000}")
    public void pollInbox() {
        try {
            pipelineInboxService.pollInbox();
        } catch (Exception ex) {
            log.warn("pipeline inbox scheduler failed: {}", ex.getMessage());
        }
    }
}

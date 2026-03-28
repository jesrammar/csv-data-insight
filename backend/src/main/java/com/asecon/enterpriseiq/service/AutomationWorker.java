package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.AutomationJob;
import com.asecon.enterpriseiq.model.AutomationJobStatus;
import com.asecon.enterpriseiq.model.AutomationJobType;
import com.asecon.enterpriseiq.repo.AutomationJobRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class AutomationWorker {
    private static final Logger log = LoggerFactory.getLogger(AutomationWorker.class);

    private final AutomationJobRepository jobRepository;
    private final ObjectMapper objectMapper;
    private final KpiAutomationService kpiAutomationService;
    private final ReportAutomationService reportAutomationService;
    private final RecommendationSnapshotService recommendationSnapshotService;
    private final TransactionTemplate txNew;

    public AutomationWorker(AutomationJobRepository jobRepository,
                            ObjectMapper objectMapper,
                            KpiAutomationService kpiAutomationService,
                            ReportAutomationService reportAutomationService,
                            RecommendationSnapshotService recommendationSnapshotService,
                            PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper;
        this.kpiAutomationService = kpiAutomationService;
        this.reportAutomationService = reportAutomationService;
        this.recommendationSnapshotService = recommendationSnapshotService;
        this.txNew = new TransactionTemplate(transactionManager);
        this.txNew.setPropagationBehaviorName("PROPAGATION_REQUIRES_NEW");
    }

    @Scheduled(fixedDelayString = "${app.scheduler.automation-worker-fixed-delay-ms:5000}")
    public void tick() {
        List<AutomationJob> due = jobRepository.findTop25ByStatusInAndRunAfterBeforeOrderByRunAfterAscIdAsc(
            List.of(AutomationJobStatus.PENDING, AutomationJobStatus.RETRY),
            Instant.now()
        );
        for (AutomationJob job : due) {
            boolean claimed = Boolean.TRUE.equals(txNew.execute(status ->
                jobRepository.claim(job.getId(), List.of(AutomationJobStatus.PENDING, AutomationJobStatus.RETRY), AutomationJobStatus.RUNNING, Instant.now()) == 1
            ));
            if (!claimed) continue;
            txNew.execute(status -> {
                runInternal(job.getId());
                return null;
            });
        }
    }

    protected void runInternal(Long jobId) {
        AutomationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) return;
        try {
            Map<String, Object> payload = parse(job.getPayloadJson());
            Long companyId = job.getCompany() != null ? job.getCompany().getId() : null;

            if (job.getType() == AutomationJobType.RECOMPUTE_KPIS) {
                int monthsBack = intPayload(payload, "monthsBack", 2);
                if (companyId != null) kpiAutomationService.recomputeRecent(companyId, monthsBack);
            } else if (job.getType() == AutomationJobType.GENERATE_MONTHLY_REPORT) {
                String period = stringPayload(payload, "period", YearMonth.now().minusMonths(1).toString());
                if (companyId != null) reportAutomationService.generateMonthly(companyId, period);
            } else if (job.getType() == AutomationJobType.SNAPSHOT_RECOMMENDATIONS) {
                String period = stringPayload(payload, "period", YearMonth.now().toString());
                if (companyId != null) recommendationSnapshotService.snapshot(companyId, period);
            }

            job.setStatus(AutomationJobStatus.SUCCESS);
            job.setUpdatedAt(Instant.now());
            job.setLastError(null);
            jobRepository.save(job);
        } catch (Exception ex) {
            handleFailure(job, ex);
        }
    }

    private void handleFailure(AutomationJob job, Exception ex) {
        int nextAttempts = job.getAttempts() + 1;
        job.setAttempts(nextAttempts);
        job.setUpdatedAt(Instant.now());
        job.setLastError(ex.getClass().getSimpleName() + ": " + ex.getMessage());

        if (nextAttempts >= job.getMaxAttempts()) {
            job.setStatus(AutomationJobStatus.DEAD);
            jobRepository.save(job);
            log.warn("automation job dead id={} type={} trace={} err={}", job.getId(), job.getType(), job.getTraceId(), job.getLastError());
            return;
        }

        long backoffSeconds = Math.min(3600, (long) Math.pow(2, Math.min(10, nextAttempts)) * 10L);
        job.setStatus(AutomationJobStatus.RETRY);
        job.setRunAfter(Instant.now().plusSeconds(backoffSeconds));
        jobRepository.save(job);
        log.info("automation job retry id={} type={} trace={} in={}s err={}", job.getId(), job.getType(), job.getTraceId(), backoffSeconds, job.getLastError());
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static int intPayload(Map<String, Object> payload, String key, int fallback) {
        Object raw = payload.get(key);
        if (raw instanceof Number n) return n.intValue();
        try {
            return raw == null ? fallback : Integer.parseInt(String.valueOf(raw));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String stringPayload(Map<String, Object> payload, String key, String fallback) {
        Object raw = payload.get(key);
        String val = raw == null ? null : String.valueOf(raw).trim();
        return (val == null || val.isBlank()) ? fallback : val;
    }
}

package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.AutomationJobType;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AutomationEnqueuer {
    private final CompanyRepository companyRepository;
    private final AutomationJobService jobService;
    private final ObjectMapper objectMapper;

    public AutomationEnqueuer(CompanyRepository companyRepository, AutomationJobService jobService, ObjectMapper objectMapper) {
        this.companyRepository = companyRepository;
        this.jobService = jobService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(cron = "${app.scheduler.kpi-daily-cron:0 15 2 * * *}")
    public void enqueueDailyKpiRecompute() {
        for (var company : companyRepository.findAll()) {
            if (!jobService.hasActiveJob(company.getId(), AutomationJobType.RECOMPUTE_KPIS)) {
                jobService.enqueue(company.getId(), AutomationJobType.RECOMPUTE_KPIS, Instant.now(), json(Map.of("monthsBack", 2)));
            }
            if (!jobService.hasActiveJob(company.getId(), AutomationJobType.SNAPSHOT_RECOMMENDATIONS)) {
                jobService.enqueue(company.getId(), AutomationJobType.SNAPSHOT_RECOMMENDATIONS, Instant.now(), json(Map.of("period", YearMonth.now().toString())));
            }
        }
    }

    @Scheduled(cron = "${app.scheduler.kpi-weekly-cron:0 30 3 * * 0}")
    public void enqueueWeeklyRecompute() {
        for (var company : companyRepository.findAll()) {
            if (!jobService.hasActiveJob(company.getId(), AutomationJobType.RECOMPUTE_KPIS)) {
                jobService.enqueue(company.getId(), AutomationJobType.RECOMPUTE_KPIS, Instant.now(), json(Map.of("monthsBack", 6)));
            }
            if (!jobService.hasActiveJob(company.getId(), AutomationJobType.SNAPSHOT_RECOMMENDATIONS)) {
                jobService.enqueue(company.getId(), AutomationJobType.SNAPSHOT_RECOMMENDATIONS, Instant.now(), json(Map.of("period", YearMonth.now().toString())));
            }
        }
    }

    @Scheduled(cron = "${app.scheduler.report-monthly-cron:0 0 6 1 * *}")
    public void enqueueMonthlyReports() {
        String period = YearMonth.now().minusMonths(1).toString();
        for (var company : companyRepository.findAll()) {
            if (!jobService.hasActiveJob(company.getId(), AutomationJobType.GENERATE_MONTHLY_REPORT)) {
                jobService.enqueue(company.getId(), AutomationJobType.GENERATE_MONTHLY_REPORT, Instant.now(), json(Map.of("period", period)));
            }
            if (!jobService.hasActiveJob(company.getId(), AutomationJobType.SNAPSHOT_RECOMMENDATIONS)) {
                jobService.enqueue(company.getId(), AutomationJobType.SNAPSHOT_RECOMMENDATIONS, Instant.now(), json(Map.of("period", period)));
            }
        }
    }

    private String json(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return null;
        }
    }
}

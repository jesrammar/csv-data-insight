package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.AutomationJobDto;
import com.asecon.enterpriseiq.model.AutomationJobType;
import com.asecon.enterpriseiq.repo.AutomationJobRepository;
import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.AutomationJobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies/{companyId}/automation")
@PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
public class AutomationController {
    private final AccessService accessService;
    private final AutomationJobService jobService;
    private final AutomationJobRepository jobRepository;
    private final ObjectMapper objectMapper;

    public AutomationController(AccessService accessService,
                                AutomationJobService jobService,
                                AutomationJobRepository jobRepository,
                                ObjectMapper objectMapper) {
        this.accessService = accessService;
        this.jobService = jobService;
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/jobs")
    public List<AutomationJobDto> jobs(@PathVariable Long companyId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        return jobRepository.findByCompany_IdOrderByCreatedAtDesc(companyId).stream()
            .limit(50)
            .map(j -> new AutomationJobDto(
                j.getId(),
                companyId,
                j.getType().name(),
                j.getStatus().name(),
                j.getAttempts(),
                j.getMaxAttempts(),
                j.getRunAfter(),
                j.getCreatedAt(),
                j.getUpdatedAt(),
                j.getTraceId(),
                j.getLastError()
            ))
            .collect(Collectors.toList());
    }

    @PostMapping("/kpis/recompute")
    public AutomationJobDto recomputeKpis(@PathVariable Long companyId, @RequestParam(defaultValue = "2") @Min(1) int monthsBack) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        var job = jobService.enqueue(companyId, AutomationJobType.RECOMPUTE_KPIS, Instant.now(), json(Map.of("monthsBack", monthsBack)));
        return new AutomationJobDto(job.getId(), companyId, job.getType().name(), job.getStatus().name(), job.getAttempts(), job.getMaxAttempts(), job.getRunAfter(), job.getCreatedAt(), job.getUpdatedAt(), job.getTraceId(), job.getLastError());
    }

    @PostMapping("/reports/monthly")
    public AutomationJobDto monthlyReport(@PathVariable Long companyId, @RequestParam(required = false) String period) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        String resolved = (period == null || period.isBlank()) ? YearMonth.now().minusMonths(1).toString() : period.trim();
        var job = jobService.enqueue(companyId, AutomationJobType.GENERATE_MONTHLY_REPORT, Instant.now(), json(Map.of("period", resolved)));
        return new AutomationJobDto(job.getId(), companyId, job.getType().name(), job.getStatus().name(), job.getAttempts(), job.getMaxAttempts(), job.getRunAfter(), job.getCreatedAt(), job.getUpdatedAt(), job.getTraceId(), job.getLastError());
    }

    @PostMapping("/recommendations/snapshot")
    public AutomationJobDto snapshotRecommendations(@PathVariable Long companyId,
                                                    @RequestParam(required = false) String period,
                                                    @RequestParam(required = false) String objective) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        String resolved = (period == null || period.isBlank()) ? YearMonth.now().toString() : period.trim();
        var job = jobService.enqueue(
            companyId,
            AutomationJobType.SNAPSHOT_RECOMMENDATIONS,
            Instant.now(),
            json(Map.of("period", resolved, "objective", objective))
        );
        return new AutomationJobDto(job.getId(), companyId, job.getType().name(), job.getStatus().name(), job.getAttempts(), job.getMaxAttempts(), job.getRunAfter(), job.getCreatedAt(), job.getUpdatedAt(), job.getTraceId(), job.getLastError());
    }

    private String json(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return null;
        }
    }
}

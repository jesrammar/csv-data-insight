package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.AutomationJob;
import com.asecon.enterpriseiq.model.AutomationJobStatus;
import com.asecon.enterpriseiq.model.AutomationJobType;
import com.asecon.enterpriseiq.repo.AutomationJobRepository;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AutomationJobService {
    private final AutomationJobRepository jobRepository;
    private final CompanyRepository companyRepository;

    public AutomationJobService(AutomationJobRepository jobRepository, CompanyRepository companyRepository) {
        this.jobRepository = jobRepository;
        this.companyRepository = companyRepository;
    }

    @Transactional
    public AutomationJob enqueue(Long companyId, AutomationJobType type, Instant runAfter, String payloadJson) {
        AutomationJob job = new AutomationJob();
        if (companyId != null) {
            job.setCompany(companyRepository.findById(companyId).orElseThrow());
        }
        job.setType(type);
        job.setStatus(AutomationJobStatus.PENDING);
        job.setAttempts(0);
        job.setMaxAttempts(5);
        job.setRunAfter(runAfter == null ? Instant.now() : runAfter);
        job.setCreatedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        job.setTraceId(UUID.randomUUID().toString().replace("-", ""));
        job.setPayloadJson(payloadJson);
        return jobRepository.save(job);
    }

    public boolean hasActiveJob(Long companyId, AutomationJobType type) {
        if (companyId == null) return false;
        return jobRepository.existsByCompany_IdAndTypeAndStatusIn(companyId, type, List.of(AutomationJobStatus.PENDING, AutomationJobStatus.RETRY, AutomationJobStatus.RUNNING));
    }
}

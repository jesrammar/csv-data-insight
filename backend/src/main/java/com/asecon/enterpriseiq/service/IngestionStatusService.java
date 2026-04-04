package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.ImportDto;
import com.asecon.enterpriseiq.dto.IngestionStatusDto;
import com.asecon.enterpriseiq.model.ImportJob;
import com.asecon.enterpriseiq.model.ImportStatus;
import com.asecon.enterpriseiq.repo.ImportJobRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class IngestionStatusService {
    private final ImportJobRepository importJobRepository;

    public IngestionStatusService(ImportJobRepository importJobRepository) {
        this.importJobRepository = importJobRepository;
    }

    public IngestionStatusDto statusForCompany(Long companyId) {
        ImportDto last = importJobRepository.findFirstByCompanyIdOrderByCreatedAtDesc(companyId).map(this::toDto).orElse(null);
        ImportDto lastProcessed = importJobRepository.findFirstByCompanyIdAndProcessedAtNotNullOrderByProcessedAtDesc(companyId).map(this::toDto).orElse(null);
        ImportDto next = importJobRepository.findFirstByCompanyIdAndStatusInOrderByRunAfterAscIdAsc(
            companyId,
            List.of(ImportStatus.PENDING, ImportStatus.RETRY)
        ).map(this::toDto).orElse(null);
        return new IngestionStatusDto(Instant.now(), last, lastProcessed, next);
    }

    private ImportDto toDto(ImportJob job) {
        return new ImportDto(job.getId(), job.getCompany().getId(), job.getPeriod(), job.getStatus(),
            job.getCreatedAt(), job.getProcessedAt(), job.getErrorSummary(), job.getWarningCount(), job.getErrorCount(),
            job.getUpdatedAt(), job.getRunAfter(), job.getAttempts(), job.getMaxAttempts(), job.getLastError(), job.getStorageRef(), job.getOriginalFilename());
    }
}


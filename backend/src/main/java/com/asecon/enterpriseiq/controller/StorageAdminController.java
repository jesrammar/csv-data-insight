package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.StorageCleanupResultDto;
import com.asecon.enterpriseiq.service.StorageRetentionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/storage/cleanup")
@PreAuthorize("hasRole('ADMIN')")
public class StorageAdminController {
    private final StorageRetentionService storageRetentionService;

    public StorageAdminController(StorageRetentionService storageRetentionService) {
        this.storageRetentionService = storageRetentionService;
    }

    @PostMapping("/run")
    public StorageCleanupResultDto run() {
        return toDto(storageRetentionService.cleanupNow());
    }

    @GetMapping("/last")
    public StorageCleanupResultDto last() {
        var last = storageRetentionService.getLastResult();
        return last == null ? null : toDto(last);
    }

    private static StorageCleanupResultDto toDto(StorageRetentionService.CleanupResult r) {
        return new StorageCleanupResultDto(
            r.startedAt(),
            r.finishedAt(),
            r.enabled(),
            r.errors(),
            new StorageCleanupResultDto.Counts(r.imports().refsCleared()),
            new StorageCleanupResultDto.Counts(r.reports().refsCleared()),
            new StorageCleanupResultDto.Counts(r.universal().refsCleared())
        );
    }
}


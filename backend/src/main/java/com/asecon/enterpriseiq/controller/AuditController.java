package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.AuditEventDto;
import com.asecon.enterpriseiq.repo.AuditEventRepository;
import com.asecon.enterpriseiq.service.AccessService;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies/{companyId}/audit")
@PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
public class AuditController {
    private final AccessService accessService;
    private final AuditEventRepository auditEventRepository;

    public AuditController(AccessService accessService, AuditEventRepository auditEventRepository) {
        this.accessService = accessService;
        this.auditEventRepository = auditEventRepository;
    }

    @GetMapping
    public List<AuditEventDto> list(@PathVariable Long companyId, @RequestParam(defaultValue = "50") int limit) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return auditEventRepository.findByCompanyIdOrderByAtDesc(companyId, PageRequest.of(0, safeLimit)).stream()
            .map(e -> new AuditEventDto(
                e.getId(),
                e.getAt(),
                e.getUserId(),
                e.getCompanyId(),
                e.getAction(),
                e.getMethod(),
                e.getPath(),
                e.getStatus(),
                e.getDurationMs(),
                e.getResourceType(),
                e.getResourceId(),
                e.getMetaJson()
            ))
            .toList();
    }
}


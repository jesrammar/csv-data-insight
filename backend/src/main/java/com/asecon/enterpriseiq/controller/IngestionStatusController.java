package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.IngestionStatusDto;
import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.IngestionStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies/{companyId}/ingestion")
public class IngestionStatusController {
    private final AccessService accessService;
    private final IngestionStatusService ingestionStatusService;

    public IngestionStatusController(AccessService accessService, IngestionStatusService ingestionStatusService) {
        this.accessService = accessService;
        this.ingestionStatusService = ingestionStatusService;
    }

    @GetMapping("/status")
    public IngestionStatusDto status(@PathVariable Long companyId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        return ingestionStatusService.statusForCompany(companyId);
    }
}


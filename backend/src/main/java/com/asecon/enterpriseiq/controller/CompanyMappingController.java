package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.CompanySavedMappingService;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies/{companyId}/mappings")
public class CompanyMappingController {
    private final AccessService accessService;
    private final CompanySavedMappingService mappingService;

    public CompanyMappingController(AccessService accessService, CompanySavedMappingService mappingService) {
        this.accessService = accessService;
        this.mappingService = mappingService;
    }

    @GetMapping(value = "/{mappingKey}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR','CLIENTE')")
    public String get(@PathVariable Long companyId, @PathVariable String mappingKey) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        String json = mappingService.getPayload(companyId, mappingKey);
        return json == null ? "{}" : json;
    }

    @PutMapping(value = "/{mappingKey}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public void upsert(@PathVariable Long companyId, @PathVariable String mappingKey, @RequestBody String payloadJson) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        mappingService.upsert(companyId, mappingKey, payloadJson);
    }
}


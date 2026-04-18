package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.CompanySavedMapping;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.asecon.enterpriseiq.repo.CompanySavedMappingRepository;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CompanySavedMappingService {
    public static final String KEY_IMPORTS_SMART = "imports.smart";

    private final CompanySavedMappingRepository mappingRepository;
    private final CompanyRepository companyRepository;

    public CompanySavedMappingService(CompanySavedMappingRepository mappingRepository, CompanyRepository companyRepository) {
        this.mappingRepository = mappingRepository;
        this.companyRepository = companyRepository;
    }

    public String getPayload(Long companyId, String mappingKey) {
        if (companyId == null || mappingKey == null || mappingKey.isBlank()) return null;
        return mappingRepository.findByCompany_IdAndMappingKey(companyId, mappingKey.trim())
            .map(CompanySavedMapping::getPayloadJson)
            .orElse(null);
    }

    @Transactional
    public void upsert(Long companyId, String mappingKey, String payloadJson) {
        if (companyId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "companyId required");
        if (mappingKey == null || mappingKey.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mappingKey required");
        if (payloadJson == null || payloadJson.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payloadJson required");
        String key = mappingKey.trim();
        CompanySavedMapping m = mappingRepository.findByCompany_IdAndMappingKey(companyId, key).orElse(null);
        if (m == null) {
            m = new CompanySavedMapping();
            m.setCompany(companyRepository.findById(companyId).orElseThrow());
            m.setMappingKey(key);
            m.setCreatedAt(Instant.now());
        }
        m.setPayloadJson(payloadJson);
        m.setUpdatedAt(Instant.now());
        mappingRepository.save(m);
    }
}


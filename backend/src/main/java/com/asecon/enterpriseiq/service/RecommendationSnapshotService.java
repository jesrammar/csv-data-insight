package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.AdvisorRecommendation;
import com.asecon.enterpriseiq.repo.AdvisorRecommendationRepository;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecommendationSnapshotService {
    private final AdvisorAssistantService advisorAssistantService;
    private final AdvisorRecommendationRepository recommendationRepository;
    private final CompanyRepository companyRepository;
    private final ObjectMapper objectMapper;

    public RecommendationSnapshotService(AdvisorAssistantService advisorAssistantService,
                                         AdvisorRecommendationRepository recommendationRepository,
                                         CompanyRepository companyRepository,
                                         ObjectMapper objectMapper) {
        this.advisorAssistantService = advisorAssistantService;
        this.recommendationRepository = recommendationRepository;
        this.companyRepository = companyRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AdvisorRecommendation snapshot(Long companyId, String period) {
        return snapshot(companyId, period, null);
    }

    @Transactional
    public AdvisorRecommendation snapshot(Long companyId, String period, String objective) {
        String resolvedPeriod = (period == null || period.isBlank()) ? java.time.YearMonth.now().toString() : period;
        String source = RecommendationObjective.toSource(objective);
        var existing = recommendationRepository.findByCompany_IdAndPeriodAndSource(companyId, resolvedPeriod, source);
        if (existing.isPresent()) return existing.get();

        var company = companyRepository.findById(companyId).orElseThrow();
        var rec = advisorAssistantService.recommendations(companyId, resolvedPeriod, objective);

        AdvisorRecommendation entity = new AdvisorRecommendation();
        entity.setCompany(company);
        entity.setPeriod(resolvedPeriod);
        entity.setCreatedAt(Instant.now());
        entity.setSource(source);
        entity.setSummary(rec.summary());
        try {
            entity.setActionsJson(objectMapper.writeValueAsString(rec.actions()));
        } catch (Exception e) {
            entity.setActionsJson("[]");
        }
        return recommendationRepository.save(entity);
    }
}


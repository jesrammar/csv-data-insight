package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.AdvisorActionDto;
import com.asecon.enterpriseiq.dto.AdvisorRecommendationSnapshotDto;
import com.asecon.enterpriseiq.repo.AdvisorRecommendationRepository;
import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.AdvisorAssistantService;
import com.asecon.enterpriseiq.service.RecommendationObjective;
import com.asecon.enterpriseiq.service.RecommendationSnapshotService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies/{companyId}/recommendations")
public class RecommendationController {
    private final AccessService accessService;
    private final AdvisorAssistantService advisorAssistantService;
    private final RecommendationSnapshotService snapshotService;
    private final AdvisorRecommendationRepository recommendationRepository;
    private final ObjectMapper objectMapper;

    public RecommendationController(AccessService accessService,
                                    AdvisorAssistantService advisorAssistantService,
                                    RecommendationSnapshotService snapshotService,
                                    AdvisorRecommendationRepository recommendationRepository,
                                    ObjectMapper objectMapper) {
        this.accessService = accessService;
        this.advisorAssistantService = advisorAssistantService;
        this.snapshotService = snapshotService;
        this.recommendationRepository = recommendationRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/latest")
    public AdvisorRecommendationSnapshotDto latest(@PathVariable Long companyId, @RequestParam(required = false) String objective) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        var rec = (objective == null || objective.isBlank())
            ? recommendationRepository.findFirstByCompany_IdOrderByCreatedAtDesc(companyId).orElse(null)
            : recommendationRepository.findFirstByCompany_IdAndSourceOrderByCreatedAtDesc(companyId, RecommendationObjective.toSource(objective)).orElse(null);
        return toDto(rec);
    }

    @GetMapping("/preview")
    public AdvisorRecommendationSnapshotDto preview(@PathVariable Long companyId,
                                                    @RequestParam(required = false) String period,
                                                    @RequestParam(required = false) String objective) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        String resolvedPeriod = (period == null || period.isBlank()) ? YearMonth.now().toString() : period.trim();
        String source = RecommendationObjective.toSource(objective);
        var rec = advisorAssistantService.recommendations(companyId, resolvedPeriod, objective);
        return new AdvisorRecommendationSnapshotDto(
            null,
            companyId,
            resolvedPeriod,
            source,
            Instant.now(),
            rec.summary(),
            rec.actions() == null ? List.of() : rec.actions()
        );
    }

    @PostMapping("/snapshot/{period}")
    public AdvisorRecommendationSnapshotDto snapshot(@PathVariable Long companyId,
                                                     @PathVariable String period,
                                                     @RequestParam(required = false) String objective) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        var rec = snapshotService.snapshot(companyId, period, objective);
        return toDto(rec);
    }

    private AdvisorRecommendationSnapshotDto toDto(com.asecon.enterpriseiq.model.AdvisorRecommendation rec) {
        if (rec == null) {
            return new AdvisorRecommendationSnapshotDto(null, null, null, "RULES", null, null, List.of());
        }
        List<AdvisorActionDto> actions = List.of();
        try {
            actions = objectMapper.readValue(rec.getActionsJson(), new TypeReference<List<AdvisorActionDto>>() {});
        } catch (Exception ignored) {}
        return new AdvisorRecommendationSnapshotDto(
            rec.getId(),
            rec.getCompany().getId(),
            rec.getPeriod(),
            rec.getSource(),
            rec.getCreatedAt(),
            rec.getSummary(),
            actions
        );
    }
}


package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.AdvisorActionFollowUpDto;
import com.asecon.enterpriseiq.dto.AdvisorActionFollowUpStatusUpdateDto;
import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.AdvisorActionFollowUpService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies/{companyId}/recommendations")
public class RecommendationFollowUpController {
    private final AccessService accessService;
    private final AdvisorActionFollowUpService followUpService;

    public RecommendationFollowUpController(AccessService accessService,
                                            AdvisorActionFollowUpService followUpService) {
        this.accessService = accessService;
        this.followUpService = followUpService;
    }

    @GetMapping("/period/{period}/follow-ups")
    public List<AdvisorActionFollowUpDto> list(@PathVariable Long companyId,
                                               @PathVariable String period,
                                               @RequestParam(required = false) String objective) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        return followUpService.listForPeriod(companyId, period, objective);
    }

    @PostMapping("/follow-ups/{followUpId}/status")
    public AdvisorActionFollowUpDto updateStatus(@PathVariable Long companyId,
                                                 @PathVariable Long followUpId,
                                                 @RequestBody AdvisorActionFollowUpStatusUpdateDto request) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        return followUpService.updateStatus(companyId, followUpId, request == null ? null : request.status(), user);
    }
}

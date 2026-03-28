package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.AlertDto;
import com.asecon.enterpriseiq.repo.AlertRepository;
import com.asecon.enterpriseiq.service.AccessService;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies/{companyId}/alerts")
public class AlertController {
    private final AlertRepository alertRepository;
    private final AccessService accessService;

    public AlertController(AlertRepository alertRepository, AccessService accessService) {
        this.alertRepository = alertRepository;
        this.accessService = accessService;
    }

    @GetMapping
    public List<AlertDto> list(@PathVariable Long companyId, @RequestParam(required = false) String period) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        var alerts = (period == null || period.isBlank())
            ? alertRepository.findTop20ByCompanyIdOrderByCreatedAtDesc(companyId)
            : alertRepository.findByCompanyIdAndPeriod(companyId, period.trim());
        return alerts.stream().map(a -> new AlertDto(
            a.getId(),
            companyId,
            a.getPeriod(),
            a.getType().name(),
            a.getMessage(),
            a.getCreatedAt()
        )).collect(Collectors.toList());
    }
}


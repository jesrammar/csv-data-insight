package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.PeriodWorkflowDto;
import com.asecon.enterpriseiq.dto.PortfolioWorkflowStepDto;
import com.asecon.enterpriseiq.model.PeriodWorkflow;
import com.asecon.enterpriseiq.repo.PeriodWorkflowRepository;
import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.PeriodWorkflowService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/companies/{companyId}/period-workflows")
public class PeriodWorkflowController {
    private final PeriodWorkflowRepository periodWorkflowRepository;
    private final AccessService accessService;
    private final PeriodWorkflowService periodWorkflowService;

    public PeriodWorkflowController(PeriodWorkflowRepository periodWorkflowRepository,
                                    AccessService accessService,
                                    PeriodWorkflowService periodWorkflowService) {
        this.periodWorkflowRepository = periodWorkflowRepository;
        this.accessService = accessService;
        this.periodWorkflowService = periodWorkflowService;
    }

    @GetMapping
    public List<PeriodWorkflowDto> list(@PathVariable Long companyId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        return periodWorkflowRepository.findByCompanyIdOrderByPeriodDesc(companyId).stream()
            .map(this::toDto)
            .toList();
    }

    @GetMapping("/{period}")
    public PeriodWorkflowDto get(@PathVariable Long companyId, @PathVariable String period) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        return periodWorkflowRepository.findByCompanyIdAndPeriod(companyId, period)
            .map(this::toDto)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow del periodo no encontrado."));
    }

    @PostMapping("/{period}/review")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public PeriodWorkflowDto review(@PathVariable Long companyId, @PathVariable String period) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        return toDto(periodWorkflowService.markReviewed(companyId, period, user));
    }

    @PostMapping("/{period}/close")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public PeriodWorkflowDto close(@PathVariable Long companyId, @PathVariable String period) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        return toDto(periodWorkflowService.markClosed(companyId, period, user));
    }

    private PeriodWorkflowDto toDto(PeriodWorkflow workflow) {
        PortfolioWorkflowStepDto portfolioStep = periodWorkflowService.resolvePortfolioStep(workflow);
        PeriodWorkflowService.WorkflowPresentation presentation = periodWorkflowService.presentWorkflow(workflow);
        PeriodWorkflowService.OrchestrationPresentation orchestration = periodWorkflowService.presentOrchestration(workflow);
        return new PeriodWorkflowDto(
            workflow.getId(),
            workflow.getCompany() == null ? null : workflow.getCompany().getId(),
            workflow.getPeriod(),
            workflow.getStatus(),
            presentation.title(),
            presentation.detail(),
            presentation.badgeTone(),
            presentation.primaryActionLabel(),
            orchestration.status(),
            orchestration.runnable(),
            orchestration.autoCloseReady(),
            orchestration.title(),
            orchestration.detail(),
            orchestration.actionLabel(),
            workflow.getPriority(),
            workflow.getBlockingCode(),
            workflow.getBlockingReason(),
            workflow.getExceptionCount(),
            workflow.getSourceImport() == null ? null : workflow.getSourceImport().getId(),
            workflow.getSourceImport() == null || workflow.getSourceImport().getStatus() == null ? null : workflow.getSourceImport().getStatus().name(),
            workflow.getReport() == null ? null : workflow.getReport().getId(),
            workflow.getReport() == null || workflow.getReport().getStatus() == null ? null : workflow.getReport().getStatus().name(),
            workflow.getRecommendationSnapshot() == null ? null : workflow.getRecommendationSnapshot().getId(),
            workflow.getRecommendationSnapshot() == null ? null : workflow.getRecommendationSnapshot().getSummary(),
            workflow.getRecommendationSnapshot() == null ? null : workflow.getRecommendationSnapshot().getCreatedAt(),
            portfolioStep,
            workflow.getOwnerUser() == null ? null : workflow.getOwnerUser().getId(),
            workflow.getStartedAt(),
            workflow.getUpdatedAt(),
            workflow.getReviewedAt(),
            workflow.getClosedAt(),
            workflow.getNotes()
        );
    }
}

package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.PeriodWorkflow;
import com.asecon.enterpriseiq.model.PeriodWorkflowStatus;
import com.asecon.enterpriseiq.repo.PeriodWorkflowRepository;
import java.io.IOException;
import java.time.YearMonth;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PeriodCloseAutomationService {
    private final PeriodWorkflowRepository periodWorkflowRepository;
    private final PeriodWorkflowService periodWorkflowService;
    private final ReportAutomationService reportAutomationService;

    public PeriodCloseAutomationService(PeriodWorkflowRepository periodWorkflowRepository,
                                        PeriodWorkflowService periodWorkflowService,
                                        ReportAutomationService reportAutomationService) {
        this.periodWorkflowRepository = periodWorkflowRepository;
        this.periodWorkflowService = periodWorkflowService;
        this.reportAutomationService = reportAutomationService;
    }

    @Transactional
    public PeriodWorkflow orchestrate(Long companyId, String period) throws IOException {
        String resolvedPeriod = normalizePeriod(period);
        PeriodWorkflow workflow = periodWorkflowRepository.findByCompanyIdAndPeriod(companyId, resolvedPeriod)
            .orElseThrow(() -> new IllegalStateException("No existe workflow para el periodo " + resolvedPeriod + "."));

        if (workflow.getStatus() == PeriodWorkflowStatus.PENDING_DATA
            || workflow.getStatus() == PeriodWorkflowStatus.INGESTING
            || workflow.getStatus() == PeriodWorkflowStatus.EXCEPTIONS) {
            throw new IllegalStateException(workflow.getNotes() == null || workflow.getNotes().isBlank()
                ? "El periodo todavia no esta listo para automatizar."
                : workflow.getNotes());
        }

        if (workflow.getStatus() == PeriodWorkflowStatus.CLOSED) {
            return workflow;
        }

        if (workflow.getReviewedAt() == null) {
            workflow = periodWorkflowService.markReviewedAutomatically(companyId, resolvedPeriod);
            if (workflow.getStatus() == PeriodWorkflowStatus.CLOSED) {
                return workflow;
            }
        }

        if (workflow.getStatus() == PeriodWorkflowStatus.REPORT_READY) {
            return periodWorkflowService.tryCloseAutomatically(companyId, resolvedPeriod);
        }

        if (workflow.getStatus() == PeriodWorkflowStatus.REPORT_GENERATING) {
            return workflow;
        }

        periodWorkflowService.markReportGenerating(companyId, resolvedPeriod);
        reportAutomationService.generateMonthly(companyId, resolvedPeriod);

        PeriodWorkflow refreshed = periodWorkflowRepository.findByCompanyIdAndPeriod(companyId, resolvedPeriod)
            .orElseThrow(() -> new IllegalStateException("No se pudo refrescar el workflow tras generar el informe."));

        if (refreshed.getStatus() == PeriodWorkflowStatus.REPORT_READY) {
            return periodWorkflowService.tryCloseAutomatically(companyId, resolvedPeriod);
        }
        return refreshed;
    }

    private static String normalizePeriod(String period) {
        if (period == null || period.isBlank()) {
            return YearMonth.now().minusMonths(1).toString();
        }
        return YearMonth.parse(period.trim()).toString();
    }
}

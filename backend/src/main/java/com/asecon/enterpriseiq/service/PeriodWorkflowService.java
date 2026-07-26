package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.PortfolioWorkflowStepDto;
import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.AdvisorRecommendation;
import com.asecon.enterpriseiq.model.ImportJob;
import com.asecon.enterpriseiq.model.ImportStatus;
import com.asecon.enterpriseiq.model.PeriodWorkflow;
import com.asecon.enterpriseiq.model.PeriodWorkflowStatus;
import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.model.PortfolioWorkflowStatus;
import com.asecon.enterpriseiq.model.Report;
import com.asecon.enterpriseiq.model.ReportStatus;
import com.asecon.enterpriseiq.model.TribunalImport;
import com.asecon.enterpriseiq.model.User;
import com.asecon.enterpriseiq.repo.PeriodWorkflowRepository;
import com.asecon.enterpriseiq.repo.TribunalImportRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PeriodWorkflowService {
    public record WorkflowPresentation(String title, String detail, String badgeTone, String primaryActionLabel) {}
    public record OrchestrationPresentation(String status,
                                            boolean runnable,
                                            boolean autoCloseReady,
                                            String title,
                                            String detail,
                                            String actionLabel) {}

    private final PeriodWorkflowRepository periodWorkflowRepository;
    private final RecommendationSnapshotService recommendationSnapshotService;
    private final TribunalImportRepository tribunalImportRepository;

    public PeriodWorkflowService(PeriodWorkflowRepository periodWorkflowRepository,
                                 RecommendationSnapshotService recommendationSnapshotService,
                                 TribunalImportRepository tribunalImportRepository) {
        this.periodWorkflowRepository = periodWorkflowRepository;
        this.recommendationSnapshotService = recommendationSnapshotService;
        this.tribunalImportRepository = tribunalImportRepository;
    }

    @Transactional
    public PeriodWorkflow ensurePendingData(Company company, String period) {
        PeriodWorkflow workflow = periodWorkflowRepository.findByCompanyIdAndPeriod(company.getId(), period)
            .orElseGet(() -> newWorkflow(company, period));
        syncPortfolioImportReference(workflow, false);
        if (workflow.getSourceImport() == null) {
            workflow.setStatus(PeriodWorkflowStatus.PENDING_DATA);
            workflow.setBlockingCode(null);
            workflow.setBlockingReason(null);
            workflow.setExceptionCount(0);
        }
        workflow.setUpdatedAt(Instant.now());
        refreshNotes(workflow);
        return periodWorkflowRepository.save(workflow);
    }

    @Transactional
    public PeriodWorkflow syncFromImport(ImportJob importJob) {
        if (importJob == null || importJob.getCompany() == null || importJob.getPeriod() == null || importJob.getPeriod().isBlank()) {
            return null;
        }

        PeriodWorkflow workflow = periodWorkflowRepository.findByCompanyIdAndPeriod(importJob.getCompany().getId(), importJob.getPeriod())
            .orElseGet(() -> newWorkflow(importJob.getCompany(), importJob.getPeriod()));

        reopenForFreshInput(workflow, importJob);
        workflow.setSourceImport(importJob);
        workflow.setUpdatedAt(Instant.now());
        syncPortfolioImportReference(workflow, false);
        workflow.setBlockingCode(importJob.getBlockingCode());
        workflow.setBlockingReason(importJob.getBlockingReason());

        ImportStatus status = importJob.getStatus();
        if (status == null) {
            workflow.setStatus(PeriodWorkflowStatus.PENDING_DATA);
            workflow.setExceptionCount(0);
            return periodWorkflowRepository.save(workflow);
        }

        switch (status) {
            case PENDING, RUNNING, RETRY -> {
                workflow.setStatus(PeriodWorkflowStatus.INGESTING);
                workflow.setExceptionCount(0);
                workflow.setBlockingCode(null);
                workflow.setBlockingReason(null);
            }
            case OK -> {
                workflow.setStatus(PeriodWorkflowStatus.READY_FOR_REVIEW);
                workflow.setExceptionCount(0);
                workflow.setBlockingCode(null);
                workflow.setBlockingReason(null);
            }
            case WARNING, BLOCKED, ERROR, DEAD -> {
                workflow.setStatus(PeriodWorkflowStatus.EXCEPTIONS);
                workflow.setExceptionCount(exceptionCount(importJob));
            }
        }

        refreshNotes(workflow);
        return periodWorkflowRepository.save(workflow);
    }

    @Transactional
    public PeriodWorkflow syncFromRecommendation(AdvisorRecommendation recommendation) {
        if (recommendation == null || recommendation.getCompany() == null || recommendation.getCompany().getId() == null) {
            return null;
        }
        String period = recommendation.getPeriod();
        if (period == null || period.isBlank()) {
            return null;
        }
        PeriodWorkflow workflow = periodWorkflowRepository.findByCompanyIdAndPeriod(recommendation.getCompany().getId(), period)
            .orElseGet(() -> newWorkflow(recommendation.getCompany(), period));
        workflow.setRecommendationSnapshot(recommendation);
        workflow.setUpdatedAt(Instant.now());
        syncPortfolioImportReference(workflow, workflow.getClosedAt() != null);
        refreshNotes(workflow);
        return periodWorkflowRepository.save(workflow);
    }

    @Transactional
    public PeriodWorkflow markReportGenerating(Long companyId, String period) {
        if (companyId == null || period == null || period.isBlank()) return null;
        PeriodWorkflow workflow = periodWorkflowRepository.findByCompanyIdAndPeriod(companyId, period).orElse(null);
        if (workflow == null) return null;
        if (workflow.getStatus() == PeriodWorkflowStatus.EXCEPTIONS || workflow.getStatus() == PeriodWorkflowStatus.CLOSED) {
            return workflow;
        }
        workflow.setStatus(PeriodWorkflowStatus.REPORT_GENERATING);
        workflow.setUpdatedAt(Instant.now());
        syncPortfolioImportReference(workflow, false);
        refreshNotes(workflow);
        return periodWorkflowRepository.save(workflow);
    }

    @Transactional
    public PeriodWorkflow syncFromReport(Report report) {
        if (report == null || report.getCompany() == null || report.getPeriod() == null || report.getPeriod().isBlank()) {
            return null;
        }
        PeriodWorkflow workflow = periodWorkflowRepository.findByCompanyIdAndPeriod(report.getCompany().getId(), report.getPeriod())
            .orElseGet(() -> newWorkflow(report.getCompany(), report.getPeriod()));
        workflow.setReport(report);
        workflow.setUpdatedAt(Instant.now());
        syncPortfolioImportReference(workflow, true);
        if (report.getStatus() == ReportStatus.READY) {
            attachRecommendationSnapshot(workflow);
            if (workflow.getReviewedAt() != null
                && workflow.getClosedAt() == null
                && portfolioStepAllowsClose(resolvePortfolioStep(workflow))) {
                closeWorkflow(workflow, null, true);
            } else if (workflow.getClosedAt() == null) {
                workflow.setStatus(PeriodWorkflowStatus.REPORT_READY);
            }
        } else {
            workflow.setStatus(PeriodWorkflowStatus.REPORT_GENERATING);
        }
        refreshNotes(workflow);
        return periodWorkflowRepository.save(workflow);
    }

    @Transactional
    public PeriodWorkflow markReviewed(Long companyId, String period, User actor) {
        PeriodWorkflow workflow = requireWorkflow(companyId, period);
        if (workflow.getStatus() == PeriodWorkflowStatus.PENDING_DATA
            || workflow.getStatus() == PeriodWorkflowStatus.INGESTING
            || workflow.getStatus() == PeriodWorkflowStatus.EXCEPTIONS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El periodo aun no esta listo para revision.");
        }
        Instant now = Instant.now();
        workflow.setReviewedAt(now);
        workflow.setUpdatedAt(now);
        syncPortfolioImportReference(workflow, false);
        if (workflow.getOwnerUser() == null && actor != null) {
            workflow.setOwnerUser(actor);
        }
        if (workflow.getStatus() == PeriodWorkflowStatus.READY_FOR_REVIEW) {
            workflow.setStatus(PeriodWorkflowStatus.REVIEWED);
        }
        if (shouldAutoCloseAfterReview(workflow)) {
            closeWorkflow(workflow, actor, true);
        }
        refreshNotes(workflow);
        return periodWorkflowRepository.save(workflow);
    }

    @Transactional
    public PeriodWorkflow markReviewedAutomatically(Long companyId, String period) {
        PeriodWorkflow workflow = requireWorkflow(companyId, period);
        if (workflow.getStatus() == PeriodWorkflowStatus.PENDING_DATA
            || workflow.getStatus() == PeriodWorkflowStatus.INGESTING
            || workflow.getStatus() == PeriodWorkflowStatus.EXCEPTIONS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El periodo aun no esta listo para revision automatica.");
        }
        if (workflow.getReviewedAt() == null) {
            Instant now = Instant.now();
            workflow.setReviewedAt(now);
            workflow.setUpdatedAt(now);
            if (workflow.getStatus() == PeriodWorkflowStatus.READY_FOR_REVIEW) {
                workflow.setStatus(PeriodWorkflowStatus.REVIEWED);
            }
        }
        if (shouldAutoCloseAfterReview(workflow)) {
            closeWorkflow(workflow, null, true);
        }
        refreshNotes(workflow);
        return periodWorkflowRepository.save(workflow);
    }

    @Transactional
    public PeriodWorkflow tryCloseAutomatically(Long companyId, String period) {
        PeriodWorkflow workflow = requireWorkflow(companyId, period);
        syncPortfolioImportReference(workflow, true);
        if (workflow.getClosedAt() != null) {
            return workflow;
        }
        if (!shouldAutoCloseAfterReview(workflow)) {
            refreshNotes(workflow);
            return periodWorkflowRepository.save(workflow);
        }
        closeWorkflow(workflow, null, true);
        refreshNotes(workflow);
        return periodWorkflowRepository.save(workflow);
    }

    @Transactional
    public PeriodWorkflow markClosed(Long companyId, String period, User actor) {
        PeriodWorkflow workflow = requireWorkflow(companyId, period);
        syncPortfolioImportReference(workflow, true);
        if (workflow.getReviewedAt() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede cerrar el periodo sin revision oficial.");
        }
        boolean hasReadyReport = workflow.getReport() != null && workflow.getReport().getStatus() == ReportStatus.READY;
        if (!hasReadyReport && workflow.getStatus() != PeriodWorkflowStatus.REPORT_READY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede cerrar el periodo sin un reporte listo.");
        }
        PortfolioWorkflowStepDto portfolioStep = resolvePortfolioStep(workflow);
        if (!portfolioStepAllowsClose(portfolioStep)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede cerrar el periodo mientras la cartera siga pendiente o desactualizada.");
        }
        closeWorkflow(workflow, actor, false);
        refreshNotes(workflow);
        return periodWorkflowRepository.save(workflow);
    }

    @Transactional(readOnly = true)
    public PortfolioWorkflowStepDto resolvePortfolioStep(PeriodWorkflow workflow) {
        if (workflow != null && workflow.getId() != null) {
            workflow = periodWorkflowRepository.findById(workflow.getId()).orElse(workflow);
        }

        if (workflow == null || workflow.getCompany() == null || workflow.getCompany().getId() == null) {
            return portfolioStep(false, PortfolioWorkflowStatus.NOT_APPLICABLE, "No aplica", "Workflow sin empresa asociada.", null);
        }

        Company company = workflow.getCompany();
        Optional<TribunalImport> latestImport = tribunalImportRepository.findFirstByCompanyIdOrderByCreatedAtDesc(company.getId());
        boolean applicable = usesTribunal(company, latestImport);

        if (!applicable) {
            return portfolioStep(false, PortfolioWorkflowStatus.NOT_APPLICABLE, "No aplica en este cliente", "Tribunal no forma parte del cierre principal de este cliente.", null);
        }

        TribunalImport effectiveImport = workflow.getPortfolioImport();
        if (effectiveImport == null) {
            effectiveImport = latestImport.orElse(null);
        } else if (workflow.getClosedAt() == null
            && latestImport.isPresent()
            && latestImport.get().getCreatedAt() != null
            && (effectiveImport.getCreatedAt() == null || latestImport.get().getCreatedAt().isAfter(effectiveImport.getCreatedAt()))) {
            effectiveImport = latestImport.get();
        }

        if (effectiveImport == null || effectiveImport.getCreatedAt() == null) {
            return portfolioStep(true, PortfolioWorkflowStatus.PENDING, "Pendiente de cartera", "Este cliente trabaja con Tribunal, pero todavia no hay una carga de cartera asociada al cierre.", null);
        }

        Instant latestDataEvent = latestWorkflowDataEvent(workflow);
        if (latestDataEvent != null && effectiveImport.getCreatedAt().isBefore(latestDataEvent)) {
            return portfolioStep(true, PortfolioWorkflowStatus.STALE, "Cartera desactualizada", "La ultima carga de Tribunal va por detras de la ultima carga real del periodo.", effectiveImport.getCreatedAt());
        }

        return portfolioStep(true, PortfolioWorkflowStatus.LOADED, "Cartera cargada", effectiveImport.getFilename(), effectiveImport.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public WorkflowPresentation presentWorkflow(PeriodWorkflow workflow) {
        if (workflow == null || workflow.getStatus() == null) {
            return new WorkflowPresentation("Sin workflow", "Aun no hay workflow generado para este periodo.", "", "Iniciar cierre");
        }
        return switch (workflow.getStatus()) {
            case PENDING_DATA -> new WorkflowPresentation(
                "Pendiente de datos",
                "Todavia no existe una base valida para este periodo.",
                "",
                "Cargar datos"
            );
            case INGESTING -> new WorkflowPresentation(
                "Ingesta en curso",
                "El sistema esta procesando la carga del periodo.",
                "warn",
                "Esperar procesamiento"
            );
            case EXCEPTIONS -> new WorkflowPresentation(
                "Periodo con excepciones",
                workflow.getBlockingReason() != null && !workflow.getBlockingReason().isBlank()
                    ? workflow.getBlockingReason()
                    : "Hay incidencias que resolver antes de cerrar el mes.",
                "err",
                "Resolver excepciones"
            );
            case READY_FOR_REVIEW -> new WorkflowPresentation(
                "Listo para revision",
                "La base del periodo ya esta preparada para revision funcional.",
                "warn",
                "Marcar revisado"
            );
            case REVIEWED -> new WorkflowPresentation(
                "Revisado",
                "La lectura esta validada; ya puedes materializar el entregable.",
                "warn",
                "Generar informe"
            );
            case REPORT_GENERATING -> new WorkflowPresentation(
                "Generando informe",
                "El entregable esta en preparacion para este periodo.",
                "warn",
                "Seguir informe"
            );
            case REPORT_READY -> new WorkflowPresentation(
                "Informe listo",
                "Ya existe un entregable disponible para este periodo.",
                "ok",
                "Cerrar periodo"
            );
            case CLOSED -> new WorkflowPresentation(
                "Periodo cerrado",
                "El cierre mensual ya quedo materializado.",
                "ok",
                "Ver cierre"
            );
        };
    }

    @Transactional(readOnly = true)
    public OrchestrationPresentation presentOrchestration(PeriodWorkflow workflow) {
        if (workflow == null || workflow.getStatus() == null) {
            return new OrchestrationPresentation(
                "BLOCKED",
                false,
                false,
                "Workflow no disponible",
                "Todavia no existe una base oficial del periodo sobre la que automatizar el cierre.",
                "Revisar workflow"
            );
        }

        PortfolioWorkflowStepDto portfolioStep = resolvePortfolioStep(workflow);

        return switch (workflow.getStatus()) {
            case PENDING_DATA -> new OrchestrationPresentation(
                "BLOCKED",
                false,
                false,
                "Falta una carga valida",
                "Sin base del periodo no se puede lanzar el cierre automatico.",
                "Cargar datos"
            );
            case INGESTING -> new OrchestrationPresentation(
                "RUNNING",
                false,
                false,
                "Ingestion en curso",
                "El sistema sigue preparando la base del periodo antes de poder automatizar el cierre.",
                "Esperar ingestion"
            );
            case EXCEPTIONS -> new OrchestrationPresentation(
                "BLOCKED",
                false,
                false,
                "Excepciones por resolver",
                workflow.getNotes() == null || workflow.getNotes().isBlank()
                    ? "La carga todavia no cumple las reglas minimas para automatizar el cierre."
                    : workflow.getNotes(),
                "Resolver excepciones"
            );
            case READY_FOR_REVIEW -> new OrchestrationPresentation(
                "AVAILABLE",
                true,
                false,
                "Automatizacion disponible",
                "La base ya es valida. El sistema puede revisar, generar informe, enlazar snapshot y cerrar si la cartera esta en regla.",
                "Automatizar cierre"
            );
            case REVIEWED -> new OrchestrationPresentation(
                "AVAILABLE",
                true,
                false,
                "Informe y snapshot pendientes",
                "La revision ya esta hecha. Automatizar ahora materializa el entregable y remata el cierre si nada bloquea la cartera.",
                "Automatizar cierre"
            );
            case REPORT_GENERATING -> new OrchestrationPresentation(
                "RUNNING",
                false,
                false,
                "Informe en generacion",
                "El sistema ya esta materializando el entregable del periodo.",
                "Seguir cierre"
            );
            case REPORT_READY -> reportReadyOrchestration(workflow, portfolioStep);
            case CLOSED -> new OrchestrationPresentation(
                "DONE",
                false,
                false,
                "Cierre ya materializado",
                workflow.getNotes() == null || workflow.getNotes().isBlank()
                    ? "El periodo ya quedo cerrado con entregable y snapshot enlazados."
                    : workflow.getNotes(),
                "Ver cierre"
            );
        };
    }

    private PeriodWorkflow requireWorkflow(Long companyId, String period) {
        return periodWorkflowRepository.findByCompanyIdAndPeriod(companyId, period)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow del periodo no encontrado."));
    }

    private PeriodWorkflow newWorkflow(Company company, String period) {
        PeriodWorkflow workflow = new PeriodWorkflow();
        Instant now = Instant.now();
        workflow.setCompany(company);
        workflow.setPeriod(period);
        workflow.setStatus(PeriodWorkflowStatus.PENDING_DATA);
        workflow.setPriority(0);
        workflow.setStartedAt(now);
        workflow.setUpdatedAt(now);
        workflow.setExceptionCount(0);
        refreshNotes(workflow);
        return workflow;
    }

    private void attachRecommendationSnapshot(PeriodWorkflow workflow) {
        if (workflow == null || workflow.getCompany() == null || workflow.getCompany().getId() == null) return;
        if (workflow.getPeriod() == null || workflow.getPeriod().isBlank()) return;
        if (workflow.getRecommendationSnapshot() != null) return;
        workflow.setRecommendationSnapshot(
            recommendationSnapshotService.snapshot(workflow.getCompany().getId(), workflow.getPeriod())
        );
    }

    private void closeWorkflow(PeriodWorkflow workflow, User actor, boolean automatic) {
        Instant now = Instant.now();
        if (workflow.getReviewedAt() == null) {
            workflow.setReviewedAt(now);
        }
        syncPortfolioImportReference(workflow, true);
        attachRecommendationSnapshot(workflow);
        workflow.setClosedAt(now);
        workflow.setUpdatedAt(now);
        workflow.setStatus(PeriodWorkflowStatus.CLOSED);
        if (workflow.getOwnerUser() == null && actor != null) {
            workflow.setOwnerUser(actor);
        }
        if (automatic) {
            workflow.setNotes("Cierre automatico completado al quedar informe, revision y cartera en regla.");
        }
    }

    private boolean shouldAutoCloseAfterReview(PeriodWorkflow workflow) {
        if (workflow == null || workflow.getClosedAt() != null) return false;
        if (workflow.getReviewedAt() == null) return false;
        boolean hasReadyReport = workflow.getReport() != null && workflow.getReport().getStatus() == ReportStatus.READY;
        if (!hasReadyReport && workflow.getStatus() != PeriodWorkflowStatus.REPORT_READY) return false;
        return portfolioStepAllowsClose(resolvePortfolioStep(workflow));
    }

    private void reopenForFreshInput(PeriodWorkflow workflow, ImportJob importJob) {
        if (workflow == null || importJob == null) return;
        ImportJob previousSource = workflow.getSourceImport();
        if (previousSource == null || previousSource.getId() == null || previousSource.getId().equals(importJob.getId())) {
            return;
        }
        workflow.setReport(null);
        workflow.setRecommendationSnapshot(null);
        workflow.setReviewedAt(null);
        workflow.setClosedAt(null);
        workflow.setNotes("El periodo se ha reabierto al entrar una nueva version de datos.");
    }

    private void refreshNotes(PeriodWorkflow workflow) {
        if (workflow == null) return;
        if (workflow.getStatus() == PeriodWorkflowStatus.CLOSED && workflow.getNotes() != null && !workflow.getNotes().isBlank()) {
            return;
        }
        PortfolioWorkflowStepDto portfolioStep = resolvePortfolioStep(workflow);
        boolean hasTribunal = portfolioStep.applicable();
        boolean hasRecommendation = workflow.getRecommendationSnapshot() != null;
        String next;
        switch (workflow.getStatus()) {
            case PENDING_DATA -> next = "Falta una carga valida del periodo para arrancar el cierre.";
            case INGESTING -> next = "La ingestion sigue en curso. Espera a que termine antes de revisar.";
            case EXCEPTIONS -> next = "Resuelve primero las excepciones de ingestion o calidad para desbloquear el cierre.";
            case READY_FOR_REVIEW -> next = "Valida checklist y senales del periodo antes de generar el informe.";
            case REVIEWED -> next = "El periodo esta revisado. Genera el informe para materializar el cierre.";
            case REPORT_GENERATING -> next = "El informe se esta generando. En cuanto quede listo, el workflow avanzara automaticamente.";
            case REPORT_READY -> next = hasRecommendation
                ? "El informe ya esta listo. Puedes cerrar el periodo; la recomendacion consultiva ya quedo enlazada."
                : "El informe ya esta listo. Solo queda registrar el cierre final del periodo.";
            case CLOSED -> next = hasTribunal
                ? switch (portfolioStep.status()) {
                    case LOADED -> "Periodo cerrado con entregable, recomendacion y estado de cartera disponible.";
                    case PENDING -> "Periodo cerrado con entregable y recomendacion, pero sigue pendiente cargar la cartera de Tribunal.";
                    case STALE -> "Periodo cerrado con entregable y recomendacion, pero conviene refrescar la cartera de Tribunal.";
                    case NOT_APPLICABLE -> "Periodo cerrado con entregable y recomendacion.";
                }
                : "Periodo cerrado con entregable y recomendacion. La cartera sigue siendo opcional para este cliente.";
            default -> next = "Workflow del periodo creado.";
        }
        workflow.setNotes(next);
    }

    private PortfolioWorkflowStepDto portfolioStep(boolean applicable,
                                                   PortfolioWorkflowStatus status,
                                                   String title,
                                                   String detail,
                                                   Instant updatedAt) {
        String badgeTone = switch (status) {
            case LOADED, NOT_APPLICABLE -> "ok";
            case STALE -> "warn";
            case PENDING -> "err";
        };
        String actionLabel = switch (status) {
            case PENDING -> "Cargar cartera";
            case STALE -> "Actualizar cartera";
            case LOADED -> "Abrir Tribunal";
            case NOT_APPLICABLE -> "Opcional";
        };
        String shortLabel = switch (status) {
            case PENDING -> "Pendiente";
            case STALE -> "Desactualizada";
            case LOADED -> "Cargada";
            case NOT_APPLICABLE -> "No aplica";
        };
        return new PortfolioWorkflowStepDto(applicable, status, title, detail, updatedAt, badgeTone, actionLabel, shortLabel);
    }

    private boolean usesTribunal(Company company, Optional<TribunalImport> latestImport) {
        if (latestImport.isPresent()) return true;
        Plan plan = company == null ? null : company.getPlan();
        return plan != null && plan.isAtLeast(Plan.GOLD);
    }

    private void syncPortfolioImportReference(PeriodWorkflow workflow, boolean freezeForClosure) {
        if (workflow == null || workflow.getCompany() == null || workflow.getCompany().getId() == null) return;
        Optional<TribunalImport> latestImport = tribunalImportRepository.findFirstByCompanyIdOrderByCreatedAtDesc(workflow.getCompany().getId());
        if (!usesTribunal(workflow.getCompany(), latestImport)) {
            workflow.setPortfolioImport(null);
            return;
        }
        if (latestImport.isEmpty()) {
            if (!freezeForClosure) {
                workflow.setPortfolioImport(null);
            }
            return;
        }
        TribunalImport current = workflow.getPortfolioImport();
        TribunalImport latest = latestImport.get();
        if (current == null) {
            workflow.setPortfolioImport(latest);
            return;
        }
        if (!freezeForClosure && latest.getCreatedAt() != null && (current.getCreatedAt() == null || latest.getCreatedAt().isAfter(current.getCreatedAt()))) {
            workflow.setPortfolioImport(latest);
        }
    }

    private OrchestrationPresentation reportReadyOrchestration(PeriodWorkflow workflow, PortfolioWorkflowStepDto portfolioStep) {
        if (shouldAutoCloseAfterReview(workflow)) {
            return new OrchestrationPresentation(
                "AUTO_CLOSE_READY",
                true,
                true,
                "Autocierre disponible",
                "Informe, revision y cartera ya estan en regla. Solo falta materializar el cierre oficial del periodo.",
                "Automatizar cierre"
            );
        }
        if (portfolioStep != null && portfolioStep.applicable() && portfolioStep.status() == PortfolioWorkflowStatus.PENDING) {
            return new OrchestrationPresentation(
                "WAITING_PORTFOLIO",
                false,
                false,
                "Pendiente de cartera",
                portfolioStep.detail() == null || portfolioStep.detail().isBlank()
                    ? "Antes de autocerrar, este periodo necesita una carga de cartera en Tribunal."
                    : portfolioStep.detail(),
                portfolioStep.actionLabel() == null || portfolioStep.actionLabel().isBlank()
                    ? "Cargar cartera"
                    : portfolioStep.actionLabel()
            );
        }
        if (portfolioStep != null && portfolioStep.applicable() && portfolioStep.status() == PortfolioWorkflowStatus.STALE) {
            return new OrchestrationPresentation(
                "WAITING_PORTFOLIO",
                false,
                false,
                "Cartera desactualizada",
                portfolioStep.detail() == null || portfolioStep.detail().isBlank()
                    ? "La cartera existe, pero ha quedado por detras del resto del cierre."
                    : portfolioStep.detail(),
                portfolioStep.actionLabel() == null || portfolioStep.actionLabel().isBlank()
                    ? "Actualizar cartera"
                    : portfolioStep.actionLabel()
            );
        }
        if (workflow.getReviewedAt() == null) {
            return new OrchestrationPresentation(
                "AVAILABLE",
                true,
                false,
                "Informe listo y revision pendiente",
                "El entregable ya existe. La automatizacion puede dejar la revision oficial y el snapshot alineados con el workflow.",
                "Automatizar cierre"
            );
        }
        return new OrchestrationPresentation(
            "AVAILABLE",
            true,
            false,
            "Workflow listo para remate",
            "El entregable ya esta materializado. Relanzar la automatizacion sirve para revalidar snapshot y cierre sobre el estado actual.",
            "Automatizar cierre"
        );
    }

    private static boolean portfolioStepAllowsClose(PortfolioWorkflowStepDto portfolioStep) {
        return portfolioStep == null
            || !portfolioStep.applicable()
            || portfolioStep.status() == PortfolioWorkflowStatus.LOADED
            || portfolioStep.status() == PortfolioWorkflowStatus.NOT_APPLICABLE;
    }

    private Instant latestWorkflowDataEvent(PeriodWorkflow workflow) {
        List<Instant> anchors = new ArrayList<>();
        if (workflow.getSourceImport() != null) {
            if (workflow.getSourceImport().getAppliedAt() != null) anchors.add(workflow.getSourceImport().getAppliedAt());
            if (workflow.getSourceImport().getUpdatedAt() != null) anchors.add(workflow.getSourceImport().getUpdatedAt());
            if (workflow.getSourceImport().getCreatedAt() != null) anchors.add(workflow.getSourceImport().getCreatedAt());
        }
        return anchors.stream().max(Comparator.naturalOrder()).orElse(null);
    }

    private static int exceptionCount(ImportJob importJob) {
        int warnings = Math.max(0, importJob.getWarningCount() == null ? 0 : importJob.getWarningCount());
        int errors = Math.max(0, importJob.getErrorCount() == null ? 0 : importJob.getErrorCount());
        int total = warnings + errors;
        if (total > 0) return total;
        if (importJob.getBlockingCode() != null && !importJob.getBlockingCode().isBlank()) return 1;
        return 0;
    }
}

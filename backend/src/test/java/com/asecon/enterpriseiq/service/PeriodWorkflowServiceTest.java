package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.ImportJob;
import com.asecon.enterpriseiq.model.ImportStatus;
import com.asecon.enterpriseiq.model.PeriodWorkflow;
import com.asecon.enterpriseiq.model.PeriodWorkflowStatus;
import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.model.PortfolioWorkflowStatus;
import com.asecon.enterpriseiq.model.Report;
import com.asecon.enterpriseiq.model.ReportFormat;
import com.asecon.enterpriseiq.model.ReportStatus;
import com.asecon.enterpriseiq.model.Role;
import com.asecon.enterpriseiq.model.TribunalImport;
import com.asecon.enterpriseiq.model.User;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.asecon.enterpriseiq.repo.ImportJobRepository;
import com.asecon.enterpriseiq.repo.PeriodWorkflowRepository;
import com.asecon.enterpriseiq.repo.ReportRepository;
import com.asecon.enterpriseiq.repo.TribunalImportRepository;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PeriodWorkflowServiceTest {
    private static final AtomicLong COMPANY_IDS = new AtomicLong(20_000);
    private static final AtomicLong USER_IDS = new AtomicLong(30_000);

    @Autowired
    private PeriodWorkflowService periodWorkflowService;

    @Autowired
    private PeriodCloseAutomationService periodCloseAutomationService;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private ImportJobRepository importJobRepository;

    @Autowired
    private ReportRepository reportRepository;

    @Autowired
    private PeriodWorkflowRepository periodWorkflowRepository;

    @Autowired
    private TribunalImportRepository tribunalImportRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void syncFromImport_movesWorkflowThroughExceptionsAndReadyForReview() {
        Company company = createCompany("Workflow import");

        ImportJob blocked = importJobRepository.save(importJob(company, "2026-06", ImportStatus.BLOCKED));
        blocked.setBlockingCode("OUTSIDE_PERIOD_ROWS");
        blocked.setBlockingReason("Fuera de periodo");
        blocked.setWarningCount(2);
        blocked.setErrorCount(1);
        blocked = importJobRepository.save(blocked);

        PeriodWorkflow workflow = periodWorkflowService.syncFromImport(blocked);
        assertThat(workflow.getStatus()).isEqualTo(PeriodWorkflowStatus.EXCEPTIONS);
        assertThat(workflow.getExceptionCount()).isEqualTo(3);
        assertThat(workflow.getSourceImport().getId()).isEqualTo(blocked.getId());

        ImportJob ok = importJobRepository.save(importJob(company, "2026-06", ImportStatus.OK));
        ok.setWarningCount(0);
        ok.setErrorCount(0);
        ok = importJobRepository.save(ok);

        workflow = periodWorkflowService.syncFromImport(ok);
        assertThat(workflow.getStatus()).isEqualTo(PeriodWorkflowStatus.READY_FOR_REVIEW);
        assertThat(workflow.getExceptionCount()).isEqualTo(0);
        assertThat(workflow.getBlockingCode()).isNull();
        assertThat(periodWorkflowService.presentOrchestration(workflow).runnable()).isTrue();
        assertThat(periodWorkflowService.presentOrchestration(workflow).status()).isEqualTo("AVAILABLE");
    }

    @Test
    void reviewAndClose_requireValidStateAndReadyReport() {
        Company company = createCompany("Workflow close");
        User owner = createUser("consultor.workflow@example.com");

        ImportJob ok = importJobRepository.save(importJob(company, "2026-07", ImportStatus.OK));
        periodWorkflowService.syncFromImport(ok);
        tribunalImportRepository.save(tribunalImport(company, "tribunal-2026-07.csv", Instant.now().plusSeconds(5)));

        Report earlyReport = reportRepository.save(report(company, "2026-07", ReportStatus.READY));
        periodWorkflowService.syncFromReport(earlyReport);

        assertThatThrownBy(() -> periodWorkflowService.markClosed(company.getId(), "2026-07", owner))
            .hasMessageContaining("No se puede cerrar el periodo sin revision oficial");

        PeriodWorkflow reviewed = periodWorkflowService.markReviewed(company.getId(), "2026-07", owner);
        assertThat(reviewed.getStatus()).isEqualTo(PeriodWorkflowStatus.CLOSED);
        assertThat(reviewed.getReviewedAt()).isNotNull();
        assertThat(reviewed.getOwnerUser().getId()).isEqualTo(owner.getId());

        Report nextVersion = report(company, "2026-07", ReportStatus.READY);
        nextVersion.setVersionNo(2);
        Report report = reportRepository.save(nextVersion);
        PeriodWorkflow reportReady = periodWorkflowService.syncFromReport(report);
        assertThat(reportReady.getStatus()).isEqualTo(PeriodWorkflowStatus.CLOSED);
        assertThat(reportReady.getRecommendationSnapshot()).isNotNull();

        PeriodWorkflow closed = periodWorkflowService.markClosed(company.getId(), "2026-07", owner);
        assertThat(closed.getStatus()).isEqualTo(PeriodWorkflowStatus.CLOSED);
        assertThat(closed.getClosedAt()).isNotNull();
        assertThat(closed.getRecommendationSnapshot()).isNotNull();
    }

    @Test
    void syncFromReport_autoClosesReviewedWorkflowAndAttachesRecommendation() {
        Company company = createCompany("Workflow autoclose");
        User owner = createUser("consultor.autoclose@example.com");

        ImportJob ok = importJobRepository.save(importJob(company, "2026-08", ImportStatus.OK));
        periodWorkflowService.syncFromImport(ok);
        tribunalImportRepository.save(tribunalImport(company, "tribunal-2026-08.csv", Instant.now().plusSeconds(5)));
        periodWorkflowService.markReviewed(company.getId(), "2026-08", owner);

        Report report = reportRepository.save(report(company, "2026-08", ReportStatus.READY));
        PeriodWorkflow closed = periodWorkflowService.syncFromReport(report);

        assertThat(closed.getStatus()).isEqualTo(PeriodWorkflowStatus.CLOSED);
        assertThat(closed.getClosedAt()).isNotNull();
        assertThat(closed.getRecommendationSnapshot()).isNotNull();
        assertThat(closed.getNotes()).contains("Cierre automatico");
    }

    @Test
    void syncFromReport_keepsReportReadyWhenPortfolioIsStillPending() {
        Company company = createCompany("Workflow autoclose blocked");
        User owner = createUser("consultor.autoblock@example.com");

        ImportJob ok = importJobRepository.save(importJob(company, "2026-09", ImportStatus.OK));
        periodWorkflowService.syncFromImport(ok);
        periodWorkflowService.markReviewed(company.getId(), "2026-09", owner);

        Report report = reportRepository.save(report(company, "2026-09", ReportStatus.READY));
        PeriodWorkflow workflow = periodWorkflowService.syncFromReport(report);

        assertThat(workflow.getStatus()).isEqualTo(PeriodWorkflowStatus.REPORT_READY);
        assertThat(workflow.getClosedAt()).isNull();
        assertThat(periodWorkflowService.resolvePortfolioStep(workflow).status()).isEqualTo(PortfolioWorkflowStatus.PENDING);
        assertThat(periodWorkflowService.presentOrchestration(workflow).status()).isEqualTo("WAITING_PORTFOLIO");
        assertThat(periodWorkflowService.presentOrchestration(workflow).runnable()).isFalse();
    }

    @Test
    void markClosed_rejectsWhenPortfolioStepIsNotLoaded() {
        Company company = createCompany("Workflow close blocked");
        User owner = createUser("consultor.closeblocked@example.com");

        ImportJob ok = importJobRepository.save(importJob(company, "2026-10", ImportStatus.OK));
        periodWorkflowService.syncFromImport(ok);
        periodWorkflowService.markReviewed(company.getId(), "2026-10", owner);
        Report report = reportRepository.save(report(company, "2026-10", ReportStatus.READY));
        periodWorkflowService.syncFromReport(report);

        assertThatThrownBy(() -> periodWorkflowService.markClosed(company.getId(), "2026-10", owner))
            .hasMessageContaining("No se puede cerrar el periodo mientras la cartera siga pendiente o desactualizada");
    }

    @Test
    void markReviewed_autoClosesWhenReportWasAlreadyReady() {
        Company company = createCompany("Workflow review closes", Plan.BRONZE);
        User owner = createUser("consultor.reviewcloses@example.com");

        ImportJob ok = importJobRepository.save(importJob(company, "2026-10", ImportStatus.OK));
        periodWorkflowService.syncFromImport(ok);
        Report report = reportRepository.save(report(company, "2026-10", ReportStatus.READY));
        periodWorkflowService.syncFromReport(report);

        PeriodWorkflow closed = periodWorkflowService.markReviewed(company.getId(), "2026-10", owner);

        assertThat(closed.getStatus()).isEqualTo(PeriodWorkflowStatus.CLOSED);
        assertThat(closed.getClosedAt()).isNotNull();
        assertThat(closed.getRecommendationSnapshot()).isNotNull();
    }

    @Test
    void syncFromImport_reopensClosedWorkflowAndClearsDownstreamArtifacts() {
        Company company = createCompany("Workflow reopen", Plan.BRONZE);
        User owner = createUser("consultor.reopen@example.com");

        ImportJob first = importJobRepository.save(importJob(company, "2026-11", ImportStatus.OK));
        periodWorkflowService.syncFromImport(first);
        periodWorkflowService.markReviewed(company.getId(), "2026-11", owner);
        Report report = reportRepository.save(report(company, "2026-11", ReportStatus.READY));
        PeriodWorkflow closed = periodWorkflowService.syncFromReport(report);
        assertThat(closed.getStatus()).isEqualTo(PeriodWorkflowStatus.CLOSED);

        ImportJob replacement = importJobRepository.save(importJob(company, "2026-11", ImportStatus.OK));
        PeriodWorkflow reopened = periodWorkflowService.syncFromImport(replacement);

        assertThat(reopened.getStatus()).isEqualTo(PeriodWorkflowStatus.READY_FOR_REVIEW);
        assertThat(reopened.getReviewedAt()).isNull();
        assertThat(reopened.getClosedAt()).isNull();
        assertThat(reopened.getReport()).isNull();
        assertThat(reopened.getRecommendationSnapshot()).isNull();
        assertThat(reopened.getNotes()).contains("Valida checklist");
    }

    @Test
    void orchestrate_advancesCleanPeriodFromImportToClosed() throws Exception {
        Company company = createCompany("Workflow orchestrated", Plan.BRONZE);
        ImportJob ok = importJobRepository.save(importJob(company, "2026-12", ImportStatus.OK));
        periodWorkflowService.syncFromImport(ok);

        PeriodWorkflow workflow = periodCloseAutomationService.orchestrate(company.getId(), "2026-12");

        assertThat(workflow.getStatus()).isEqualTo(PeriodWorkflowStatus.CLOSED);
        assertThat(workflow.getReviewedAt()).isNotNull();
        assertThat(workflow.getClosedAt()).isNotNull();
        assertThat(workflow.getReport()).isNotNull();
        assertThat(workflow.getRecommendationSnapshot()).isNotNull();
    }

    @Test
    void resolvePortfolioStep_marksPendingForGoldWithoutTribunalImport() {
        Company company = createCompany("Workflow portfolio pending");
        ImportJob ok = importJobRepository.save(importJob(company, "2026-09", ImportStatus.OK));
        PeriodWorkflow workflow = periodWorkflowService.syncFromImport(ok);

        var step = periodWorkflowService.resolvePortfolioStep(workflow);

        assertThat(step.applicable()).isTrue();
        assertThat(step.status()).isEqualTo(PortfolioWorkflowStatus.PENDING);
    }

    @Test
    void resolvePortfolioStep_marksStaleWhenTribunalLagsBehindCloseActivity() {
        Company company = createCompany("Workflow portfolio stale");
        tribunalImportRepository.save(tribunalImport(company, "tribunal-old.csv", Instant.parse("2026-10-01T10:00:00Z")));

        ImportJob ok = importJob(company, "2026-10", ImportStatus.OK);
        ok.setCreatedAt(Instant.parse("2026-10-02T10:00:00Z"));
        ok.setUpdatedAt(Instant.parse("2026-10-02T10:00:00Z"));
        ok = importJobRepository.save(ok);
        PeriodWorkflow workflow = periodWorkflowService.syncFromImport(ok);

        var step = periodWorkflowService.resolvePortfolioStep(workflow);

        assertThat(step.applicable()).isTrue();
        assertThat(step.status()).isEqualTo(PortfolioWorkflowStatus.STALE);
    }

    @Test
    void resolvePortfolioStep_marksLoadedWhenTribunalIsFresh() {
        Company company = createCompany("Workflow portfolio loaded");
        ImportJob ok = importJob(company, "2026-11", ImportStatus.OK);
        ok.setCreatedAt(Instant.parse("2026-11-02T10:00:00Z"));
        ok.setUpdatedAt(Instant.parse("2026-11-02T10:00:00Z"));
        ok = importJobRepository.save(ok);
        PeriodWorkflow workflow = periodWorkflowService.syncFromImport(ok);
        tribunalImportRepository.save(tribunalImport(company, "tribunal-fresh.csv", Instant.parse("2026-11-03T10:00:00Z")));

        var step = periodWorkflowService.resolvePortfolioStep(periodWorkflowRepository.findById(workflow.getId()).orElseThrow());

        assertThat(step.applicable()).isTrue();
        assertThat(step.status()).isEqualTo(PortfolioWorkflowStatus.LOADED);
        assertThat(step.detail()).isEqualTo("tribunal-fresh.csv");
    }

    @Test
    void resolvePortfolioStep_marksNotApplicableForBronzeWithoutHistory() {
        Company company = createCompany("Workflow portfolio bronze", Plan.BRONZE);
        ImportJob ok = importJobRepository.save(importJob(company, "2026-12", ImportStatus.OK));
        PeriodWorkflow workflow = periodWorkflowService.syncFromImport(ok);

        var step = periodWorkflowService.resolvePortfolioStep(workflow);

        assertThat(step.applicable()).isFalse();
        assertThat(step.status()).isEqualTo(PortfolioWorkflowStatus.NOT_APPLICABLE);
    }

    private Company createCompany(String suffix) {
        return createCompany(suffix, Plan.GOLD);
    }

    private Company createCompany(String suffix, Plan plan) {
        long id = COMPANY_IDS.incrementAndGet();
        jdbcTemplate.update(
            "insert into companies (id, name, plan) values (?, ?, ?)",
            id,
            "Test " + suffix,
            plan.name()
        );
        return companyRepository.findById(id).orElseThrow();
    }

    private User createUser(String email) {
        long id = USER_IDS.incrementAndGet();
        jdbcTemplate.update(
            "insert into users (id, email, password_hash, role, enabled) values (?, ?, ?, ?, ?)",
            id,
            email,
            "hash",
            Role.CONSULTOR.name(),
            true
        );
        User user = new User();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", id);
        user.setEmail(email);
        user.setPasswordHash("hash");
        user.setRole(Role.CONSULTOR);
        user.setEnabled(true);
        return user;
    }

    private static ImportJob importJob(Company company, String period, ImportStatus status) {
        ImportJob job = new ImportJob();
        job.setCompany(company);
        job.setPeriod(period);
        job.setStatus(status);
        job.setCreatedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        job.setRunAfter(Instant.now());
        job.setAttempts(0);
        job.setMaxAttempts(3);
        job.setOriginalFilename("test.csv");
        job.setVersionNo(1);
        return job;
    }

    private static Report report(Company company, String period, ReportStatus status) {
        Report report = new Report();
        report.setCompany(company);
        report.setPeriod(period);
        report.setFormat(ReportFormat.HTML);
        report.setStatus(status);
        report.setCreatedAt(Instant.now());
        report.setStorageRef("target/test-storage/reports/report-test.html");
        return report;
    }

    private static TribunalImport tribunalImport(Company company, String filename, Instant createdAt) {
        TribunalImport tribunalImport = new TribunalImport();
        tribunalImport.setCompany(company);
        tribunalImport.setFilename(filename);
        tribunalImport.setCreatedAt(createdAt);
        tribunalImport.setRowCount(10);
        tribunalImport.setWarningCount(0);
        tribunalImport.setErrorCount(0);
        return tribunalImport;
    }
}

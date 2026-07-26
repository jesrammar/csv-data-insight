package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.PortfolioWorkflowStepDto;
import com.asecon.enterpriseiq.dto.PortfolioCompanyOperationDto;
import com.asecon.enterpriseiq.dto.PortfolioMonthFlowDto;
import com.asecon.enterpriseiq.dto.PortfolioOperationsDto;
import com.asecon.enterpriseiq.dto.PortfolioOperationsSummaryDto;
import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.ImportJob;
import com.asecon.enterpriseiq.model.ImportStatus;
import com.asecon.enterpriseiq.model.PeriodWorkflow;
import com.asecon.enterpriseiq.model.Report;
import com.asecon.enterpriseiq.model.User;
import com.asecon.enterpriseiq.repo.ImportJobRepository;
import com.asecon.enterpriseiq.repo.PeriodWorkflowRepository;
import com.asecon.enterpriseiq.repo.ReportRepository;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PortfolioOperationsService {
    private final CompanyService companyService;
    private final ImportJobRepository importJobRepository;
    private final ReportRepository reportRepository;
    private final PeriodWorkflowRepository periodWorkflowRepository;
    private final PeriodWorkflowService periodWorkflowService;

    public PortfolioOperationsService(CompanyService companyService,
                                      ImportJobRepository importJobRepository,
                                      ReportRepository reportRepository,
                                      PeriodWorkflowRepository periodWorkflowRepository,
                                      PeriodWorkflowService periodWorkflowService) {
        this.companyService = companyService;
        this.importJobRepository = importJobRepository;
        this.reportRepository = reportRepository;
        this.periodWorkflowRepository = periodWorkflowRepository;
        this.periodWorkflowService = periodWorkflowService;
    }

    public PortfolioOperationsDto buildForUser(User user, int monthsBack) {
        List<Company> companies = user == null
            ? List.of()
            : companyService.findVisibleForUser(user);
        int window = Math.max(1, Math.min(monthsBack, 12));
        List<String> months = lastMonths(window);
        if (companies.isEmpty()) {
            return new PortfolioOperationsDto(
                months,
                new PortfolioOperationsSummaryDto(0, 0, 0, null, null, null, null),
                List.of()
            );
        }

        List<Long> companyIds = companies.stream().map(Company::getId).toList();
        List<ImportJob> jobs = importJobRepository.findByCompanyIdInAndPeriodInOrderByCompanyIdAscPeriodAscCreatedAtDesc(companyIds, months);
        List<Report> reports = reportRepository.findByCompanyIdInAndPeriodInOrderByCompanyIdAscPeriodAscCreatedAtDesc(companyIds, months);
        List<PeriodWorkflow> workflows = periodWorkflowRepository.findByCompanyIdInAndPeriodIn(companyIds, months);

        Map<String, ImportJob> latestImportByCompanyPeriod = new HashMap<>();
        for (ImportJob job : jobs) {
            String key = key(job.getCompany().getId(), job.getPeriod());
            latestImportByCompanyPeriod.putIfAbsent(key, job);
        }

        Map<String, Boolean> reportByCompanyPeriod = new HashMap<>();
        for (Report report : reports) {
            reportByCompanyPeriod.put(key(report.getCompany().getId(), report.getPeriod()), true);
        }
        Map<String, PeriodWorkflow> workflowByCompanyPeriod = new HashMap<>();
        for (PeriodWorkflow workflow : workflows) {
            workflowByCompanyPeriod.put(key(workflow.getCompany().getId(), workflow.getPeriod()), workflow);
        }

        List<PortfolioCompanyOperationDto> items = new ArrayList<>();
        for (Company company : companies) {
            List<PortfolioMonthFlowDto> monthItems = new ArrayList<>();
            for (String period : months) {
                ImportJob latestJob = latestImportByCompanyPeriod.get(key(company.getId(), period));
                boolean hasReport = reportByCompanyPeriod.containsKey(key(company.getId(), period));
                String importStatus = latestJob == null || latestJob.getStatus() == null ? null : latestJob.getStatus().name();
                boolean importOk = latestJob != null && (latestJob.getStatus() == ImportStatus.OK || latestJob.getStatus() == ImportStatus.WARNING);
                PortfolioWorkflowStepDto portfolioStep = resolvePortfolioStep(workflowByCompanyPeriod.get(key(company.getId(), period)));
                monthItems.add(new PortfolioMonthFlowDto(period, importStatus, importOk || hasReport, hasReport, portfolioStep));
            }

            int blockedCount = countWhere(monthItems, item -> in(item.getImportStatus(), "ERROR", "DEAD", "BLOCKED"));
            int warningCount = countWhere(monthItems, item -> in(item.getImportStatus(), "WARNING"));
            int readyCount = countWhere(monthItems, PortfolioMonthFlowDto::isHasReport);
            int pendingPdfCount = countWhere(monthItems, item -> item.isHasReading() && !item.isHasReport());
            int missingDataCount = countWhere(monthItems, item -> item.getImportStatus() == null && !item.isHasReading() && !item.isHasReport());
            boolean hasWorkInProgress = monthItems.stream().anyMatch(item ->
                item.isHasReading() || in(item.getImportStatus(), "RUNNING", "PENDING", "RETRY", "WARNING")
            );
            int priorityScore = blockedCount * 100 + warningCount * 30 + pendingPdfCount * 12 + missingDataCount * 4 - readyCount * 3;
            String statusLabel = blockedCount > 0
                ? "Atascada"
                : readyCount == months.size()
                    ? "Al d\u00eda"
                    : hasWorkInProgress ? "En curso" : "Pendiente";

            items.add(new PortfolioCompanyOperationDto(
                company.getId(),
                company.getName(),
                company.getPlan(),
                monthItems,
                blockedCount,
                warningCount,
                readyCount,
                pendingPdfCount,
                missingDataCount,
                priorityScore,
                statusLabel
            ));
        }

        items.sort(Comparator
            .comparingInt(PortfolioCompanyOperationDto::getPriorityScore).reversed()
            .thenComparingInt(PortfolioCompanyOperationDto::getBlockedCount).reversed()
            .thenComparingInt(PortfolioCompanyOperationDto::getPendingPdfCount).reversed()
            .thenComparing(PortfolioCompanyOperationDto::getCompanyName, String.CASE_INSENSITIVE_ORDER));

        int activeCompanies = countWhere(items, item ->
            item.getReadyCount() > 0 ||
            item.getBlockedCount() > 0 ||
            item.getItems().stream().anyMatch(entry -> entry.isHasReading() || entry.getImportStatus() != null)
        );
        int blockedCompanies = countWhere(items, item -> item.getBlockedCount() > 0);
        int readyCompanies = countWhere(items, item -> item.getReadyCount() == months.size());
        PortfolioCompanyOperationDto top = items.isEmpty() ? null : items.get(0);

        PortfolioOperationsSummaryDto summary = new PortfolioOperationsSummaryDto(
            activeCompanies,
            blockedCompanies,
            readyCompanies,
            top == null ? null : top.getCompanyId(),
            top == null ? null : top.getCompanyName(),
            top == null ? null : top.getBlockedCount(),
            top == null ? null : top.getPendingPdfCount()
        );

        return new PortfolioOperationsDto(months, summary, items);
    }

    private static List<String> lastMonths(int count) {
        List<String> months = new ArrayList<>(count);
        YearMonth current = YearMonth.now();
        for (int i = count - 1; i >= 0; i--) {
            months.add(current.minusMonths(i).toString());
        }
        return months;
    }

    private static String key(Long companyId, String period) {
        return companyId + "|" + String.valueOf(period).trim().toLowerCase(Locale.ROOT);
    }

    private PortfolioWorkflowStepDto resolvePortfolioStep(PeriodWorkflow workflow) {
        if (workflow == null) return null;
        return periodWorkflowService.resolvePortfolioStep(workflow);
    }

    private static boolean in(String value, String... candidates) {
        if (value == null) return false;
        for (String candidate : candidates) {
            if (candidate.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    private static <T> int countWhere(List<T> items, java.util.function.Predicate<T> predicate) {
        int count = 0;
        for (T item : items) {
            if (predicate.test(item)) count++;
        }
        return count;
    }
}



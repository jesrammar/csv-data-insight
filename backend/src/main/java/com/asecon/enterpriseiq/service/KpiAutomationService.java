package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.asecon.enterpriseiq.repo.TransactionRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KpiAutomationService {
    private final CompanyRepository companyRepository;
    private final TransactionRepository transactionRepository;
    private final KpiService kpiService;
    private final AlertService alertService;

    public KpiAutomationService(CompanyRepository companyRepository,
                                TransactionRepository transactionRepository,
                                KpiService kpiService,
                                AlertService alertService) {
        this.companyRepository = companyRepository;
        this.transactionRepository = transactionRepository;
        this.kpiService = kpiService;
        this.alertService = alertService;
    }

    @Transactional
    public void recomputeRecent(Long companyId, int monthsBack) {
        var company = companyRepository.findById(companyId).orElseThrow();
        List<String> periods = transactionRepository.findDistinctPeriodsDesc(companyId);
        if (periods == null || periods.isEmpty()) return;
        int take = Math.max(1, Math.min(monthsBack, periods.size()));
        for (int i = 0; i < take; i++) {
            String period = periods.get(i);
            var kpi = kpiService.recompute(company, period, (BigDecimal) null);
            alertService.evaluateThreshold(company, kpi);
        }
    }
}


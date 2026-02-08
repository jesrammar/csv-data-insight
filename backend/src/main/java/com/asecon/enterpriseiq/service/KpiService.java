package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.KpiMonthly;
import com.asecon.enterpriseiq.model.Transaction;
import com.asecon.enterpriseiq.repo.KpiMonthlyRepository;
import com.asecon.enterpriseiq.repo.TransactionRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KpiService {
    private final KpiMonthlyRepository kpiMonthlyRepository;
    private final TransactionRepository transactionRepository;

    public KpiService(KpiMonthlyRepository kpiMonthlyRepository, TransactionRepository transactionRepository) {
        this.kpiMonthlyRepository = kpiMonthlyRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public KpiMonthly recompute(Company company, String period, BigDecimal lastBalanceEnd) {
        List<Transaction> transactions = transactionRepository.findByCompanyIdAndPeriod(company.getId(), period);
        BigDecimal inflows = BigDecimal.ZERO;
        BigDecimal outflows = BigDecimal.ZERO;
        BigDecimal cumulative = BigDecimal.ZERO;

        for (Transaction tx : transactions) {
            BigDecimal amt = tx.getAmount();
            if (amt.compareTo(BigDecimal.ZERO) >= 0) {
                inflows = inflows.add(amt);
            } else {
                outflows = outflows.add(amt);
            }
            cumulative = cumulative.add(amt);
        }

        BigDecimal netFlow = inflows.add(outflows);
        BigDecimal endingBalance = lastBalanceEnd != null ? lastBalanceEnd : cumulative;

        kpiMonthlyRepository.deleteByCompanyIdAndPeriod(company.getId(), period);
        KpiMonthly kpi = new KpiMonthly();
        kpi.setCompany(company);
        kpi.setPeriod(period);
        kpi.setInflows(inflows);
        kpi.setOutflows(outflows);
        kpi.setNetFlow(netFlow);
        kpi.setEndingBalance(endingBalance);
        return kpiMonthlyRepository.save(kpi);
    }
}

package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.Alert;
import com.asecon.enterpriseiq.model.AlertRule;
import com.asecon.enterpriseiq.model.AlertType;
import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.KpiMonthly;
import com.asecon.enterpriseiq.repo.AlertRepository;
import com.asecon.enterpriseiq.repo.AlertRuleRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class AlertService {
    private final AlertRuleRepository alertRuleRepository;
    private final AlertRepository alertRepository;

    public AlertService(AlertRuleRepository alertRuleRepository, AlertRepository alertRepository) {
        this.alertRuleRepository = alertRuleRepository;
        this.alertRepository = alertRepository;
    }

    public void evaluateThreshold(Company company, KpiMonthly kpi) {
        Optional<AlertRule> ruleOpt = alertRuleRepository.findByCompanyId(company.getId());
        if (ruleOpt.isEmpty()) return;
        AlertRule rule = ruleOpt.get();
        if (kpi.getNetFlow().compareTo(rule.getNetFlowMinThreshold()) < 0) {
            Alert alert = new Alert();
            alert.setCompany(company);
            alert.setPeriod(kpi.getPeriod());
            alert.setType(AlertType.NET_FLOW_BELOW_THRESHOLD);
            alert.setMessage("Net flow below threshold: " + kpi.getNetFlow());
            alert.setCreatedAt(Instant.now());
            alertRepository.save(alert);
        }
    }
}

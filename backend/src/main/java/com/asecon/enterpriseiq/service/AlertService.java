package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.Alert;
import com.asecon.enterpriseiq.model.AlertRule;
import com.asecon.enterpriseiq.model.AlertType;
import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.KpiMonthly;
import com.asecon.enterpriseiq.repo.AlertRepository;
import com.asecon.enterpriseiq.repo.AlertRuleRepository;
import com.asecon.enterpriseiq.repo.KpiMonthlyRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class AlertService {
    private final AlertRuleRepository alertRuleRepository;
    private final AlertRepository alertRepository;
    private final KpiMonthlyRepository kpiMonthlyRepository;

    public AlertService(AlertRuleRepository alertRuleRepository,
                        AlertRepository alertRepository,
                        KpiMonthlyRepository kpiMonthlyRepository) {
        this.alertRuleRepository = alertRuleRepository;
        this.alertRepository = alertRepository;
        this.kpiMonthlyRepository = kpiMonthlyRepository;
    }

    public void evaluateMonthly(Company company, KpiMonthly kpi) {
        if (company == null || kpi == null || kpi.getPeriod() == null) return;
        alertRepository.deleteByCompanyIdAndPeriod(company.getId(), kpi.getPeriod());

        Optional<AlertRule> ruleOpt = alertRuleRepository.findByCompanyId(company.getId());
        if (ruleOpt.isPresent()) {
            AlertRule rule = ruleOpt.get();
            if (kpi.getNetFlow() != null && rule.getNetFlowMinThreshold() != null && kpi.getNetFlow().compareTo(rule.getNetFlowMinThreshold()) < 0) {
                save(company, kpi.getPeriod(), AlertType.NET_FLOW_BELOW_THRESHOLD,
                    "Flujo neto mensual por debajo del umbral (" + fmtMoney(kpi.getNetFlow()) + " < " + fmtMoney(rule.getNetFlowMinThreshold()) + ").");
            }
        }

        // Alerta de saldo bajo (heurística demo): saldo final negativo o menor que ~10% de los pagos del mes.
        if (kpi.getEndingBalance() != null && kpi.getOutflows() != null) {
            boolean negative = kpi.getEndingBalance().compareTo(BigDecimal.ZERO) < 0;
            BigDecimal minOk = kpi.getOutflows().abs().multiply(new BigDecimal("0.10"));
            boolean lowVsOutflows = kpi.getEndingBalance().compareTo(minOk) < 0;
            if (negative || lowVsOutflows) {
                save(company, kpi.getPeriod(), AlertType.ENDING_BALANCE_LOW,
                    "Saldo final bajo: " + fmtMoney(kpi.getEndingBalance()) + ". Revisa tesorería (cobros/pagos próximos).");
            }
        }

        // Picos / caídas vs media móvil (últimos 6 meses excluyendo el actual)
        var avg = computeAverages(company.getId(), kpi.getPeriod(), 6);
        if (avg != null) {
            if (kpi.getOutflows() != null && avg.avgOutflows != null
                && avg.avgOutflows.compareTo(BigDecimal.ZERO) > 0
                && kpi.getOutflows().compareTo(avg.avgOutflows.multiply(new BigDecimal("1.50"))) > 0) {
                save(company, kpi.getPeriod(), AlertType.OUTFLOWS_SPIKE,
                    "Pico de pagos: " + fmtMoney(kpi.getOutflows()) + " vs media 6m " + fmtMoney(avg.avgOutflows) + ". Posible gasto puntual o acumulación.");
            }
            if (kpi.getInflows() != null && avg.avgInflows != null
                && avg.avgInflows.compareTo(BigDecimal.ZERO) > 0
                && kpi.getInflows().compareTo(avg.avgInflows.multiply(new BigDecimal("0.60"))) < 0) {
                save(company, kpi.getPeriod(), AlertType.INFLOWS_DROP,
                    "Caída de cobros: " + fmtMoney(kpi.getInflows()) + " vs media 6m " + fmtMoney(avg.avgInflows) + ". Revisa facturación/cobros.");
            }
        }
    }

    private void save(Company company, String period, AlertType type, String message) {
        Alert alert = new Alert();
        alert.setCompany(company);
        alert.setPeriod(period);
        alert.setType(type);
        alert.setMessage(message == null ? "" : message);
        alert.setCreatedAt(Instant.now());
        alertRepository.save(alert);
    }

    private record Averages(BigDecimal avgInflows, BigDecimal avgOutflows) {}

    private Averages computeAverages(Long companyId, String period, int monthsBack) {
        try {
            YearMonth ym = YearMonth.parse(period);
            String to = ym.minusMonths(1).toString();
            String from = ym.minusMonths(monthsBack).toString();
            List<KpiMonthly> rows = kpiMonthlyRepository.findByCompanyIdAndPeriodBetweenOrderByPeriodAsc(companyId, from, to);
            if (rows == null || rows.isEmpty()) return null;
            BigDecimal sumIn = BigDecimal.ZERO;
            BigDecimal sumOut = BigDecimal.ZERO;
            int nIn = 0;
            int nOut = 0;
            for (KpiMonthly k : rows) {
                if (k.getInflows() != null) { sumIn = sumIn.add(k.getInflows()); nIn++; }
                if (k.getOutflows() != null) { sumOut = sumOut.add(k.getOutflows()); nOut++; }
            }
            BigDecimal avgIn = nIn == 0 ? null : sumIn.divide(BigDecimal.valueOf(nIn), 2, RoundingMode.HALF_UP);
            BigDecimal avgOut = nOut == 0 ? null : sumOut.divide(BigDecimal.valueOf(nOut), 2, RoundingMode.HALF_UP);
            return new Averages(avgIn, avgOut);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String fmtMoney(BigDecimal v) {
        if (v == null) return "—";
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString() + " €";
    }
}

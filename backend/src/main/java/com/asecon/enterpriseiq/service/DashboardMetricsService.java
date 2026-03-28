package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.DashboardInsightDto;
import com.asecon.enterpriseiq.dto.DashboardMetricDto;
import com.asecon.enterpriseiq.model.KpiMonthly;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DashboardMetricsService {
    public record Result(List<DashboardMetricDto> metrics, List<DashboardInsightDto> insights) {}

    public Result compute(List<KpiMonthly> kpis) {
        if (kpis == null || kpis.isEmpty()) {
            return new Result(List.of(), List.of());
        }

        int n = kpis.size();
        double[] inflows = new double[n];
        double[] outflows = new double[n];
        double[] netflows = new double[n];
        double[] ending = new double[n];
        String[] periods = new String[n];

        for (int i = 0; i < n; i++) {
            KpiMonthly k = kpis.get(i);
            inflows[i] = k.getInflows().doubleValue();
            outflows[i] = k.getOutflows().doubleValue();
            netflows[i] = k.getNetFlow().doubleValue();
            ending[i] = k.getEndingBalance().doubleValue();
            periods[i] = k.getPeriod();
        }

        double avgInflows = mean(inflows);
        double avgOutflows = mean(outflows);
        double avgNetFlow = mean(netflows);
        double netStd = stddev(netflows);
        double trendSlope = slope(netflows);
        double latestEnding = ending[n - 1];
        int positiveMonths = countPositive(netflows);
        double positivePct = n == 0 ? 0 : (double) positiveMonths / n;

        int bestIdx = argMax(netflows);
        int worstIdx = argMin(netflows);
        int peakInflowsIdx = argMax(inflows);

        Double runwayMonths = null;
        if (avgOutflows > 0) {
            runwayMonths = latestEnding / avgOutflows;
        }

        BreakEven breakEven = computeBreakEven(ending, periods, avgNetFlow);

        List<DashboardMetricDto> metrics = new ArrayList<>();
        metrics.add(metric("avg_inflows", "Ingresos medios", fmt(avgInflows), "BRONZE"));
        metrics.add(metric("avg_outflows", "Gastos medios", fmt(avgOutflows), "BRONZE"));
        metrics.add(metric("avg_netflow", "Flujo neto medio", fmt(avgNetFlow), "BRONZE"));
        metrics.add(metric("latest_balance", "Saldo final último mes", fmt(latestEnding), "BRONZE"));

        metrics.add(metric("netflow_volatility", "Volatilidad flujo neto (std)", fmt(netStd), "GOLD"));
        metrics.add(metric("netflow_trend", "Tendencia flujo neto (pendiente)", fmt(trendSlope), "GOLD"));
        metrics.add(metric("burn_rate", "Burn rate mensual", fmt(avgOutflows), "GOLD"));
        metrics.add(metric("runway", "Runway estimado (meses)", runwayMonths == null ? "-" : fmt(runwayMonths), "GOLD"));
        metrics.add(metric("break_even", "Punto muerto de caja", breakEven.label, "GOLD"));

        metrics.add(metric("best_month", "Mejor mes (flujo neto)", formatPeriodValue(periods[bestIdx], netflows[bestIdx]), "PLATINUM"));
        metrics.add(metric("worst_month", "Peor mes (flujo neto)", formatPeriodValue(periods[worstIdx], netflows[worstIdx]), "PLATINUM"));
        metrics.add(metric("positive_pct", "% meses positivos", formatPercent(positivePct), "PLATINUM"));
        metrics.add(metric("forecast_next", "Forecast próximo mes (flujo neto)", fmt(forecastNext(netflows, trendSlope)), "PLATINUM"));
        metrics.add(metric("seasonality_peak", "Estacionalidad pico (ingresos)", periods[peakInflowsIdx], "PLATINUM"));

        List<DashboardInsightDto> insights = buildInsights(avgNetFlow, netStd, trendSlope, runwayMonths, positivePct, breakEven, n);

        return new Result(metrics, insights);
    }

    private static List<DashboardInsightDto> buildInsights(double avgNetFlow,
                                                          double netStd,
                                                          double trendSlope,
                                                          Double runwayMonths,
                                                          double positivePct,
                                                          BreakEven breakEven,
                                                          int months) {
        List<DashboardInsightDto> out = new ArrayList<>();

        if (avgNetFlow < 0) {
            out.add(insight("Flujo neto negativo",
                "El promedio mensual es negativo; revisar estructura de gastos.",
                "warning",
                "GOLD"));
        } else {
            out.add(insight("Flujo neto saludable",
                "El promedio mensual es positivo; mantener disciplina de cobros.",
                "info",
                "GOLD"));
        }

        if (trendSlope < 0) {
            out.add(insight("Tendencia a la baja",
                "La pendiente del flujo neto es descendente.",
                "warning",
                "PLATINUM"));
        }

        if (runwayMonths != null && runwayMonths < 3) {
            out.add(insight("Runway corto",
                "El saldo actual cubre menos de 3 meses de gastos medios.",
                "critical",
                "PLATINUM"));
        }

        if (positivePct < 0.5) {
            out.add(insight("Meses positivos bajos",
                "Menos del 50% de los meses tienen flujo neto positivo.",
                "warning",
                "PLATINUM"));
        }

        if (!breakEven.reached && avgNetFlow <= 0) {
            out.add(insight("Punto muerto no alcanzable",
                "Con la tendencia actual no se alcanza punto muerto de caja.",
                "critical",
                "PLATINUM"));
        }

        double cv = avgNetFlow == 0 ? 0 : Math.abs(netStd / avgNetFlow);
        if (cv > 1.2 && months >= 4) {
            out.add(insight("Alta volatilidad",
                "La variabilidad del flujo neto es elevada; revisar estacionalidad.",
                "info",
                "PLATINUM"));
        }

        out.sort(Comparator.comparing(DashboardInsightDto::getTier));
        return out;
    }

    private static DashboardMetricDto metric(String key, String label, String value, String tier) {
        return new DashboardMetricDto(key, label, value, tier);
    }

    private static DashboardInsightDto insight(String title, String detail, String severity, String tier) {
        return new DashboardInsightDto(title, detail, severity, tier);
    }

    private static double mean(double[] values) {
        if (values.length == 0) return 0;
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.length;
    }

    private static double stddev(double[] values) {
        if (values.length <= 1) return 0;
        double avg = mean(values);
        double sum = 0;
        for (double v : values) {
            double d = v - avg;
            sum += d * d;
        }
        return Math.sqrt(sum / (values.length - 1));
    }

    private static double slope(double[] values) {
        int n = values.length;
        if (n <= 1) return 0;
        double avgX = (n - 1) / 2.0;
        double avgY = mean(values);
        double num = 0;
        double den = 0;
        for (int i = 0; i < n; i++) {
            double dx = i - avgX;
            num += dx * (values[i] - avgY);
            den += dx * dx;
        }
        return den == 0 ? 0 : num / den;
    }

    private static double forecastNext(double[] values, double slope) {
        if (values.length == 0) return 0;
        double last = values[values.length - 1];
        return last + slope;
    }

    private static int countPositive(double[] values) {
        int count = 0;
        for (double v : values) {
            if (v > 0) count++;
        }
        return count;
    }

    private static int argMax(double[] values) {
        int idx = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[idx]) idx = i;
        }
        return idx;
    }

    private static int argMin(double[] values) {
        int idx = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] < values[idx]) idx = i;
        }
        return idx;
    }

    private static String fmt(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toString();
    }

    private static String formatPercent(double value) {
        double pct = value * 100.0;
        return BigDecimal.valueOf(pct).setScale(1, RoundingMode.HALF_UP) + "%";
    }

    private static String formatPeriodValue(String period, double value) {
        return period + " (" + fmt(value) + ")";
    }

    private static BreakEven computeBreakEven(double[] ending, String[] periods, double avgNetFlow) {
        for (int i = 0; i < ending.length; i++) {
            if (ending[i] >= 0) {
                return new BreakEven(true, "Alcanzado en " + periods[i] + " (saldo " + fmt(ending[i]) + ")");
            }
        }
        double latest = ending[ending.length - 1];
        if (avgNetFlow > 0 && latest < 0) {
            int months = (int) Math.ceil(Math.abs(latest) / avgNetFlow);
            return new BreakEven(false, "No alcanzado; estimado en " + months + " meses");
        }
        return new BreakEven(false, "No alcanzado; tendencia negativa");
    }

    private record BreakEven(boolean reached, String label) {}
}

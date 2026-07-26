package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.BudgetComparisonMonthDto;
import com.asecon.enterpriseiq.dto.BudgetComparisonSummaryDto;
import com.asecon.enterpriseiq.dto.BudgetLongInsightsDto;
import com.asecon.enterpriseiq.dto.BudgetSummaryDto;
import com.asecon.enterpriseiq.dto.BudgetWorkflowDto;
import com.asecon.enterpriseiq.dto.CashflowMonthDto;
import com.asecon.enterpriseiq.dto.CashflowSummaryDto;
import com.asecon.enterpriseiq.dto.UniversalImportAnalysisDto;
import com.asecon.enterpriseiq.dto.UniversalIntakeDiagnosisDto;
import com.asecon.enterpriseiq.model.KpiMonthly;
import com.asecon.enterpriseiq.model.UniversalImport;
import com.asecon.enterpriseiq.repo.KpiMonthlyRepository;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Year;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BudgetWorkflowService {
    private final BudgetService budgetService;
    private final UniversalImportFileService universalImportFileService;
    private final KpiMonthlyRepository kpiMonthlyRepository;
    private final ObjectMapper objectMapper;

    public BudgetWorkflowService(BudgetService budgetService,
                                 UniversalImportFileService universalImportFileService,
                                 KpiMonthlyRepository kpiMonthlyRepository,
                                 ObjectMapper objectMapper) {
        this.budgetService = budgetService;
        this.universalImportFileService = universalImportFileService;
        this.kpiMonthlyRepository = kpiMonthlyRepository;
        this.objectMapper = objectMapper;
    }

    public BudgetWorkflowDto getWorkflow(Long companyId) {
        List<UniversalImport> latestImports = universalImportFileService.latestList(companyId, 2);
        List<UniversalImport> latestAnnualImports = universalImportFileService.latestAnnualBudgetList(companyId, 2);
        UniversalImport latestImport = latestImports.isEmpty() ? null : latestImports.get(0);
        UniversalImport previousImport = latestImports.size() > 1 ? latestImports.get(1) : null;
        UniversalImport latestAnnualImport = latestAnnualImports.isEmpty() ? null : latestAnnualImports.get(0);
        UniversalImport previousAnnualImport = latestAnnualImports.size() > 1 ? latestAnnualImports.get(1) : null;

        BudgetSummaryDto summary = null;
        CashflowSummaryDto cashflow = null;
        BudgetLongInsightsDto insights = null;
        String summaryError = null;
        String cashflowError = null;
        String insightsError = null;

        try {
            summary = budgetService.latestBudget(companyId);
        } catch (ResponseStatusException ex) {
            summaryError = reasonOf(ex, "No se pudo validar la estructura anual.");
        }

        try {
            cashflow = budgetService.latestCashflow(companyId);
        } catch (ResponseStatusException ex) {
            cashflowError = reasonOf(ex, "No se pudo leer la tesorería prevista.");
        }

        try {
            insights = budgetService.latestBudgetLongInsights(companyId);
        } catch (ResponseStatusException ex) {
            insightsError = reasonOf(ex, "No se pudo generar la lectura anual.");
        }

        BudgetService.LongBudgetSource annualSource = null;
        try {
            annualSource = budgetService.latestLongBudgetSource(companyId);
        } catch (Exception ignored) {
            annualSource = null;
        }

        BudgetComparisonBundle comparison = cashflow == null ? null : buildComparison(companyId, cashflow, annualSource);

        UniversalImportAnalysisDto latestUploadAnalysis = parseAnalysis(latestImport);
        UniversalIntakeDiagnosisDto latestUploadDiagnosis = latestUploadAnalysis == null ? null : latestUploadAnalysis.intakeDiagnosis();
        boolean sourcePresent = latestAnnualImport != null || summary != null || cashflow != null || insights != null || annualSource != null;
        boolean wrongSource = !sourcePresent
            && latestImport != null
            && latestUploadDiagnosis != null
            && !"ANNUAL_BUDGET".equalsIgnoreCase(nonBlank(latestUploadDiagnosis.kind(), ""));
        boolean structureValidated = summary != null;
        boolean annualInsightsReady = insights != null;
        boolean plannedCashflowReady = cashflow != null;
        boolean comparisonReady = comparison != null && comparison.summary().commonMonths() != null && comparison.summary().commonMonths() > 0;
        int plannedMonths = cashflow != null
            ? cashflow.months().size()
            : summary != null ? summary.months().size() : 0;
        int actualMonths = comparison == null || comparison.summary().actualMonths() == null ? 0 : comparison.summary().actualMonths();
        UniversalImport officialSourceImport = latestAnnualImport != null ? latestAnnualImport : latestImport;
        UniversalImportAnalysisDto sourceAnalysis = parseAnalysis(officialSourceImport);
        Integer sourceSheetIndex = sourceAnalysis == null || sourceAnalysis.xlsx() == null ? null : sourceAnalysis.xlsx().sheetIndex();
        Integer sourceHeaderRow = sourceAnalysis == null || sourceAnalysis.xlsx() == null ? null : sourceAnalysis.xlsx().headerRow1Based();
        AttemptTrend sourceTrend = compareAttempts(companyId, latestAnnualImport != null ? latestAnnualImport : latestImport, latestAnnualImport != null ? previousAnnualImport : previousImport);

        String status;
        String title;
        String detail;
        String badgeTone;
        String nextAction;

        if (!sourcePresent) {
            if (wrongSource) {
                status = "WRONG_SOURCE";
                title = "El ultimo fichero no parece un plan anual";
                detail = nonBlank(
                    latestUploadDiagnosis == null ? null : latestUploadDiagnosis.detail(),
                    "La ultima carga encaja mejor en otro flujo. Abrela en Universal antes de usarla como presupuesto."
                );
                badgeTone = "warn";
                nextAction = "Abrir analisis tecnico";
            } else {
            status = "MISSING_SOURCE";
            title = "Presupuesto anual pendiente";
            detail = "Todavía no hay un XLSX anual cargado en Universal para esta empresa.";
            badgeTone = "warn";
            nextAction = "Subir presupuesto";
            }
        } else if (!structureValidated) {
            status = "NEEDS_VALIDATION";
            title = "Estructura anual pendiente de validar";
            detail = nonBlank(summaryError, "Hay un fichero cargado, pero no se ha podido convertir en una lectura anual fiable.");
            badgeTone = "err";
            nextAction = "Corregir carga";
        } else if (!annualInsightsReady) {
            status = "READY_FOR_READING";
            title = "Presupuesto detectado, falta lectura anual";
            detail = nonBlank(insightsError, "La estructura mínima ya existe, pero todavía no hay lectura anual consultiva preparada.");
            badgeTone = "warn";
            nextAction = "Generar lectura";
        } else if (!plannedCashflowReady) {
            status = "ANNUAL_READING_READY";
            title = "Lectura anual lista";
            detail = nonBlank(cashflowError, "Ya existe lectura anual, pero la tesorería prevista no está disponible para comparar contra el real.");
            badgeTone = "warn";
            nextAction = "Revisar tesorería";
        } else if (!comparisonReady) {
            status = "WAITING_ACTUALS";
            title = "Plan anual listo, falta contraste con el real";
            detail = actualMonths > 0
                ? "Hay meses reales cargados, pero todavía no se solapan suficientemente con el presupuesto para una comparación útil."
                : "El presupuesto anual ya está leído. En cuanto entren KPIs reales del ejercicio, se abrirá la comparativa.";
            badgeTone = "warn";
            nextAction = "Cargar meses reales";
        } else {
            status = "COMPARISON_READY";
            title = "Comparativa real vs presupuesto activa";
            detail = "El flujo anual ya puede leerse como planificación más contraste contra los meses reales disponibles.";
            badgeTone = "ok";
            nextAction = "Descargar informe anual";
        }

        return new BudgetWorkflowDto(
            companyId,
            status,
            title,
            detail,
            badgeTone,
            nextAction,
            officialSourceImport == null ? null : officialSourceImport.getFilename(),
            officialSourceImport == null ? null : officialSourceImport.getCreatedAt(),
            sourceSheetIndex,
            sourceHeaderRow,
            sourceTrend.trend(),
            sourceTrend.title(),
            sourceTrend.detail(),
            sourcePresent,
            structureValidated,
            annualInsightsReady,
            plannedCashflowReady,
            comparisonReady,
            plannedMonths,
            actualMonths,
            comparison == null ? null : comparison.summary(),
            comparison == null ? List.of() : comparison.months()
        );
    }

    private UniversalImportAnalysisDto parseAnalysis(UniversalImport latestImport) {
        if (latestImport == null || latestImport.getAnalysisJson() == null || latestImport.getAnalysisJson().isBlank()) return null;
        try {
            return objectMapper.readValue(latestImport.getAnalysisJson(), UniversalImportAnalysisDto.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private AttemptTrend compareAttempts(Long companyId, UniversalImport latestImport, UniversalImport previousImport) {
        if (latestImport == null) {
            return new AttemptTrend(null, null, null);
        }
        if (previousImport == null) {
            return new AttemptTrend("FIRST", "Primer intento registrado", "Todavía no hay un intento anterior con el que comparar esta lectura anual.");
        }

        AnnualAttemptProbe latest = probeAttempt(companyId, latestImport);
        AnnualAttemptProbe previous = probeAttempt(companyId, previousImport);

        if (latest.score() > previous.score()) {
            return new AttemptTrend(
                "IMPROVED",
                "Mejoró frente al intento anterior",
                latest.monthCount() >= 6 && previous.monthCount() < 6
                    ? "Ahora sí detecta meses anuales; antes no."
                    : latest.longReady() && !previous.longReady()
                        ? "La estructura anual ahora es más interpretable que en el intento anterior."
                        : "Este intento deja una base anual más utilizable que el anterior."
            );
        }
        if (latest.score() < previous.score()) {
            return new AttemptTrend(
                "WORSE",
                "Empeoró frente al intento anterior",
                previous.monthCount() >= 6 && latest.monthCount() < 6
                    ? "Antes sí detectaba meses anuales y ahora no."
                    : previous.longReady() && !latest.longReady()
                        ? "El intento anterior dejaba una estructura anual más interpretable."
                        : "La estructura detectada ahora es más débil que en el intento anterior."
            );
        }
        return new AttemptTrend(
            "SAME",
            "Sin mejora clara frente al intento anterior",
            latest.monthCount() == previous.monthCount()
                ? "La lectura anual detectada sigue prácticamente igual que en el intento anterior."
                : "Hubo cambios, pero no suficientes como para mejorar la lectura anual oficial."
        );
    }

    private AnnualAttemptProbe probeAttempt(Long companyId, UniversalImport imp) {
        if (imp == null) return new AnnualAttemptProbe(0, 0, false);
        try {
            byte[] bytes = universalImportFileService.normalizedCsv(companyId, imp.getId());
            if (bytes == null || bytes.length == 0) return new AnnualAttemptProbe(0, 0, false);

            var longResult = BudgetLongNormalizer.normalizeToLongCsv(bytes, 5_000, 5);
            int monthCount = longResult.monthKeys() == null ? 0 : longResult.monthKeys().size();
            boolean longReady = longResult.longCsvBytes() != null
                && longResult.longCsvBytes().length > 0
                && longResult.labelHeader() != null
                && monthCount >= 6;

            int score = longReady ? 3 : monthCount >= 6 ? 2 : monthCount > 0 ? 1 : 0;

            return new AnnualAttemptProbe(score, monthCount, longReady);
        } catch (Exception ignored) {
            return new AnnualAttemptProbe(0, 0, false);
        }
    }

    private record AnnualAttemptProbe(int score, int monthCount, boolean longReady) {}
    private record AttemptTrend(String trend, String title, String detail) {}

    private BudgetComparisonBundle buildComparison(Long companyId, CashflowSummaryDto cashflow) {
        int comparisonYear = kpiMonthlyRepository.findFirstByCompanyIdOrderByPeriodDesc(companyId)
            .map(KpiMonthly::getPeriod)
            .map(BudgetWorkflowService::parsePeriod)
            .map(YearMonth::getYear)
            .orElse(Year.now().getValue());

        String from = comparisonYear + "-01";
        String to = comparisonYear + "-12";
        List<KpiMonthly> actuals = kpiMonthlyRepository.findByCompanyIdAndPeriodBetweenOrderByPeriodAsc(companyId, from, to);
        Map<String, KpiMonthly> actualByPeriod = new LinkedHashMap<>();
        for (KpiMonthly item : actuals) {
            if (item != null && item.getPeriod() != null) {
                actualByPeriod.put(item.getPeriod(), item);
            }
        }

        List<BudgetComparisonMonthDto> months = new ArrayList<>();
        BigDecimal plannedInflowYtd = BigDecimal.ZERO;
        BigDecimal actualInflowYtd = BigDecimal.ZERO;
        BigDecimal plannedOutflowYtd = BigDecimal.ZERO;
        BigDecimal actualOutflowYtd = BigDecimal.ZERO;
        BigDecimal plannedNetYtd = BigDecimal.ZERO;
        BigDecimal actualNetYtd = BigDecimal.ZERO;
        BigDecimal plannedEndingBalanceLatest = null;
        BigDecimal actualEndingBalanceLatest = null;
        String latestComparedPeriod = null;
        String strongestPositiveMonth = null;
        BigDecimal strongestPositiveVariance = null;
        String strongestNegativeMonth = null;
        BigDecimal strongestNegativeVariance = null;
        int actualMonths = 0;
        int commonMonths = 0;

        for (CashflowMonthDto planned : cashflow.months()) {
            int monthNumber = toMonthNumber(planned.monthKey());
            String period = monthNumber > 0 ? comparisonYear + "-" + String.format(Locale.ROOT, "%02d", monthNumber) : null;
            KpiMonthly actual = period == null ? null : actualByPeriod.get(period);
            boolean hasActual = actual != null;
            if (hasActual) {
                actualMonths++;
                commonMonths++;
            }

            BigDecimal plannedInflow = scale(planned.inflow());
            BigDecimal plannedOutflow = scale(planned.outflow());
            BigDecimal plannedNet = scale(planned.net());
            BigDecimal plannedEndingBalance = scale(planned.endingBalance());
            BigDecimal actualInflow = scale(actual == null ? null : actual.getInflows());
            BigDecimal actualOutflow = scale(actual == null ? null : actual.getOutflows());
            BigDecimal actualNet = scale(actual == null ? null : actual.getNetFlow());
            BigDecimal actualEndingBalance = scale(actual == null ? null : actual.getEndingBalance());

            BigDecimal inflowVariance = variance(actualInflow, plannedInflow);
            BigDecimal outflowVariance = variance(actualOutflow, plannedOutflow);
            BigDecimal netVariance = variance(actualNet, plannedNet);
            BigDecimal endingBalanceVariance = variance(actualEndingBalance, plannedEndingBalance);

            if (hasActual) {
                plannedInflowYtd = plannedInflowYtd.add(zero(plannedInflow));
                actualInflowYtd = actualInflowYtd.add(zero(actualInflow));
                plannedOutflowYtd = plannedOutflowYtd.add(zero(plannedOutflow));
                actualOutflowYtd = actualOutflowYtd.add(zero(actualOutflow));
                plannedNetYtd = plannedNetYtd.add(zero(plannedNet));
                actualNetYtd = actualNetYtd.add(zero(actualNet));
                plannedEndingBalanceLatest = plannedEndingBalance;
                actualEndingBalanceLatest = actualEndingBalance;
                latestComparedPeriod = period;

                if (netVariance != null && (strongestPositiveVariance == null || netVariance.compareTo(strongestPositiveVariance) > 0)) {
                    strongestPositiveVariance = netVariance;
                    strongestPositiveMonth = planned.label();
                }
                if (netVariance != null && (strongestNegativeVariance == null || netVariance.compareTo(strongestNegativeVariance) < 0)) {
                    strongestNegativeVariance = netVariance;
                    strongestNegativeMonth = planned.label();
                }
            }

            months.add(new BudgetComparisonMonthDto(
                planned.monthKey(),
                planned.label(),
                period,
                hasActual,
                plannedInflow,
                actualInflow,
                inflowVariance,
                plannedOutflow,
                actualOutflow,
                outflowVariance,
                plannedNet,
                actualNet,
                netVariance,
                plannedEndingBalance,
                actualEndingBalance,
                endingBalanceVariance
            ));
        }

        BudgetComparisonSummaryDto summary = new BudgetComparisonSummaryDto(
            comparisonYear,
            cashflow.months().size(),
            actualMonths,
            commonMonths,
            "Comparativa alineada al último año real detectado en KPIs: " + comparisonYear + ". Si el presupuesto pertenece a otro ejercicio, carga el real del mismo año o ajusta el periodo de trabajo.",
            latestComparedPeriod,
            scale(plannedInflowYtd),
            scale(actualInflowYtd),
            variance(scale(actualInflowYtd), scale(plannedInflowYtd)),
            scale(plannedOutflowYtd),
            scale(actualOutflowYtd),
            variance(scale(actualOutflowYtd), scale(plannedOutflowYtd)),
            scale(plannedNetYtd),
            scale(actualNetYtd),
            variance(scale(actualNetYtd), scale(plannedNetYtd)),
            scale(plannedEndingBalanceLatest),
            scale(actualEndingBalanceLatest),
            variance(scale(actualEndingBalanceLatest), scale(plannedEndingBalanceLatest)),
            strongestPositiveMonth,
            scale(strongestPositiveVariance),
            strongestNegativeMonth,
            scale(strongestNegativeVariance)
        );

        return new BudgetComparisonBundle(summary, months);
    }

    private BudgetComparisonBundle buildComparison(Long companyId, CashflowSummaryDto cashflow, BudgetService.LongBudgetSource annualSource) {
        if (annualSource == null) {
            return buildComparison(companyId, cashflow);
        }

        int comparisonYear = annualSource.comparisonYear() != null
            ? annualSource.comparisonYear()
            : kpiMonthlyRepository.findFirstByCompanyIdOrderByPeriodDesc(companyId)
                .map(KpiMonthly::getPeriod)
                .map(BudgetWorkflowService::parsePeriod)
                .map(YearMonth::getYear)
                .orElse(Year.now().getValue());

        String from = comparisonYear + "-01";
        String to = comparisonYear + "-12";
        List<KpiMonthly> actuals = kpiMonthlyRepository.findByCompanyIdAndPeriodBetweenOrderByPeriodAsc(companyId, from, to);
        Map<String, KpiMonthly> actualByPeriod = new LinkedHashMap<>();
        for (KpiMonthly item : actuals) {
            if (item != null && item.getPeriod() != null) {
                actualByPeriod.put(item.getPeriod(), item);
            }
        }

        List<BudgetComparisonMonthDto> months = new ArrayList<>();
        BigDecimal plannedInflowYtd = BigDecimal.ZERO;
        BigDecimal actualInflowYtd = BigDecimal.ZERO;
        BigDecimal plannedOutflowYtd = BigDecimal.ZERO;
        BigDecimal actualOutflowYtd = BigDecimal.ZERO;
        BigDecimal plannedNetYtd = BigDecimal.ZERO;
        BigDecimal actualNetYtd = BigDecimal.ZERO;
        BigDecimal plannedEndingBalanceLatest = null;
        BigDecimal actualEndingBalanceLatest = null;
        String latestComparedPeriod = null;
        String strongestPositiveMonth = null;
        BigDecimal strongestPositiveVariance = null;
        String strongestNegativeMonth = null;
        BigDecimal strongestNegativeVariance = null;
        int actualMonths = 0;
        int commonMonths = 0;
        int forecastMonths = 0;

        BigDecimal rollingObservedBalance = BigDecimal.ZERO;
        boolean rollingObservedActive = false;

        for (CashflowMonthDto planned : cashflow.months()) {
            int monthNumber = toMonthNumber(planned.monthKey());
            String period = monthNumber > 0 ? comparisonYear + "-" + String.format(Locale.ROOT, "%02d", monthNumber) : null;
            KpiMonthly kpiActual = period == null ? null : actualByPeriod.get(period);

            BigDecimal plannedInflow = scale(planned.inflow());
            BigDecimal plannedOutflow = scale(planned.outflow());
            BigDecimal plannedNet = scale(planned.net());
            BigDecimal plannedEndingBalance = scale(planned.endingBalance());

            BigDecimal csvActualInflow = scale(annualSource.actualIncomeByMonth().get(planned.monthKey()));
            BigDecimal csvActualOutflow = scale(
                zero(annualSource.actualExpenseByMonth().get(planned.monthKey()))
                    .add(zero(annualSource.actualCapexByMonth().get(planned.monthKey())))
            );
            BigDecimal csvForecastInflow = scale(annualSource.forecastIncomeByMonth().get(planned.monthKey()));
            BigDecimal csvForecastOutflow = scale(
                zero(annualSource.forecastExpenseByMonth().get(planned.monthKey()))
                    .add(zero(annualSource.forecastCapexByMonth().get(planned.monthKey())))
            );

            boolean hasKpiActual = kpiActual != null;
            boolean hasCsvActual = csvActualInflow != null || csvActualOutflow != null;
            boolean hasCsvForecast = csvForecastInflow != null || csvForecastOutflow != null;
            boolean hasObservedValue = hasKpiActual || hasCsvActual || hasCsvForecast;

            BigDecimal actualInflow;
            BigDecimal actualOutflow;
            BigDecimal actualEndingBalance;

            if (hasKpiActual) {
                actualInflow = scale(kpiActual.getInflows());
                actualOutflow = scale(kpiActual.getOutflows());
                actualEndingBalance = scale(kpiActual.getEndingBalance());
                actualMonths++;
            } else if (hasCsvActual) {
                actualInflow = zero(csvActualInflow);
                actualOutflow = zero(csvActualOutflow);
                rollingObservedBalance = rollingObservedBalance.add(actualInflow.subtract(actualOutflow));
                rollingObservedActive = true;
                actualEndingBalance = scale(rollingObservedBalance);
                actualMonths++;
            } else if (hasCsvForecast) {
                actualInflow = zero(csvForecastInflow);
                actualOutflow = zero(csvForecastOutflow);
                rollingObservedBalance = rollingObservedBalance.add(actualInflow.subtract(actualOutflow));
                rollingObservedActive = true;
                actualEndingBalance = scale(rollingObservedBalance);
                forecastMonths++;
            } else {
                actualInflow = null;
                actualOutflow = null;
                actualEndingBalance = rollingObservedActive ? scale(rollingObservedBalance) : null;
            }

            BigDecimal actualNet = actualInflow == null || actualOutflow == null ? null : scale(actualInflow.subtract(actualOutflow));

            BigDecimal inflowVariance = variance(actualInflow, plannedInflow);
            BigDecimal outflowVariance = variance(actualOutflow, plannedOutflow);
            BigDecimal netVariance = variance(actualNet, plannedNet);
            BigDecimal endingBalanceVariance = variance(actualEndingBalance, plannedEndingBalance);

            if (hasObservedValue) {
                commonMonths++;
                plannedInflowYtd = plannedInflowYtd.add(zero(plannedInflow));
                actualInflowYtd = actualInflowYtd.add(zero(actualInflow));
                plannedOutflowYtd = plannedOutflowYtd.add(zero(plannedOutflow));
                actualOutflowYtd = actualOutflowYtd.add(zero(actualOutflow));
                plannedNetYtd = plannedNetYtd.add(zero(plannedNet));
                actualNetYtd = actualNetYtd.add(zero(actualNet));
                plannedEndingBalanceLatest = plannedEndingBalance;
                actualEndingBalanceLatest = actualEndingBalance;
                latestComparedPeriod = period;

                if (netVariance != null && (strongestPositiveVariance == null || netVariance.compareTo(strongestPositiveVariance) > 0)) {
                    strongestPositiveVariance = netVariance;
                    strongestPositiveMonth = planned.label();
                }
                if (netVariance != null && (strongestNegativeVariance == null || netVariance.compareTo(strongestNegativeVariance) < 0)) {
                    strongestNegativeVariance = netVariance;
                    strongestNegativeMonth = planned.label();
                }
            }

            months.add(new BudgetComparisonMonthDto(
                planned.monthKey(),
                planned.label(),
                period,
                hasObservedValue,
                plannedInflow,
                actualInflow,
                inflowVariance,
                plannedOutflow,
                actualOutflow,
                outflowVariance,
                plannedNet,
                actualNet,
                netVariance,
                plannedEndingBalance,
                actualEndingBalance,
                endingBalanceVariance
            ));
        }

        BudgetComparisonSummaryDto summary = new BudgetComparisonSummaryDto(
            comparisonYear,
            cashflow.months().size(),
            actualMonths + forecastMonths,
            commonMonths,
            buildComparisonAssumption(comparisonYear, actualMonths, forecastMonths),
            latestComparedPeriod,
            scale(plannedInflowYtd),
            scale(actualInflowYtd),
            variance(scale(actualInflowYtd), scale(plannedInflowYtd)),
            scale(plannedOutflowYtd),
            scale(actualOutflowYtd),
            variance(scale(actualOutflowYtd), scale(plannedOutflowYtd)),
            scale(plannedNetYtd),
            scale(actualNetYtd),
            variance(scale(actualNetYtd), scale(plannedNetYtd)),
            scale(plannedEndingBalanceLatest),
            scale(actualEndingBalanceLatest),
            variance(scale(actualEndingBalanceLatest), scale(plannedEndingBalanceLatest)),
            strongestPositiveMonth,
            scale(strongestPositiveVariance),
            strongestNegativeMonth,
            scale(strongestNegativeVariance)
        );

        return new BudgetComparisonBundle(summary, months);
    }

    private static YearMonth parsePeriod(String value) {
        try {
            return value == null ? null : YearMonth.parse(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String buildComparisonAssumption(int comparisonYear, int actualMonths, int forecastMonths) {
        if (forecastMonths > 0 && actualMonths > 0) {
            return "Comparativa anual " + comparisonYear + ": meses cerrados con real detectado y meses abiertos apoyados en forecast del propio plan.";
        }
        if (forecastMonths > 0) {
            return "Comparativa anual " + comparisonYear + ": todavía no hay suficiente real externo y la lectura se apoya en forecast del propio plan.";
        }
        return "Comparativa anual " + comparisonYear + ": alineada con meses reales detectados en KPIs o en el propio CSV anual.";
    }

    private static int toMonthNumber(String monthKey) {
        if (monthKey == null) {
            return -1;
        }
        return switch (monthKey.trim().toUpperCase(Locale.ROOT)) {
            case "ENERO" -> 1;
            case "FEBRERO" -> 2;
            case "MARZO" -> 3;
            case "ABRIL" -> 4;
            case "MAYO" -> 5;
            case "JUNIO" -> 6;
            case "JULIO" -> 7;
            case "AGOSTO" -> 8;
            case "SEPTIEMBRE" -> 9;
            case "OCTUBRE" -> 10;
            case "NOVIEMBRE" -> 11;
            case "DICIEMBRE" -> 12;
            default -> -1;
        };
    }

    private static BigDecimal variance(BigDecimal actual, BigDecimal planned) {
        if (actual == null || planned == null) {
            return null;
        }
        return scale(actual.subtract(planned));
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String reasonOf(ResponseStatusException ex, String fallback) {
        String reason = ex == null ? null : ex.getReason();
        return reason == null || reason.isBlank() ? fallback : reason;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record BudgetComparisonBundle(BudgetComparisonSummaryDto summary, List<BudgetComparisonMonthDto> months) {}
}

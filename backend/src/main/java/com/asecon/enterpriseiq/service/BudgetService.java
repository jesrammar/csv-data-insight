package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.BudgetMonthDto;
import com.asecon.enterpriseiq.dto.BudgetMonthTotalDto;
import com.asecon.enterpriseiq.dto.BudgetItemDetailDto;
import com.asecon.enterpriseiq.dto.BudgetItemDetailMonthDto;
import com.asecon.enterpriseiq.dto.BudgetLongPreviewDto;
import com.asecon.enterpriseiq.dto.BudgetLongInsightsDto;
import com.asecon.enterpriseiq.dto.BudgetItemInsightDto;
import com.asecon.enterpriseiq.dto.BudgetSummaryDto;
import com.asecon.enterpriseiq.dto.CashflowMonthDto;
import com.asecon.enterpriseiq.dto.CashflowSummaryDto;
import com.asecon.enterpriseiq.model.UniversalImport;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BudgetService {
    private static final List<String> MONTH_KEYS = List.of(
        "ENERO", "FEBRERO", "MARZO", "ABRIL", "MAYO", "JUNIO",
        "JULIO", "AGOSTO", "SEPTIEMBRE", "OCTUBRE", "NOVIEMBRE", "DICIEMBRE"
    );

    private static final Map<String, String> MONTH_LABELS = Map.ofEntries(
        Map.entry("ENERO", "Enero"),
        Map.entry("FEBRERO", "Febrero"),
        Map.entry("MARZO", "Marzo"),
        Map.entry("ABRIL", "Abril"),
        Map.entry("MAYO", "Mayo"),
        Map.entry("JUNIO", "Junio"),
        Map.entry("JULIO", "Julio"),
        Map.entry("AGOSTO", "Agosto"),
        Map.entry("SEPTIEMBRE", "Septiembre"),
        Map.entry("OCTUBRE", "Octubre"),
        Map.entry("NOVIEMBRE", "Noviembre"),
        Map.entry("DICIEMBRE", "Diciembre")
    );

    private final UniversalImportFileService universalImportFileService;

    public BudgetService(UniversalImportFileService universalImportFileService) {
        this.universalImportFileService = universalImportFileService;
    }

    public BudgetPdfBundle latestBudgetPdfBundle(Long companyId) {
        UniversalImport imp = requireLatestAnnualBudget(companyId);
        byte[] bytes = requireNormalizedCsv(companyId, imp);
        String analysisVersion = buildAnalysisVersion(imp);
        return new BudgetPdfBundle(
            latestBudgetFromBytes(companyId, imp, bytes, analysisVersion),
            latestBudgetLongInsightsFromBytes(companyId, imp, bytes, analysisVersion)
        );
    }

    public BudgetLongPreviewDto latestBudgetLongPreview(Long companyId) {
        UniversalImport imp = universalImportFileService.latestAnnualBudget(companyId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No hay un presupuesto anual valido. Sube tu presupuesto (XLSX/CSV) a Universal."));

        byte[] bytes = universalImportFileService.normalizedCsv(companyId, imp.getId());
        var result = BudgetLongNormalizer.normalizeToLongCsv(bytes, String.valueOf(companyId), 10_000, 200);
        if (result.longCsvBytes().length == 0 || result.labelHeader() == null || result.monthKeys().isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "No pude detectar un presupuesto anual legible. Sube una hoja con meses ENERO..DICIEMBRE o un CSV anual mensualizado con campos de mes y plan."
            );
        }
        return new BudgetLongPreviewDto(
            imp.getFilename(),
            imp.getCreatedAt(),
            result.monthKeys(),
            result.labelHeader(),
            result.totalRowsProduced(),
            result.sampleRows(),
            result.requiresConfirmation(),
            result.mappingNotes()
        );
    }

    public byte[] latestBudgetLongCsv(Long companyId) {
        UniversalImport imp = requireLatestAnnualBudget(companyId);
        byte[] bytes = requireNormalizedCsv(companyId, imp);
        var result = BudgetLongNormalizer.normalizeToLongCsv(bytes, String.valueOf(companyId), 50_000, 0);
        if (result.longCsvBytes().length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo normalizar el presupuesto a formato largo.");
        }
        return result.longCsvBytes();
    }

    public BudgetLongInsightsDto latestBudgetLongInsights(Long companyId) {
        UniversalImport imp = requireLatestAnnualBudget(companyId);
        byte[] bytes = requireNormalizedCsv(companyId, imp);
        return latestBudgetLongInsightsFromBytes(companyId, imp, bytes, buildAnalysisVersion(imp));
    }

    public BudgetItemDetailDto latestBudgetItemDetail(Long companyId, String canonicalRowId) {
        UniversalImport imp = requireLatestAnnualBudget(companyId);
        byte[] bytes = requireNormalizedCsv(companyId, imp);
        String analysisVersion = buildAnalysisVersion(imp);
        var result = BudgetLongNormalizer.normalizeToLongCsv(bytes, String.valueOf(companyId), 50_000, 50);
        if (result.longCsvBytes().length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo normalizar el presupuesto a formato largo.");
        }
        if (result.requiresConfirmation()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, confirmationMessage(result, "La lectura anual necesita confirmacion semantica antes de abrir el detalle."));
        }
        CanonicalBudgetAnalysis analysis = parseCanonicalBudgetAnalysis(result.longCsvBytes(), imp.getFilename(), null);
        BudgetItemDetailDto detail = buildItemDetail(analysis, imp.getId(), analysisVersion, canonicalRowId);
        if (detail == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No se encontro detalle canonico para la partida seleccionada.");
        }
        return detail;
    }

    private BudgetLongInsightsDto latestBudgetLongInsightsFromBytes(Long companyId,
                                                                    UniversalImport imp,
                                                                    byte[] bytes,
                                                                    String analysisVersion) {
        var result = BudgetLongNormalizer.normalizeToLongCsv(bytes, String.valueOf(companyId), 50_000, 50);
        if (result.longCsvBytes().length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo normalizar el presupuesto a formato largo.");
        }
        if (result.requiresConfirmation()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, confirmationMessage(result, "La lectura anual necesita confirmacion semantica antes de generar conclusiones."));
        }
        var insights = parseCanonicalBudgetAnalysis(result.longCsvBytes(), imp.getFilename(), null).insights();
        if (insights.itemCount() == 0 || insights.monthTotals().isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "No pude calcular insights anuales. Revisa la hoja o la fila de cabecera, o sube un CSV anual con meses y plan mensual."
            );
        }
        return attachAnalysisMetadata(
            new BudgetLongInsightsDto(
            insights.filename(),
            insights.createdAt(),
            imp.getId(),
            analysisVersion,
            insights.itemCount(),
            insights.totalAbsAnnual(),
            insights.bestMonth(),
            insights.worstMonth(),
            insights.concentrationTop3AbsPct(),
            insights.monthTotals(),
            insights.topDrivers(),
            insights.zeroHeavyItems(),
            insights.accountingAdjustments()
            ),
            imp.getId(),
            analysisVersion
        );
    }

    public BudgetSummaryDto latestBudget(Long companyId) {
        UniversalImport imp = requireLatestAnnualBudget(companyId);
        byte[] bytes = requireNormalizedCsv(companyId, imp);
        return latestBudgetFromBytes(companyId, imp, bytes, buildAnalysisVersion(imp));
    }

    private BudgetSummaryDto latestBudgetFromBytes(Long companyId,
                                                   UniversalImport imp,
                                                   byte[] bytes,
                                                   String analysisVersion) {
        if (bytes == null || bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Import universal vacío.");
        }

        String head = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.UTF_8);
        int eol = head.indexOf('\n');
        if (eol >= 0) head = head.substring(0, eol);
        char delimiter = detectDelimiter(head);
        BudgetLongNormalizer.Result canonicalResult = BudgetLongNormalizer.normalizeToLongCsv(bytes, String.valueOf(companyId), 50_000, 50);
        if (canonicalResult.longCsvBytes().length > 0) {
            if (canonicalResult.requiresConfirmation()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, confirmationMessage(canonicalResult, "La lectura anual necesita confirmacion semantica antes de consolidar el plan."));
            }
            BudgetSummaryDto canonicalSummary = tryBuildLongBudgetSummary(companyId, imp, canonicalResult.longCsvBytes(), analysisVersion);
            if (canonicalSummary != null) return canonicalSummary;
        }

        Map<String, String> monthHeader = new LinkedHashMap<>();
        String labelHeader;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            CSVParser parser = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setAllowMissingColumnNames(true)
                .setIgnoreEmptyLines(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .build()
                .parse(reader);

            List<String> headers = new ArrayList<>(parser.getHeaderMap().keySet());
            if (headers.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV/XLSX sin cabeceras detectables.");
            }

            for (String h : headers) {
                if (h == null) continue;
                String norm = h.trim().toUpperCase(Locale.ROOT);
                if (MONTH_LABELS.containsKey(norm)) {
                    monthHeader.put(norm, h);
                }
            }
            long present = monthHeader.keySet().stream().filter(MONTH_LABELS::containsKey).count();
            if (present < 6) {
                BudgetSummaryDto longSummary = tryBuildLongBudgetSummary(companyId, imp, bytes, analysisVersion);
                if (longSummary != null) return longSummary;
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "No detecto meses ENERO..DICIEMBRE ni un formato anual mensualizado compatible. Usa el modo guiado y revisa hoja + cabecera."
                );
            }

            // Detect label column (sometimes the first column is empty and becomes col_1)
            List<String> candidates = headers.stream()
                .filter(Objects::nonNull)
                .filter(h -> !monthHeader.containsValue(h))
                .limit(5)
                .toList();

            Map<String, Integer> hits = new LinkedHashMap<>();
            for (String c : candidates) hits.put(c, 0);

            int rows = 0;
            for (CSVRecord record : parser) {
                rows++;
                if (rows > 120) break;
                for (String c : candidates) {
                    String label = clean(get(record, c));
                    if (label == null) continue;
                    String up = label.toUpperCase(Locale.ROOT);
                    if (matchesIncomeRow(up) || matchesExpenseRow(up)) {
                        hits.put(c, hits.getOrDefault(c, 0) + 1);
                    }
                }
            }

            labelHeader = hits.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .map(Map.Entry::getKey)
                .orElse(headers.get(0));

            Map<String, BigDecimal> income = new LinkedHashMap<>();
            Map<String, BigDecimal> expense = new LinkedHashMap<>();

            parser = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setAllowMissingColumnNames(true)
                .setIgnoreEmptyLines(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .build()
                .parse(new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)));

            rows = 0;
            for (CSVRecord record : parser) {
                rows++;
                if (rows > 800) break;
                String label = clean(get(record, labelHeader));
                if (label == null) continue;
                String up = label.toUpperCase(Locale.ROOT);

                if (matchesIncomeRow(up)) {
                    for (String mk : MONTH_KEYS) {
                        String h = monthHeader.get(mk);
                        if (h == null) continue;
                        BigDecimal v = parseMoney(clean(get(record, h)));
                        if (v != null) income.put(mk, v);
                    }
                }

                if (matchesExpenseRow(up)) {
                    for (String mk : MONTH_KEYS) {
                        String h = monthHeader.get(mk);
                        if (h == null) continue;
                        BigDecimal v = parseMoney(clean(get(record, h)));
                        if (v != null) expense.put(mk, v);
                    }
                }
            }

            if (income.isEmpty() || expense.isEmpty()) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "He detectado meses, pero no encuentro filas de 'Total ingresos' y 'Gastos explotación'. Asegúrate de subir la hoja de presupuesto (Cuenta de explotación)."
                );
            }

            List<BudgetMonthDto> months = new ArrayList<>();
            BigDecimal totalIncome = BigDecimal.ZERO;
            BigDecimal totalExpense = BigDecimal.ZERO;

            BigDecimal prevMargin = null;
            String bestMonth = null;
            BigDecimal best = null;
            String worstMonth = null;
            BigDecimal worst = null;

            for (String mk : MONTH_KEYS) {
                if (!monthHeader.containsKey(mk)) continue;
                BigDecimal inc = income.getOrDefault(mk, BigDecimal.ZERO);
                BigDecimal exp = expense.getOrDefault(mk, BigDecimal.ZERO);
                BigDecimal margin = inc.subtract(exp);

                BigDecimal delta = null;
                BigDecimal deltaPct = null;
                if (prevMargin != null) {
                    delta = margin.subtract(prevMargin);
                    if (prevMargin.compareTo(BigDecimal.ZERO) != 0) {
                        deltaPct = delta
                            .divide(prevMargin.abs(), 6, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .setScale(2, RoundingMode.HALF_UP);
                    }
                }

                months.add(new BudgetMonthDto(
                    mk,
                    MONTH_LABELS.getOrDefault(mk, mk),
                    inc.setScale(2, RoundingMode.HALF_UP),
                    exp.setScale(2, RoundingMode.HALF_UP),
                    margin.setScale(2, RoundingMode.HALF_UP),
                    delta == null ? null : delta.setScale(2, RoundingMode.HALF_UP),
                    deltaPct
                ));

                totalIncome = totalIncome.add(inc);
                totalExpense = totalExpense.add(exp);
                prevMargin = margin;

                if (best == null || margin.compareTo(best) > 0) {
                    best = margin;
                    bestMonth = mk;
                }
                if (worst == null || margin.compareTo(worst) < 0) {
                    worst = margin;
                    worstMonth = mk;
                }
            }

            BigDecimal totalMargin = totalIncome.subtract(totalExpense).setScale(2, RoundingMode.HALF_UP);
            return new BudgetSummaryDto(
                imp.getFilename(),
                imp.getCreatedAt(),
                imp.getId(),
                analysisVersion,
                months,
                totalIncome.setScale(2, RoundingMode.HALF_UP),
                totalExpense.setScale(2, RoundingMode.HALF_UP),
                totalMargin,
                null,
                null,
                null,
                null,
                null,
                bestMonth,
                worstMonth
            );
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo interpretar el presupuesto. Usa el modo guiado (hoja + cabecera).");
        }
    }

    public CashflowSummaryDto latestCashflow(Long companyId) {
        UniversalImport imp = requireLatestAnnualBudget(companyId);
        byte[] bytes = requireNormalizedCsv(companyId, imp);
        return latestCashflowFromBytes(companyId, imp, bytes, buildAnalysisVersion(imp));
    }

    private CashflowSummaryDto latestCashflowFromBytes(Long companyId,
                                                       UniversalImport imp,
                                                       byte[] bytes,
                                                       String analysisVersion) {
        if (bytes == null || bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Import universal vacío.");
        }

        String head = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.UTF_8);
        int eol = head.indexOf('\n');
        if (eol >= 0) head = head.substring(0, eol);
        char delimiter = detectDelimiter(head);
        BudgetLongNormalizer.Result canonicalResult = BudgetLongNormalizer.normalizeToLongCsv(bytes, String.valueOf(companyId), 50_000, 50);
        if (canonicalResult.longCsvBytes().length > 0) {
            if (canonicalResult.requiresConfirmation()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, confirmationMessage(canonicalResult, "La lectura anual necesita confirmacion semantica antes de leer la tesoreria."));
            }
            CashflowSummaryDto canonicalCashflow = tryBuildLongCashflowSummary(companyId, imp, canonicalResult.longCsvBytes(), analysisVersion);
            if (canonicalCashflow != null) return canonicalCashflow;
        }

        Map<String, String> monthHeader = new LinkedHashMap<>();
        String labelHeader;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            CSVParser parser = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setAllowMissingColumnNames(true)
                .setIgnoreEmptyLines(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .build()
                .parse(reader);

            List<String> headers = new ArrayList<>(parser.getHeaderMap().keySet());
            if (headers.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV/XLSX sin cabeceras detectables.");
            }

            for (String h : headers) {
                if (h == null) continue;
                String norm = h.trim().toUpperCase(Locale.ROOT);
                if (MONTH_LABELS.containsKey(norm)) {
                    monthHeader.put(norm, h);
                }
            }
            long present = monthHeader.keySet().stream().filter(MONTH_LABELS::containsKey).count();
            if (present < 6) {
                CashflowSummaryDto longCashflow = tryBuildLongCashflowSummary(companyId, imp, bytes, analysisVersion);
                if (longCashflow != null) return longCashflow;
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "No detecto meses ENERO..DICIEMBRE ni un formato anual mensualizado compatible. Usa el modo guiado y revisa hoja + cabecera."
                );
            }

            // detect best label column (same technique as P&L)
            List<String> candidates = headers.stream()
                .filter(Objects::nonNull)
                .filter(h -> !monthHeader.containsValue(h))
                .limit(6)
                .toList();
            Map<String, Integer> hits = new LinkedHashMap<>();
            for (String c : candidates) hits.put(c, 0);

            int rows = 0;
            for (CSVRecord record : parser) {
                rows++;
                if (rows > 160) break;
                for (String c : candidates) {
                    String label = clean(get(record, c));
                    if (label == null) continue;
                    String up = label.toUpperCase(Locale.ROOT);
                    if (matchesCashInflowRow(up) || matchesCashOutflowRow(up) || up.contains("SALDO") && up.contains("INICIAL")) {
                        hits.put(c, hits.getOrDefault(c, 0) + 1);
                    }
                }
            }
            labelHeader = hits.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .map(Map.Entry::getKey)
                .orElse(headers.get(0));

            parser = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setAllowMissingColumnNames(true)
                .setIgnoreEmptyLines(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .build()
                .parse(new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)));

            Map<String, BigDecimal> inflow = new LinkedHashMap<>();
            Map<String, BigDecimal> outflow = new LinkedHashMap<>();
            BigDecimal openingBalance = null;

            rows = 0;
            for (CSVRecord record : parser) {
                rows++;
                if (rows > 1000) break;
                String label = clean(get(record, labelHeader));
                if (label == null) continue;
                String up = label.toUpperCase(Locale.ROOT);

                if (openingBalance == null && up.contains("SALDO") && up.contains("INICIAL")) {
                    openingBalance = firstMoney(record, monthHeader, headers);
                }

                if (matchesCashInflowRow(up)) {
                    for (String mk : MONTH_KEYS) {
                        String h = monthHeader.get(mk);
                        if (h == null) continue;
                        BigDecimal v = parseMoney(clean(get(record, h)));
                        if (v != null) inflow.put(mk, v);
                    }
                }
                if (matchesCashOutflowRow(up)) {
                    for (String mk : MONTH_KEYS) {
                        String h = monthHeader.get(mk);
                        if (h == null) continue;
                        BigDecimal v = parseMoney(clean(get(record, h)));
                        if (v != null) outflow.put(mk, v);
                    }
                }
            }

            if (openingBalance == null) openingBalance = BigDecimal.ZERO;
            if (inflow.isEmpty() || outflow.isEmpty()) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "He detectado meses, pero no encuentro filas de Cashflow (Total facturación / Total gastos). Asegúrate de subir la hoja que contiene la tabla de tesorería."
                );
            }

            List<CashflowMonthDto> months = new ArrayList<>();
            BigDecimal totalIn = BigDecimal.ZERO;
            BigDecimal totalOut = BigDecimal.ZERO;
            BigDecimal totalNet = BigDecimal.ZERO;
            BigDecimal balance = openingBalance;
            BigDecimal prevNet = null;

            String bestMonth = null;
            BigDecimal best = null;
            String worstMonth = null;
            BigDecimal worst = null;

            for (String mk : MONTH_KEYS) {
                if (!monthHeader.containsKey(mk)) continue;
                BigDecimal in = inflow.getOrDefault(mk, BigDecimal.ZERO);
                BigDecimal out = outflow.getOrDefault(mk, BigDecimal.ZERO);
                BigDecimal net = in.subtract(out);
                balance = balance.add(net);

                BigDecimal delta = null;
                BigDecimal deltaPct = null;
                if (prevNet != null) {
                    delta = net.subtract(prevNet);
                    if (prevNet.compareTo(BigDecimal.ZERO) != 0) {
                        deltaPct = delta
                            .divide(prevNet.abs(), 6, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .setScale(2, RoundingMode.HALF_UP);
                    }
                }

                months.add(new CashflowMonthDto(
                    mk,
                    MONTH_LABELS.getOrDefault(mk, mk),
                    in.setScale(2, RoundingMode.HALF_UP),
                    out.setScale(2, RoundingMode.HALF_UP),
                    net.setScale(2, RoundingMode.HALF_UP),
                    balance.setScale(2, RoundingMode.HALF_UP),
                    delta == null ? null : delta.setScale(2, RoundingMode.HALF_UP),
                    deltaPct
                ));

                totalIn = totalIn.add(in);
                totalOut = totalOut.add(out);
                totalNet = totalNet.add(net);
                prevNet = net;

                if (best == null || net.compareTo(best) > 0) { best = net; bestMonth = mk; }
                if (worst == null || net.compareTo(worst) < 0) { worst = net; worstMonth = mk; }
            }

            return new CashflowSummaryDto(
                imp.getFilename(),
                imp.getCreatedAt(),
                imp.getId(),
                analysisVersion,
                openingBalance.setScale(2, RoundingMode.HALF_UP),
                months,
                totalIn.setScale(2, RoundingMode.HALF_UP),
                totalOut.setScale(2, RoundingMode.HALF_UP),
                totalNet.setScale(2, RoundingMode.HALF_UP),
                balance.setScale(2, RoundingMode.HALF_UP),
                bestMonth,
                worstMonth
            );
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo interpretar el cashflow. Usa el modo guiado (hoja + cabecera).");
        }
    }

    private UniversalImport requireLatestAnnualBudget(Long companyId) {
        return universalImportFileService.latestAnnualBudget(companyId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No hay un presupuesto anual valido. Sube tu presupuesto (XLSX/CSV) a Universal."));
    }

    private byte[] requireNormalizedCsv(Long companyId, UniversalImport imp) {
        return universalImportFileService.normalizedCsv(companyId, imp.getId());
    }

    private String buildAnalysisVersion(UniversalImport imp) {
        if (imp == null) return null;
        String importId = imp.getId() == null ? "pending" : String.valueOf(imp.getId());
        String createdAt = imp.getCreatedAt() == null ? "unknown" : String.valueOf(imp.getCreatedAt().toEpochMilli());
        return "budget-" + importId + "-" + createdAt;
    }

    private static boolean matchesIncomeRow(String upperLabel) {
        return upperLabel.contains("TOTAL") && upperLabel.contains("INGRES");
    }

    private static boolean matchesExpenseRow(String upperLabel) {
        return upperLabel.contains("GASTOS") && upperLabel.contains("EXPLOT");
    }

    private BudgetSummaryDto tryBuildLongBudgetSummary(Long companyId,
                                                       UniversalImport imp,
                                                       byte[] bytes,
                                                       String analysisVersion) {
        byte[] canonicalCsv = isCanonicalBudgetCsv(bytes)
            ? bytes
            : BudgetLongNormalizer.normalizeToLongCsv(bytes, String.valueOf(companyId), 50_000, 0).longCsvBytes();
        LongBudgetSource source = parseCanonicalBudgetSource(canonicalCsv);
        if (source == null || source.plannedIncomeByMonth.isEmpty() || source.plannedExpenseByMonth.isEmpty()) {
            return null;
        }

        List<BudgetMonthDto> months = new ArrayList<>();
        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal totalExpense = BigDecimal.ZERO;
        BigDecimal totalCapex = total(source.plannedCapexByMonth().values()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalDepreciation = total(source.plannedDepreciationByMonth().values()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal financialResult = total(source.plannedFinancialResultByMonth().values()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal prevMargin = null;
        String bestMonth = null;
        String worstMonth = null;
        BigDecimal best = null;
        BigDecimal worst = null;

        for (String mk : source.orderedMonthKeys) {
            BigDecimal inc = source.plannedIncomeByMonth.getOrDefault(mk, BigDecimal.ZERO);
            BigDecimal exp = source.plannedExpenseByMonth.getOrDefault(mk, BigDecimal.ZERO);
            BigDecimal margin = inc.subtract(exp);

            BigDecimal delta = null;
            BigDecimal deltaPct = null;
            if (prevMargin != null) {
                delta = margin.subtract(prevMargin);
                if (prevMargin.compareTo(BigDecimal.ZERO) != 0) {
                    deltaPct = delta
                        .divide(prevMargin.abs(), 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP);
                }
            }

            months.add(new BudgetMonthDto(
                mk,
                MONTH_LABELS.getOrDefault(mk, mk),
                inc.setScale(2, RoundingMode.HALF_UP),
                exp.setScale(2, RoundingMode.HALF_UP),
                margin.setScale(2, RoundingMode.HALF_UP),
                delta == null ? null : delta.setScale(2, RoundingMode.HALF_UP),
                deltaPct
            ));

            totalIncome = totalIncome.add(inc);
            totalExpense = totalExpense.add(exp);
            prevMargin = margin;

            if (best == null || margin.compareTo(best) > 0) { best = margin; bestMonth = mk; }
            if (worst == null || margin.compareTo(worst) < 0) { worst = margin; worstMonth = mk; }
        }

        return new BudgetSummaryDto(
            imp.getFilename(),
            imp.getCreatedAt(),
            imp.getId(),
            analysisVersion,
            months,
            totalIncome.setScale(2, RoundingMode.HALF_UP),
            totalExpense.setScale(2, RoundingMode.HALF_UP),
            totalIncome.subtract(totalExpense).setScale(2, RoundingMode.HALF_UP),
            totalCapex,
            totalDepreciation,
            totalIncome.subtract(totalExpense).subtract(totalDepreciation).setScale(2, RoundingMode.HALF_UP),
            financialResult,
            totalIncome.subtract(totalExpense).subtract(totalDepreciation).add(financialResult).setScale(2, RoundingMode.HALF_UP),
            bestMonth,
            worstMonth
        );
    }

    private CashflowSummaryDto tryBuildLongCashflowSummary(Long companyId,
                                                           UniversalImport imp,
                                                           byte[] bytes,
                                                           String analysisVersion) {
        byte[] canonicalCsv = isCanonicalBudgetCsv(bytes)
            ? bytes
            : BudgetLongNormalizer.normalizeToLongCsv(bytes, String.valueOf(companyId), 50_000, 0).longCsvBytes();
        LongBudgetSource source = parseCanonicalBudgetSource(canonicalCsv);
        if (source == null || (source.plannedIncomeByMonth.isEmpty() && source.plannedExpenseByMonth.isEmpty())) {
            return null;
        }

        List<CashflowMonthDto> months = new ArrayList<>();
        String firstMonth = source.orderedMonthKeys().isEmpty() ? null : source.orderedMonthKeys().get(0);
        BigDecimal openingBalance = firstMonth == null
            ? BigDecimal.ZERO
            : source.plannedOpeningBalanceByMonth().getOrDefault(firstMonth, BigDecimal.ZERO);
        BigDecimal balance = openingBalance;
        BigDecimal totalIn = BigDecimal.ZERO;
        BigDecimal totalOut = BigDecimal.ZERO;
        BigDecimal totalNet = BigDecimal.ZERO;
        BigDecimal prevNet = null;
        String bestMonth = null;
        String worstMonth = null;
        BigDecimal best = null;
        BigDecimal worst = null;
        boolean hasExplicitCash = !source.plannedCashInflowByMonth().isEmpty() || !source.plannedCashOutflowByMonth().isEmpty();
        boolean hasClosingBalances = !source.plannedClosingBalanceByMonth().isEmpty();

        for (String mk : source.orderedMonthKeys) {
            BigDecimal in = hasExplicitCash
                ? source.plannedCashInflowByMonth().getOrDefault(mk, BigDecimal.ZERO)
                : source.plannedIncomeByMonth.getOrDefault(mk, BigDecimal.ZERO);
            BigDecimal out = hasExplicitCash
                ? source.plannedCashOutflowByMonth().getOrDefault(mk, BigDecimal.ZERO)
                : source.plannedExpenseByMonth.getOrDefault(mk, BigDecimal.ZERO)
                    .add(source.plannedCapexByMonth.getOrDefault(mk, BigDecimal.ZERO))
                    .add(source.plannedTaxByMonth().getOrDefault(mk, BigDecimal.ZERO));
            BigDecimal financing = source.plannedFinancingByMonth().getOrDefault(mk, BigDecimal.ZERO);
            if (!hasExplicitCash && financing.compareTo(BigDecimal.ZERO) != 0) {
                out = out.add(financing.abs());
            }
            BigDecimal net = in.subtract(out);
            balance = hasClosingBalances
                ? source.plannedClosingBalanceByMonth().getOrDefault(mk, balance.add(net))
                : balance.add(net);

            BigDecimal delta = null;
            BigDecimal deltaPct = null;
            if (prevNet != null) {
                delta = net.subtract(prevNet);
                if (prevNet.compareTo(BigDecimal.ZERO) != 0) {
                    deltaPct = delta
                        .divide(prevNet.abs(), 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP);
                }
            }

            months.add(new CashflowMonthDto(
                mk,
                MONTH_LABELS.getOrDefault(mk, mk),
                in.setScale(2, RoundingMode.HALF_UP),
                out.setScale(2, RoundingMode.HALF_UP),
                net.setScale(2, RoundingMode.HALF_UP),
                balance.setScale(2, RoundingMode.HALF_UP),
                delta == null ? null : delta.setScale(2, RoundingMode.HALF_UP),
                deltaPct
            ));

            totalIn = totalIn.add(in);
            totalOut = totalOut.add(out);
            totalNet = totalNet.add(net);
            prevNet = net;

            if (best == null || net.compareTo(best) > 0) { best = net; bestMonth = mk; }
            if (worst == null || net.compareTo(worst) < 0) { worst = net; worstMonth = mk; }
        }

        return new CashflowSummaryDto(
            imp.getFilename(),
            imp.getCreatedAt(),
            imp.getId(),
            analysisVersion,
            openingBalance.setScale(2, RoundingMode.HALF_UP),
            months,
            totalIn.setScale(2, RoundingMode.HALF_UP),
            totalOut.setScale(2, RoundingMode.HALF_UP),
            totalNet.setScale(2, RoundingMode.HALF_UP),
            balance.setScale(2, RoundingMode.HALF_UP),
            bestMonth,
            worstMonth
        );
    }

    public LongBudgetSource latestLongBudgetSource(Long companyId) {
        UniversalImport imp = universalImportFileService.latestAnnualBudget(companyId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No hay un presupuesto anual valido. Sube tu presupuesto (XLSX/CSV) a Universal."));
        byte[] sourceBytes = universalImportFileService.normalizedCsv(companyId, imp.getId());
        BudgetLongNormalizer.Result result = BudgetLongNormalizer.normalizeToLongCsv(sourceBytes, String.valueOf(companyId), 50_000, 50);
        if (result.longCsvBytes().length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo normalizar el presupuesto a formato largo.");
        }
        if (result.requiresConfirmation()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, confirmationMessage(result, "La lectura anual necesita confirmacion semantica antes de usarse como fuente oficial."));
        }
        byte[] bytes = result.longCsvBytes();
        if (bytes == null || bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Import universal vacío.");
        }

        LongBudgetSource source = parseCanonicalBudgetSource(bytes);
        if (source == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo interpretar el CSV anual mensualizado.");
        }
        return source;
    }

    private LongBudgetSource parseCanonicalBudgetSource(byte[] bytes) {
        return parseCanonicalBudgetAnalysis(bytes, null, null).source();
    }

    CanonicalBudgetAnalysis parseCanonicalBudgetAnalysis(byte[] bytes, String sourceFile, String sourceSheet) {
        if (bytes == null || bytes.length == 0) {
            return new CanonicalBudgetAnalysis(null, List.of(), List.of(), emptyCanonicalInsights(sourceFile), emptyDiagnostics());
        }
        String head = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.UTF_8);
        int eol = head.indexOf('\n');
        if (eol >= 0) head = head.substring(0, eol);
        char delimiter = detectDelimiter(head);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            CSVParser parser = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setAllowMissingColumnNames(true)
                .setIgnoreEmptyLines(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .build()
                .parse(reader);

            List<String> headers = new ArrayList<>(parser.getHeaderMap().keySet());
            if (!headers.contains("month_key") || !headers.contains("semantic_kind")) {
                return new CanonicalBudgetAnalysis(null, List.of(), List.of(), emptyCanonicalInsights(sourceFile), emptyDiagnostics());
            }

            List<CanonicalRow> rows = new ArrayList<>();
            Map<String, Boolean> seenMonths = new LinkedHashMap<>();
            Integer detectedYear = null;
            int index = 0;
            for (CSVRecord record : parser) {
                index++;
                if (index > 50_000) break;
                String monthToken = clean(get(record, "month_key"));
                String monthKey = resolveCanonicalMonthKey(monthToken);
                if (monthKey == null) continue;

                String semanticKind = upper(clean(get(record, "semantic_kind")));
                String financialNature = upper(clean(get(record, "financial_nature")));
                String cashflowNature = upper(clean(get(record, "cashflow_nature")));
                String rowType = upper(clean(get(record, "row_type")));
                String sectionKind = upper(clean(get(record, "section_kind")));
                String mappingStatus = upper(clean(get(record, "mapping_status")));
                BigDecimal plannedAmount = firstNonNullAmount(
                    parseMoney(clean(get(record, "budget_amount"))),
                    parseMoney(clean(get(record, "amount")))
                );
                BigDecimal actualAmount = parseMoney(clean(get(record, "actual_amount")));
                BigDecimal forecastAmount = parseMoney(clean(get(record, "forecast_amount")));
                BigDecimal varianceAmount = parseMoney(clean(get(record, "variance_amount")));
                String rawLabel = clean(get(record, "label"));
                String rawCode = clean(get(record, "code"));
                String code = normalizeBudgetCode(rawCode, rawLabel);
                String label = normalizeBudgetLabel(rawLabel, code);
                String normalizedLabel = normalizeIdentityLabel(label);
                String blockId = firstNonNull(clean(get(record, "block_id")), "ROW-" + index);
                Integer sourceRow = parseInteger(clean(get(record, "source_row")));
                String confidence = upper(clean(get(record, "inference_confidence")));
                String effectiveFinancialKind = !financialNature.isBlank() ? financialNature : semanticKind;
                String effectiveCashflowKind = !cashflowNature.isBlank() ? cashflowNature : semanticKind;

                if (detectedYear == null) {
                    detectedYear = parseYearFromMonthToken(monthToken);
                }
                seenMonths.put(monthKey, Boolean.TRUE);

                rows.add(new CanonicalRow(
                    rows.size(),
                    sourceFile,
                    sourceSheet,
                    sourceRow,
                    blockId,
                    label,
                    normalizedLabel,
                    code,
                    rowType,
                    semanticKind,
                    effectiveFinancialKind,
                    effectiveCashflowKind,
                    sectionKind,
                    mappingStatus,
                    confidence,
                    monthKey,
                    plannedAmount,
                    actualAmount,
                    forecastAmount,
                    varianceAmount
                ));
            }

            if (rows.isEmpty() || seenMonths.isEmpty()) {
                return new CanonicalBudgetAnalysis(null, List.of(), List.of(), emptyCanonicalInsights(sourceFile), emptyDiagnostics());
            }

            Map<String, BigDecimal> income = new LinkedHashMap<>();
            Map<String, BigDecimal> expense = new LinkedHashMap<>();
            Map<String, BigDecimal> capex = new LinkedHashMap<>();
            Map<String, BigDecimal> depreciation = new LinkedHashMap<>();
            Map<String, BigDecimal> tax = new LinkedHashMap<>();
            Map<String, BigDecimal> financialResultRaw = new LinkedHashMap<>();
            Map<String, BigDecimal> cashInflow = new LinkedHashMap<>();
            Map<String, BigDecimal> cashOutflow = new LinkedHashMap<>();
            Map<String, BigDecimal> financing = new LinkedHashMap<>();
            Map<String, BigDecimal> openingBalance = new LinkedHashMap<>();
            Map<String, BigDecimal> closingBalance = new LinkedHashMap<>();
            Map<String, BigDecimal> actualIncome = new LinkedHashMap<>();
            Map<String, BigDecimal> actualExpense = new LinkedHashMap<>();
            Map<String, BigDecimal> actualCapex = new LinkedHashMap<>();
            Map<String, BigDecimal> forecastIncome = new LinkedHashMap<>();
            Map<String, BigDecimal> forecastExpense = new LinkedHashMap<>();
            Map<String, BigDecimal> forecastCapex = new LinkedHashMap<>();

            Map<Integer, String> exclusions = new LinkedHashMap<>();
            Map<Integer, String> aggregationPolicy = new LinkedHashMap<>();
            Map<Integer, Boolean> pnlIncluded = new LinkedHashMap<>();
            Map<Integer, Boolean> cashflowIncluded = new LinkedHashMap<>();
            Map<Integer, Boolean> driverIncluded = new LinkedHashMap<>();
            markDuplicates(rows, exclusions);
            markHierarchicalBreakdowns(rows, exclusions);
            Map<String, Boolean> zeroSeriesIdentity = detectZeroSeries(rows);
            Set<String> pnlDetailFinancialIdentities = collectPnlDetailFinancialIdentities(rows, exclusions);

            List<String> pnlKinds = List.of("REVENUE", "OPEX", "OPERATING_ADJUSTMENT", "CAPEX", "DEPRECIATION_AMORTIZATION", "TAX", "FINANCING");
            for (String monthKey : seenMonths.keySet()) {
                for (String kind : pnlKinds) {
                    applyPreferredRows(
                        rows,
                        exclusions,
                        aggregationPolicy,
                        pnlIncluded,
                        monthKey,
                        kind,
                        "P_AND_L",
                        true,
                        pnlDetailFinancialIdentities,
                        value -> {
                            switch (kind) {
                                case "REVENUE" -> mergeAmount(income, monthKey, value);
                                case "OPEX" -> mergeAmount(expense, monthKey, value);
                                case "OPERATING_ADJUSTMENT" -> mergeAmount(income, monthKey, value);
                                case "CAPEX" -> mergeAmount(capex, monthKey, value);
                                case "DEPRECIATION_AMORTIZATION" -> mergeAmount(depreciation, monthKey, value);
                                case "TAX" -> mergeAmount(tax, monthKey, value);
                                case "FINANCING" -> mergeAmount(financialResultRaw, monthKey, value);
                                default -> {}
                            }
                        },
                        value -> {
                            switch (kind) {
                                case "REVENUE" -> mergeAmount(actualIncome, monthKey, value);
                                case "OPEX" -> mergeAmount(actualExpense, monthKey, value);
                                case "CAPEX" -> mergeAmount(actualCapex, monthKey, value);
                                default -> {}
                            }
                        },
                        value -> {
                            switch (kind) {
                                case "REVENUE" -> mergeAmount(forecastIncome, monthKey, value);
                                case "OPEX" -> mergeAmount(forecastExpense, monthKey, value);
                                case "CAPEX" -> mergeAmount(forecastCapex, monthKey, value);
                                default -> {}
                            }
                        },
                        zeroSeriesIdentity
                    );
                }
                for (String kind : List.of("CASH_INFLOW", "CASH_OUTFLOW", "FINANCING")) {
                    applyPreferredRows(
                        rows,
                        exclusions,
                        aggregationPolicy,
                        cashflowIncluded,
                        monthKey,
                        kind,
                        "CASHFLOW",
                        false,
                        null,
                        value -> {
                            switch (kind) {
                                case "CASH_INFLOW" -> mergeAmount(cashInflow, monthKey, value);
                                case "CASH_OUTFLOW" -> mergeAmount(cashOutflow, monthKey, value);
                                case "FINANCING" -> mergeAmount(financing, monthKey, value);
                                default -> {}
                            }
                        },
                        null,
                        null,
                        zeroSeriesIdentity
                    );
                }
                applyLastValueRows(rows, exclusions, aggregationPolicy, cashflowIncluded, monthKey, "OPENING_BALANCE", openingBalance, zeroSeriesIdentity);
                applyLastValueRows(rows, exclusions, aggregationPolicy, cashflowIncluded, monthKey, "CLOSING_BALANCE", closingBalance, zeroSeriesIdentity);
            }

            for (CanonicalRow row : rows) {
                if ("REVIEW".equals(row.mappingStatus()) && !exclusions.containsKey(row.index())) {
                    exclusions.put(row.index(), "EXCLUDED_REVIEW");
                } else if ("DERIVED_KPI".equals(row.rowType()) && !exclusions.containsKey(row.index())) {
                    exclusions.put(row.index(), "EXCLUDED_DERIVED_KPI");
                } else if (Boolean.TRUE.equals(zeroSeriesIdentity.get(row.canonicalIdentity())) && !exclusions.containsKey(row.index())) {
                    exclusions.put(row.index(), "EXCLUDED_NO_ACTIVITY");
                } else if (!supportsSemantic(row.effectiveFinancialKind(), row.effectiveCashflowKind()) && !exclusions.containsKey(row.index())) {
                    exclusions.put(row.index(), "EXCLUDED_UNSUPPORTED_SEMANTIC");
                }
                if (matchesSection(row.sectionKind(), "CASHFLOW")
                    && isFinancialSemantic(row.effectiveFinancialKind())
                    && !Boolean.TRUE.equals(pnlIncluded.get(row.index()))
                    && !exclusions.containsKey(row.index())) {
                    exclusions.put(row.index(), "EXCLUDED_CASHFLOW_FROM_PNL");
                }
                boolean driver = isDriverRow(row, pnlIncluded);
                driverIncluded.put(row.index(), driver);
            }

            Map<String, BigDecimal> financialResult = rebuildFinancialResultByMonth(rows, exclusions, pnlIncluded);

            LongBudgetSource source = new LongBudgetSource(
                detectedYear,
                List.copyOf(seenMonths.keySet()),
                income,
                expense,
                capex,
                depreciation,
                tax,
                financialResult,
                cashInflow,
                cashOutflow,
                financing,
                openingBalance,
                closingBalance,
                actualIncome,
                actualExpense,
                actualCapex,
                forecastIncome,
                forecastExpense,
                forecastCapex
            );
            List<RowAudit> audits = buildRowAudits(rows, exclusions, aggregationPolicy, pnlIncluded, cashflowIncluded, driverIncluded);
            List<ReconciliationCheck> reconciliations = buildReconciliations(source, rows, exclusions);
            BudgetLongInsightsDto insights = buildCanonicalLongInsights(sourceFile, source.orderedMonthKeys(), rows, exclusions, driverIncluded);
            BudgetExecutionDiagnostics diagnostics = buildExecutionDiagnostics(source, audits);
            return new CanonicalBudgetAnalysis(source, audits, reconciliations, insights, diagnostics);
        } catch (Exception ex) {
            return new CanonicalBudgetAnalysis(null, List.of(), List.of(), emptyCanonicalInsights(sourceFile), emptyDiagnostics());
        }
    }

    public record LongBudgetSource(Integer comparisonYear,
                                   List<String> orderedMonthKeys,
                                   Map<String, BigDecimal> plannedIncomeByMonth,
                                   Map<String, BigDecimal> plannedExpenseByMonth,
                                   Map<String, BigDecimal> plannedCapexByMonth,
                                   Map<String, BigDecimal> plannedDepreciationByMonth,
                                   Map<String, BigDecimal> plannedTaxByMonth,
                                   Map<String, BigDecimal> plannedFinancialResultByMonth,
                                   Map<String, BigDecimal> plannedCashInflowByMonth,
                                   Map<String, BigDecimal> plannedCashOutflowByMonth,
                                   Map<String, BigDecimal> plannedFinancingByMonth,
                                   Map<String, BigDecimal> plannedOpeningBalanceByMonth,
                                   Map<String, BigDecimal> plannedClosingBalanceByMonth,
                                   Map<String, BigDecimal> actualIncomeByMonth,
                                   Map<String, BigDecimal> actualExpenseByMonth,
                                   Map<String, BigDecimal> actualCapexByMonth,
                                   Map<String, BigDecimal> forecastIncomeByMonth,
                                   Map<String, BigDecimal> forecastExpenseByMonth,
                                   Map<String, BigDecimal> forecastCapexByMonth) {}

    public record BudgetPdfBundle(BudgetSummaryDto summary,
                                  BudgetLongInsightsDto longInsights) {}

    record CanonicalBudgetAnalysis(LongBudgetSource source,
                                   List<RowAudit> rowAudits,
                                   List<ReconciliationCheck> reconciliations,
                                   BudgetLongInsightsDto insights,
                                   BudgetExecutionDiagnostics diagnostics) {}

    record RowAudit(String sourceFile,
                    String sourceSheet,
                    Integer sourceRow,
                    String blockId,
                    String originalLabel,
                    String normalizedLabel,
                    String accountingCode,
                    String rowType,
                    String semanticKind,
                    String financialNature,
                    String cashflowNature,
                    String sectionKind,
                    String mappingStatus,
                    String confidence,
                    String monthKey,
                    BigDecimal plannedAmount,
                    boolean includedInPnL,
                    boolean includedInCashflow,
                    boolean includedInDrivers,
                    String aggregationPolicy,
                    String exclusionReason,
                    String canonicalIdentity) {}

    record ReconciliationCheck(String code,
                               boolean passed,
                               BigDecimal expectedValue,
                               BigDecimal actualValue,
                               BigDecimal tolerance,
                               String warning) {}

    record BudgetExecutionDiagnostics(int normalizedRows,
                                      int detailRows,
                                      int subtotalRows,
                                      int totalRows,
                                      int derivedRows,
                                      int reviewRows,
                                      int pnlRows,
                                      int cashflowRows,
                                      int driverEligibleRows,
                                      int aggregateRowsExcluded,
                                      int duplicateRowsExcluded,
                                      int noActivityRowsExcluded,
                                      BigDecimal revenueTotal,
                                      BigDecimal opexTotal,
                                      BigDecimal ebitdaTotal,
                                      BigDecimal ebitTotal,
                                      BigDecimal financialResultTotal,
                                      BigDecimal netResultTotal,
                                      BigDecimal endingBalanceTotal) {}

    private record CanonicalRow(int index,
                                String sourceFile,
                                String sourceSheet,
                                Integer sourceRow,
                                String blockId,
                                String originalLabel,
                                String normalizedLabel,
                                String accountingCode,
                                String rowType,
                                String semanticKind,
                                String effectiveFinancialKind,
                                String effectiveCashflowKind,
                                String sectionKind,
                                String mappingStatus,
                                String confidence,
                                String monthKey,
                                BigDecimal plannedAmount,
                                BigDecimal actualAmount,
                                BigDecimal forecastAmount,
                                BigDecimal varianceAmount) {
        String canonicalIdentity() {
            String codePart = normalizeIdentityCode(accountingCode);
            String labelPart = normalizeIdentityLabel(normalizedLabel);
            return sectionKind + "|" + effectiveFinancialKind + "|" + effectiveCashflowKind + "|" + codePart + "|" + labelPart;
        }

        String financialIdentity() {
            String codeFamily = accountingCodeFamily(accountingCode);
            if (!codeFamily.isBlank()) {
                return effectiveFinancialKind + "|" + codeFamily;
            }
            String labelPart = normalizeIdentityLabel(normalizedLabel);
            return effectiveFinancialKind + "||" + labelPart;
        }

        String duplicateKey() {
            return canonicalIdentity() + "|" + rowType + "|" + monthKey + "|" + normalizeAmount(plannedAmount);
        }
    }

    @FunctionalInterface
    private interface AmountConsumer {
        void accept(BigDecimal value);
    }

    private static void applyPreferredRows(List<CanonicalRow> rows,
                                           Map<Integer, String> exclusions,
                                           Map<Integer, String> aggregationPolicy,
                                           Map<Integer, Boolean> inclusionTarget,
                                           String monthKey,
                                           String semanticKind,
                                           String sectionKind,
                                           boolean useFinancialSemantic,
                                           Set<String> pnlDetailFinancialIdentities,
                                           AmountConsumer plannedCollector,
                                           AmountConsumer actualCollector,
                                           AmountConsumer forecastCollector,
                                           Map<String, Boolean> zeroSeriesIdentity) {
        List<CanonicalRow> detail = new ArrayList<>();
        List<CanonicalRow> aggregate = new ArrayList<>();
        List<CanonicalRow> fallbackDetail = new ArrayList<>();
        List<CanonicalRow> fallbackAggregate = new ArrayList<>();
        for (CanonicalRow row : rows) {
            if (!Objects.equals(row.monthKey(), monthKey)) continue;
            if (!semanticKind.equals(useFinancialSemantic ? row.effectiveFinancialKind() : row.effectiveCashflowKind())) continue;
            if ("REVIEW".equals(row.mappingStatus())) continue;
            if ("DERIVED_KPI".equals(row.rowType())) continue;
            if (Boolean.TRUE.equals(zeroSeriesIdentity.get(row.canonicalIdentity()))) continue;
            if (exclusions.containsKey(row.index())) continue;
            boolean primarySection = matchesSection(row.sectionKind(), sectionKind);
            boolean fallbackFinancialRow = useFinancialSemantic
                && "P_AND_L".equalsIgnoreCase(sectionKind)
                && !"REVENUE".equalsIgnoreCase(semanticKind)
                && !"FINANCING".equalsIgnoreCase(semanticKind)
                && matchesSection(row.sectionKind(), "CASHFLOW")
                && !pnlDetailFinancialIdentities.contains(row.financialIdentity());
            if (!primarySection && !fallbackFinancialRow) continue;
            if ("DETAIL".equals(row.rowType())) {
                if (primarySection) {
                    detail.add(row);
                } else {
                    fallbackDetail.add(row);
                }
            } else {
                if (primarySection) {
                    aggregate.add(row);
                } else {
                    fallbackAggregate.add(row);
                }
            }
        }

        if (!detail.isEmpty() || !fallbackDetail.isEmpty()) {
            for (CanonicalRow row : detail) {
                inclusionTarget.put(row.index(), true);
                aggregationPolicy.put(row.index(), "SUM_DETAIL");
                if (plannedCollector != null) plannedCollector.accept(adjustAmountForAggregation(row, row.plannedAmount(), semanticKind, useFinancialSemantic));
                if (actualCollector != null) actualCollector.accept(adjustAmountForAggregation(row, row.actualAmount(), semanticKind, useFinancialSemantic));
                if (forecastCollector != null) forecastCollector.accept(adjustAmountForAggregation(row, row.forecastAmount(), semanticKind, useFinancialSemantic));
            }
            for (CanonicalRow row : fallbackDetail) {
                inclusionTarget.put(row.index(), true);
                aggregationPolicy.put(row.index(), "SUM_CASHFLOW_DETAIL_FALLBACK");
                if (plannedCollector != null) plannedCollector.accept(adjustAmountForAggregation(row, row.plannedAmount(), semanticKind, useFinancialSemantic));
                if (actualCollector != null) actualCollector.accept(adjustAmountForAggregation(row, row.actualAmount(), semanticKind, useFinancialSemantic));
                if (forecastCollector != null) forecastCollector.accept(adjustAmountForAggregation(row, row.forecastAmount(), semanticKind, useFinancialSemantic));
            }
            for (CanonicalRow row : aggregate) {
                exclusions.putIfAbsent(row.index(), "EXCLUDED_AGGREGATE_WITH_DETAIL");
            }
            for (CanonicalRow row : fallbackAggregate) {
                exclusions.putIfAbsent(row.index(), "EXCLUDED_AGGREGATE_WITH_DETAIL");
            }
            return;
        }

        List<CanonicalRow> eligibleAggregate = !aggregate.isEmpty() ? aggregate : fallbackAggregate;
        if (eligibleAggregate.isEmpty()) return;
        CanonicalRow selected = eligibleAggregate.get(eligibleAggregate.size() - 1);
        inclusionTarget.put(selected.index(), true);
        aggregationPolicy.put(selected.index(), matchesSection(selected.sectionKind(), "CASHFLOW")
            ? "USED_AS_CASHFLOW_AGGREGATE_FALLBACK"
            : "USED_AS_AGGREGATE_FALLBACK");
        if (plannedCollector != null) plannedCollector.accept(adjustAmountForAggregation(selected, selected.plannedAmount(), semanticKind, useFinancialSemantic));
        if (actualCollector != null) actualCollector.accept(adjustAmountForAggregation(selected, selected.actualAmount(), semanticKind, useFinancialSemantic));
        if (forecastCollector != null) forecastCollector.accept(adjustAmountForAggregation(selected, selected.forecastAmount(), semanticKind, useFinancialSemantic));
        for (CanonicalRow row : eligibleAggregate) {
            if (row.index() == selected.index()) continue;
            exclusions.putIfAbsent(row.index(), "EXCLUDED_DUPLICATE_CANONICAL_ROW");
        }
    }

    private static void applyLastValueRows(List<CanonicalRow> rows,
                                           Map<Integer, String> exclusions,
                                           Map<Integer, String> aggregationPolicy,
                                           Map<Integer, Boolean> inclusionTarget,
                                           String monthKey,
                                           String semanticKind,
                                           Map<String, BigDecimal> target,
                                           Map<String, Boolean> zeroSeriesIdentity) {
        CanonicalRow selected = null;
        for (CanonicalRow row : rows) {
            if (!Objects.equals(row.monthKey(), monthKey)) continue;
            if (!matchesSection(row.sectionKind(), "CASHFLOW")) continue;
            if (!semanticKind.equals(row.effectiveCashflowKind())) continue;
            if ("REVIEW".equals(row.mappingStatus())) continue;
            if (Boolean.TRUE.equals(zeroSeriesIdentity.get(row.canonicalIdentity()))) continue;
            if (exclusions.containsKey(row.index())) continue;
            selected = row;
        }
        if (selected == null) return;
        inclusionTarget.put(selected.index(), true);
        aggregationPolicy.put(selected.index(), "LAST_VALUE");
        putLastValue(target, monthKey, selected.plannedAmount());
        for (CanonicalRow row : rows) {
            if (!Objects.equals(row.monthKey(), monthKey)) continue;
            if (!matchesSection(row.sectionKind(), "CASHFLOW")) continue;
            if (!semanticKind.equals(row.effectiveCashflowKind())) continue;
            if (row.index() == selected.index()) continue;
            exclusions.putIfAbsent(row.index(), "EXCLUDED_DUPLICATE_CANONICAL_ROW");
        }
    }

    private static Map<String, Boolean> detectZeroSeries(List<CanonicalRow> rows) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (CanonicalRow row : rows) {
            BigDecimal amount = firstNonNullAmount(row.plannedAmount(), row.actualAmount(), row.forecastAmount(), row.varianceAmount());
            if (amount == null) continue;
            totals.merge(row.canonicalIdentity(), amount.abs(), BigDecimal::add);
        }
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (var entry : totals.entrySet()) {
            out.put(entry.getKey(), entry.getValue().compareTo(BigDecimal.ZERO) == 0);
        }
        return out;
    }

    private static void markDuplicates(List<CanonicalRow> rows, Map<Integer, String> exclusions) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (CanonicalRow row : rows) {
            if (!supportsSemantic(row.effectiveFinancialKind(), row.effectiveCashflowKind())) continue;
            Integer previous = seen.putIfAbsent(row.duplicateKey(), row.index());
            if (previous != null) {
                exclusions.put(row.index(), "EXCLUDED_DUPLICATE_CANONICAL_ROW");
            }
        }
    }

    private static Set<String> collectPnlDetailFinancialIdentities(List<CanonicalRow> rows, Map<Integer, String> exclusions) {
        Set<String> identities = new java.util.LinkedHashSet<>();
        for (CanonicalRow row : rows) {
            if (exclusions.containsKey(row.index())) continue;
            if (!"DETAIL".equals(row.rowType())) continue;
            if (!matchesSection(row.sectionKind(), "P_AND_L")) continue;
            if (!isFinancialSemantic(row.effectiveFinancialKind())) continue;
            identities.add(row.financialIdentity());
        }
        return identities;
    }

    private static void markHierarchicalBreakdowns(List<CanonicalRow> rows, Map<Integer, String> exclusions) {
        record FamilyKey(String sectionKind, String semanticKind, String monthKey, String family) {}

        Map<FamilyKey, List<CanonicalRow>> grouped = new LinkedHashMap<>();
        for (CanonicalRow row : rows) {
            if (!"DETAIL".equals(row.rowType())) continue;
            if (!matchesSection(row.sectionKind(), "P_AND_L")) continue;
            if (!isSupportedFinancialSemantic(row.effectiveFinancialKind())) continue;
            String family = accountingCodeFamily(row.accountingCode());
            if (family.isBlank()) continue;
            grouped.computeIfAbsent(
                new FamilyKey(row.sectionKind(), row.effectiveFinancialKind(), row.monthKey(), family),
                ignored -> new ArrayList<>()
            ).add(row);
        }

        for (List<CanonicalRow> familyRows : grouped.values()) {
            CanonicalRow parent = null;
            List<CanonicalRow> children = new ArrayList<>();
            for (CanonicalRow row : familyRows) {
                String code = upper(row.accountingCode());
                String family = accountingCodeFamily(row.accountingCode());
                if (code.equals(family)) {
                    parent = row;
                } else if (!family.isBlank() && code.startsWith(family)) {
                    children.add(row);
                }
            }
            if (parent == null || children.isEmpty()) continue;

            BigDecimal parentAmount = firstNonNullAmount(parent.plannedAmount(), parent.actualAmount(), parent.forecastAmount(), parent.varianceAmount());
            List<CanonicalRow> breakdownRows = new ArrayList<>(children);
            Integer parentSourceRow = parent.sourceRow();
            Integer lastChildSourceRow = children.stream()
                .map(CanonicalRow::sourceRow)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(null);
            if (parentSourceRow != null && lastChildSourceRow != null) {
                for (CanonicalRow row : rows) {
                    if (row.index() == parent.index()) continue;
                    if (!"DETAIL".equals(row.rowType())) continue;
                    if (!matchesSection(row.sectionKind(), parent.sectionKind())) continue;
                    if (!upper(row.effectiveFinancialKind()).equals(upper(parent.effectiveFinancialKind()))) continue;
                    if (!Objects.equals(row.monthKey(), parent.monthKey())) continue;
                    Integer sourceRow = row.sourceRow();
                    if (sourceRow == null || sourceRow <= parentSourceRow || sourceRow >= lastChildSourceRow) continue;
                    if (row.accountingCode() != null && !row.accountingCode().isBlank()) continue;
                    breakdownRows.add(row);
                }
            }
            BigDecimal childrenAmount = sumCanonicalAmounts(breakdownRows);
            if (parentAmount == null) continue;
            if (parentAmount.subtract(childrenAmount).abs().compareTo(new BigDecimal("0.50")) > 0) continue;

            for (CanonicalRow child : breakdownRows) {
                exclusions.putIfAbsent(child.index(), "EXCLUDED_CHILD_BREAKDOWN_UNDER_PARENT");
            }
        }

        for (CanonicalRow parent : rows) {
            if (exclusions.containsKey(parent.index())) continue;
            if (!"DETAIL".equals(parent.rowType())) continue;
            if (!matchesSection(parent.sectionKind(), "P_AND_L")) continue;
            if (!isSupportedFinancialSemantic(parent.effectiveFinancialKind())) continue;
            String compactCode = compactDigits(parent.accountingCode());
            if (compactCode.length() < 3 || !compactCode.endsWith("0")) continue;

            String prefix = compactCode.replaceFirst("0+$", "");
            if (prefix.length() < 2) continue;

            List<CanonicalRow> children = new ArrayList<>();
            for (CanonicalRow row : rows) {
                if (row.index() == parent.index()) continue;
                if (exclusions.containsKey(row.index())) continue;
                if (!"DETAIL".equals(row.rowType())) continue;
                if (!matchesSection(row.sectionKind(), parent.sectionKind())) continue;
                if (!upper(row.effectiveFinancialKind()).equals(upper(parent.effectiveFinancialKind()))) continue;
                if (!Objects.equals(row.monthKey(), parent.monthKey())) continue;
                String childCode = compactDigits(row.accountingCode());
                if (childCode.length() <= compactCode.length() || childCode.equals(compactCode)) continue;
                if (!childCode.startsWith(prefix)) continue;
                children.add(row);
            }
            if (children.isEmpty()) continue;

            BigDecimal parentAmount = firstNonNullAmount(parent.plannedAmount(), parent.actualAmount(), parent.forecastAmount(), parent.varianceAmount());
            BigDecimal childrenAmount = sumCanonicalAmounts(children);
            if (parentAmount == null) continue;
            if (parentAmount.subtract(childrenAmount).abs().compareTo(new BigDecimal("0.50")) > 0) continue;

            exclusions.putIfAbsent(parent.index(), "EXCLUDED_PARENT_AGGREGATE_WITH_BREAKDOWN");
        }

        for (int i = 0; i < rows.size(); i++) {
            CanonicalRow parent = rows.get(i);
            if (exclusions.containsKey(parent.index())) continue;
            if (!"DETAIL".equals(parent.rowType())) continue;
            if (!matchesSection(parent.sectionKind(), "P_AND_L")) continue;
            if (!isSupportedFinancialSemantic(parent.effectiveFinancialKind())) continue;
            if (parent.accountingCode() != null && !parent.accountingCode().isBlank()) continue;

            BigDecimal parentAmount = firstNonNullAmount(parent.plannedAmount(), parent.actualAmount(), parent.forecastAmount(), parent.varianceAmount());
            if (parentAmount == null) continue;

            Integer parentSourceRow = parent.sourceRow();
            List<CanonicalRow> codedFollowers = new ArrayList<>();
            for (int j = i + 1; j < rows.size(); j++) {
                CanonicalRow child = rows.get(j);
                if (!"DETAIL".equals(child.rowType())) break;
                if (!matchesSection(child.sectionKind(), parent.sectionKind())) break;
                if (!upper(child.effectiveFinancialKind()).equals(upper(parent.effectiveFinancialKind()))) break;
                if (!Objects.equals(child.monthKey(), parent.monthKey())) break;
                if (parentSourceRow != null && child.sourceRow() != null && child.sourceRow() - parentSourceRow > 8) break;
                if (child.accountingCode() == null || child.accountingCode().isBlank()) continue;
                codedFollowers.add(child);
            }
            if (codedFollowers.isEmpty()) continue;

            BigDecimal breakdownAmount = sumCanonicalAmounts(codedFollowers);
            if (parentAmount.subtract(breakdownAmount).abs().compareTo(new BigDecimal("0.50")) > 0) continue;

            for (CanonicalRow child : codedFollowers) {
                exclusions.putIfAbsent(child.index(), "EXCLUDED_CHILD_BREAKDOWN_UNDER_PARENT");
            }
        }
    }

    private static Map<String, BigDecimal> rebuildFinancialResultByMonth(List<CanonicalRow> rows,
                                                                         Map<Integer, String> exclusions,
                                                                         Map<Integer, Boolean> pnlIncluded) {
        Map<String, BigDecimal> rebuilt = new LinkedHashMap<>();
        if (rows == null || rows.isEmpty()) {
            return rebuilt;
        }
        for (CanonicalRow row : rows) {
            if (!Boolean.TRUE.equals(pnlIncluded.get(row.index()))) continue;
            if (exclusions.containsKey(row.index())) continue;
            if (!matchesSection(row.sectionKind(), "P_AND_L")) continue;
            if (!"FINANCING".equalsIgnoreCase(row.effectiveFinancialKind())) continue;
            BigDecimal amount = firstNonNullAmount(row.plannedAmount(), row.actualAmount(), row.forecastAmount(), row.varianceAmount());
            if (amount == null) continue;
            rebuilt.merge(row.monthKey(), signedFinancialValue(row, amount), BigDecimal::add);
        }
        return rebuilt;
    }

    private static BigDecimal sumCanonicalAmounts(List<CanonicalRow> rows) {
        BigDecimal total = BigDecimal.ZERO;
        for (CanonicalRow row : rows) {
            BigDecimal amount = firstNonNullAmount(row.plannedAmount(), row.actualAmount(), row.forecastAmount(), row.varianceAmount());
            if (amount != null) {
                total = total.add(amount);
            }
        }
        return total;
    }

    private static String compactDigits(String code) {
        if (code == null) return "";
        return code.replaceAll("\\D", "");
    }

    private static boolean supportsSemantic(String financialNature, String cashflowNature) {
        return isSupportedFinancialSemantic(financialNature) || isSupportedCashflowSemantic(cashflowNature);
    }

    private static boolean isSupportedFinancialSemantic(String semanticKind) {
        return List.of("REVENUE", "OPEX", "OPERATING_ADJUSTMENT", "CAPEX", "DEPRECIATION_AMORTIZATION", "FINANCING", "TAX").contains(upper(semanticKind));
    }

    private static boolean isSupportedCashflowSemantic(String semanticKind) {
        return List.of("CASH_INFLOW", "CASH_OUTFLOW", "FINANCING", "OPENING_BALANCE", "CLOSING_BALANCE").contains(upper(semanticKind));
    }

    private static boolean isFinancialSemantic(String semanticKind) {
        return List.of("REVENUE", "OPEX", "OPERATING_ADJUSTMENT", "CAPEX", "DEPRECIATION_AMORTIZATION", "TAX").contains(upper(semanticKind));
    }

    private static boolean isDriverRow(CanonicalRow row, Map<Integer, Boolean> pnlIncluded) {
        return Boolean.TRUE.equals(pnlIncluded.get(row.index()))
            && "DETAIL".equals(row.rowType())
            && "P_AND_L".equals(row.sectionKind())
            && !"REVIEW".equals(row.mappingStatus())
            && List.of("REVENUE", "OPEX").contains(upper(row.effectiveFinancialKind()));
    }

    private static List<RowAudit> buildRowAudits(List<CanonicalRow> rows,
                                                 Map<Integer, String> exclusions,
                                                 Map<Integer, String> aggregationPolicy,
                                                 Map<Integer, Boolean> pnlIncluded,
                                                 Map<Integer, Boolean> cashflowIncluded,
                                                 Map<Integer, Boolean> driverIncluded) {
        List<RowAudit> audits = new ArrayList<>();
        for (CanonicalRow row : rows) {
            audits.add(new RowAudit(
                row.sourceFile(),
                row.sourceSheet(),
                row.sourceRow(),
                row.blockId(),
                row.originalLabel(),
                row.normalizedLabel(),
                row.accountingCode(),
                row.rowType(),
                row.semanticKind(),
                row.effectiveFinancialKind(),
                row.effectiveCashflowKind(),
                row.sectionKind(),
                row.mappingStatus(),
                row.confidence(),
                row.monthKey(),
                row.plannedAmount(),
                Boolean.TRUE.equals(pnlIncluded.get(row.index())),
                Boolean.TRUE.equals(cashflowIncluded.get(row.index())),
                Boolean.TRUE.equals(driverIncluded.get(row.index())),
                aggregationPolicy.get(row.index()),
                exclusions.get(row.index()),
                row.canonicalIdentity()
            ));
        }
        return List.copyOf(audits);
    }

    private static List<ReconciliationCheck> buildReconciliations(LongBudgetSource source,
                                                                  List<CanonicalRow> rows,
                                                                  Map<Integer, String> exclusions) {
        if (source == null) return List.of();
        BigDecimal tolerance = new BigDecimal("0.50");
        List<ReconciliationCheck> checks = new ArrayList<>();

        addDeclaredVsMonthlyCheck(checks, "declared_revenue_vs_monthly", rows, exclusions, "REVENUE", source.plannedIncomeByMonth(), tolerance);
        addDeclaredVsMonthlyCheck(checks, "declared_opex_vs_monthly", rows, exclusions, "OPEX", source.plannedExpenseByMonth(), tolerance);
        addDeclaredVsMonthlyCheck(checks, "declared_capex_vs_monthly", rows, exclusions, "CAPEX", source.plannedCapexByMonth(), tolerance);
        addDeclaredVsMonthlyCheck(checks, "declared_depreciation_vs_monthly", rows, exclusions, "DEPRECIATION_AMORTIZATION", source.plannedDepreciationByMonth(), tolerance);
        addDeclaredVsMonthlyCheck(checks, "declared_financing_vs_monthly", rows, exclusions, "FINANCING", source.plannedFinancialResultByMonth(), tolerance);

        BigDecimal ebitdaComputed = total(source.plannedIncomeByMonth().values()).subtract(total(source.plannedExpenseByMonth().values()));
        BigDecimal ebitComputed = ebitdaComputed.subtract(total(source.plannedDepreciationByMonth().values()));
        BigDecimal netComputed = ebitComputed.add(total(source.plannedFinancialResultByMonth().values()));
        BigDecimal closingComputed = lastValue(source.plannedClosingBalanceByMonth().values());

        addLabelBasedCheck(checks, "declared_ebitda", rows, exclusions, "EBITDA", ebitdaComputed, tolerance, false);
        addLabelBasedCheck(checks, "declared_ebit", rows, exclusions, "EBIT", ebitComputed, tolerance, false);
        addLabelBasedCheck(checks, "declared_beneficio_neto", rows, exclusions, "BENEFICIO NETO", netComputed, tolerance, false);
        addLabelBasedCheck(checks, "declared_resultado_financiero", rows, exclusions, "RESULTADO FINANCIERO", total(source.plannedFinancialResultByMonth().values()), tolerance, false);
        addLabelBasedCheck(checks, "declared_saldo_final", rows, exclusions, "SALDO FINAL", closingComputed, tolerance, true);

        return List.copyOf(checks);
    }

    private static BudgetLongInsightsDto buildCanonicalLongInsights(String filename,
                                                                    List<String> orderedMonthKeys,
                                                                    List<CanonicalRow> rows,
                                                                    Map<Integer, String> exclusions,
                                                                    Map<Integer, Boolean> driverIncluded) {
        if (rows == null || rows.isEmpty() || orderedMonthKeys == null || orderedMonthKeys.isEmpty()) {
            return emptyCanonicalInsights(filename);
        }

        Map<String, BigDecimal> monthTotals = new LinkedHashMap<>();
        for (String monthKey : orderedMonthKeys) {
            monthTotals.put(monthKey, BigDecimal.ZERO);
        }

        Map<String, DriverAggregate> drivers = new LinkedHashMap<>();
        Map<String, DriverAggregate> accountingAdjustments = new LinkedHashMap<>();
        for (CanonicalRow row : rows) {
            BigDecimal amount = firstNonNullAmount(row.plannedAmount(), row.actualAmount(), row.forecastAmount(), row.varianceAmount());
            if (amount == null) continue;

            if (!exclusions.containsKey(row.index()) && Boolean.TRUE.equals(driverIncluded.get(row.index()))) {
                monthTotals.putIfAbsent(row.monthKey(), BigDecimal.ZERO);
                monthTotals.merge(row.monthKey(), amount, BigDecimal::add);

                DriverAggregate aggregate = drivers.computeIfAbsent(
                    row.canonicalIdentity(),
                    key -> new DriverAggregate(
                        row.accountingCode(),
                        row.originalLabel(),
                        row.normalizedLabel(),
                        row.semanticKind(),
                        upper(row.effectiveFinancialKind()),
                        upper(row.effectiveCashflowKind()),
                        row.rowType(),
                        row.sectionKind(),
                        row.mappingStatus(),
                        row.sourceRow(),
                        row.blockId(),
                        row.canonicalIdentity()
                    )
                );
                aggregate.months().merge(row.monthKey(), amount, BigDecimal::add);
            }

            if (isAccountingAdjustmentRow(row, exclusions)) {
                DriverAggregate aggregate = accountingAdjustments.computeIfAbsent(
                    row.canonicalIdentity(),
                    key -> new DriverAggregate(
                        row.accountingCode(),
                        row.originalLabel(),
                        row.normalizedLabel(),
                        row.semanticKind(),
                        upper(row.effectiveFinancialKind()),
                        upper(row.effectiveCashflowKind()),
                        row.rowType(),
                        row.sectionKind(),
                        row.mappingStatus(),
                        row.sourceRow(),
                        row.blockId(),
                        row.canonicalIdentity()
                    )
                );
                aggregate.months().merge(row.monthKey(), amount, BigDecimal::add);
            }
        }

        if (drivers.isEmpty() && accountingAdjustments.isEmpty()) {
            return emptyCanonicalInsights(filename);
        }

        List<DriverComputed> computed = new ArrayList<>();
        BigDecimal totalAbsAnnual = BigDecimal.ZERO;
        for (DriverAggregate aggregate : drivers.values()) {
            BigDecimal annual = BigDecimal.ZERO;
            int zeroMonths = 0;
            List<BigDecimal> orderedValues = new ArrayList<>();
            for (String monthKey : monthTotals.keySet()) {
                BigDecimal value = aggregate.months().getOrDefault(monthKey, BigDecimal.ZERO);
                orderedValues.add(value);
                annual = annual.add(value);
                if (value.compareTo(BigDecimal.ZERO) == 0) {
                    zeroMonths++;
                }
            }
            BigDecimal absAnnual = annual.abs();
            String zeroInterpretation = BudgetCanonicalClassifier.classifyZeroInterpretation(
                aggregate.semanticKind(),
                aggregate.label(),
                orderedValues
            );
            if (absAnnual.compareTo(BigDecimal.ZERO) > 0) {
                totalAbsAnnual = totalAbsAnnual.add(absAnnual);
            }
            computed.add(new DriverComputed(aggregate, annual, absAnnual, zeroMonths, zeroInterpretation));
        }
        List<DriverComputed> adjustmentComputed = new ArrayList<>();
        for (DriverAggregate aggregate : accountingAdjustments.values()) {
            BigDecimal annual = BigDecimal.ZERO;
            int zeroMonths = 0;
            List<BigDecimal> orderedValues = new ArrayList<>();
            for (String monthKey : monthTotals.keySet()) {
                BigDecimal value = aggregate.months().getOrDefault(monthKey, BigDecimal.ZERO);
                orderedValues.add(value);
                annual = annual.add(value);
                if (value.compareTo(BigDecimal.ZERO) == 0) {
                    zeroMonths++;
                }
            }
            BigDecimal absAnnual = annual.abs();
            String zeroInterpretation = BudgetCanonicalClassifier.classifyZeroInterpretation(
                aggregate.semanticKind(),
                aggregate.label(),
                orderedValues
            );
            adjustmentComputed.add(new DriverComputed(aggregate, annual, absAnnual, zeroMonths, zeroInterpretation));
        }

        computed.sort((left, right) -> {
            int byAbs = right.absAnnual().compareTo(left.absAnnual());
            if (byAbs != 0) return byAbs;
            return safe(left.aggregate().label()).compareToIgnoreCase(safe(right.aggregate().label()));
        });
        adjustmentComputed.sort((left, right) -> {
            int byAbs = right.absAnnual().compareTo(left.absAnnual());
            if (byAbs != 0) return byAbs;
            return safe(left.aggregate().label()).compareToIgnoreCase(safe(right.aggregate().label()));
        });

        BigDecimal totalAbsAnnualFinal = totalAbsAnnual;

        List<BudgetItemInsightDto> topDrivers = computed.stream()
            .filter(item -> item.absAnnual().compareTo(BigDecimal.ZERO) > 0)
            .limit(10)
            .map(item -> toBudgetItem(item, totalAbsAnnualFinal))
            .toList();

        List<BudgetItemInsightDto> zeroHeavy = computed.stream()
            .filter(item -> item.zeroMonths() >= 8)
            .filter(item -> !"NO_ACTIVITY".equals(item.zeroInterpretation()))
            .filter(item -> !"NOT_APPLICABLE".equals(item.zeroInterpretation()))
            .filter(item -> !"ACCOUNTING_ADJUSTMENT".equals(item.zeroInterpretation()))
            .sorted((left, right) -> {
                int byZeros = Integer.compare(right.zeroMonths(), left.zeroMonths());
                if (byZeros != 0) return byZeros;
                return right.absAnnual().compareTo(left.absAnnual());
            })
            .limit(15)
            .map(item -> toBudgetItem(item, totalAbsAnnualFinal))
            .toList();
        List<BudgetItemInsightDto> adjustments = adjustmentComputed.stream()
            .filter(item -> item.absAnnual().compareTo(BigDecimal.ZERO) > 0)
            .map(item -> toBudgetItem(item, BigDecimal.ZERO))
            .toList();

        String bestMonth = null;
        String worstMonth = null;
        BigDecimal best = null;
        BigDecimal worst = null;
        for (var entry : monthTotals.entrySet()) {
            BigDecimal total = entry.getValue();
            if (best == null || total.compareTo(best) > 0) {
                best = total;
                bestMonth = entry.getKey();
            }
            if (worst == null || total.compareTo(worst) < 0) {
                worst = total;
                worstMonth = entry.getKey();
            }
        }

        BigDecimal top3Abs = computed.stream()
            .filter(item -> item.absAnnual().compareTo(BigDecimal.ZERO) > 0)
            .limit(3)
            .map(DriverComputed::absAnnual)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<BudgetMonthTotalDto> monthTotalDtos = monthTotals.entrySet().stream()
            .map(entry -> new BudgetMonthTotalDto(
                entry.getKey(),
                MONTH_LABELS.getOrDefault(entry.getKey(), entry.getKey()),
                entry.getValue().setScale(2, RoundingMode.HALF_UP)
            ))
            .toList();

        return new BudgetLongInsightsDto(
            filename,
            null,
            null,
            null,
            drivers.size(),
            totalAbsAnnual.setScale(2, RoundingMode.HALF_UP),
            bestMonth,
            worstMonth,
            sharePct(top3Abs, totalAbsAnnualFinal),
            monthTotalDtos,
            topDrivers,
            zeroHeavy,
            adjustments
        );
    }

    private static BudgetItemInsightDto toBudgetItem(DriverComputed item, BigDecimal totalAbsAnnual) {
        return new BudgetItemInsightDto(
            item.aggregate().code(),
            item.aggregate().label(),
            item.aggregate().normalizedLabel(),
            item.aggregate().semanticKind(),
            item.aggregate().financialNature(),
            item.aggregate().cashflowNature(),
            item.annual().setScale(2, RoundingMode.HALF_UP),
            item.zeroMonths(),
            sharePct(item.absAnnual(), totalAbsAnnual),
            item.zeroInterpretation(),
            item.aggregate().rowType(),
            item.aggregate().sectionKind(),
            item.aggregate().mappingStatus(),
            item.aggregate().sourceRow(),
            item.aggregate().blockId(),
            null,
            item.aggregate().canonicalIdentity(),
            null
        );
    }

    private static boolean isAccountingAdjustmentRow(CanonicalRow row, Map<Integer, String> exclusions) {
        return row != null
            && !exclusions.containsKey(row.index())
            && "DETAIL".equals(row.rowType())
            && "P_AND_L".equals(row.sectionKind())
            && !"REVIEW".equals(row.mappingStatus())
            && "OPERATING_ADJUSTMENT".equals(upper(row.effectiveFinancialKind()));
    }

    private static BudgetItemDetailDto buildItemDetail(CanonicalBudgetAnalysis analysis,
                                                       Long sourceImportId,
                                                       String analysisVersion,
                                                       String requestedCanonicalRowId) {
        if (analysis == null || analysis.rowAudits() == null || analysis.rowAudits().isEmpty()) return null;
        DetailLookupMatch lookup = resolveDetailRows(analysis.rowAudits(), requestedCanonicalRowId, analysisVersion, sourceImportId);
        if (lookup == null || lookup.rows().isEmpty()) return null;

        List<RowAudit> rows = lookup.rows();
        RowAudit first = rows.get(0);
        List<Integer> sourceRows = rows.stream()
            .map(RowAudit::sourceRow)
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
        Map<String, BigDecimal> monthMap = new LinkedHashMap<>();
        for (RowAudit row : rows) {
            mergeAmount(monthMap, row.monthKey(), row.plannedAmount());
        }
        List<BudgetItemDetailMonthDto> months = MONTH_KEYS.stream()
            .map(monthKey -> new BudgetItemDetailMonthDto(
                monthKey,
                MONTH_LABELS.getOrDefault(monthKey, monthKey),
                scale2(monthMap.getOrDefault(monthKey, BigDecimal.ZERO))
            ))
            .toList();
        List<String> warnings = rows.stream()
            .map(RowAudit::exclusionReason)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        String aggregationPolicy = rows.stream()
            .map(RowAudit::aggregationPolicy)
            .filter(Objects::nonNull)
            .distinct()
            .reduce((left, right) -> Objects.equals(left, right) ? left : "MIXED")
            .orElse("SUM_DETAIL");
        BigDecimal computedAnnualTotal = rows.stream()
            .map(RowAudit::plannedAmount)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new BudgetItemDetailDto(
            sourceImportId,
            analysisVersion,
            lookup.strategy(),
            buildCanonicalRowId(analysisVersion, sourceImportId, first.blockId(), first.sectionKind(), first.accountingCode(), first.normalizedLabel(), first.canonicalIdentity()),
            first.canonicalIdentity(),
            first.accountingCode(),
            first.originalLabel(),
            first.normalizedLabel(),
            first.rowType(),
            first.semanticKind(),
            first.financialNature(),
            first.cashflowNature(),
            first.sectionKind(),
            first.mappingStatus(),
            first.blockId(),
            aggregationPolicy,
            scale2(computedAnnualTotal),
            scale2(computedAnnualTotal),
            sourceRows.size(),
            sourceRows,
            warnings,
            months
        );
    }

    private static DetailLookupMatch resolveDetailRows(List<RowAudit> audits,
                                                       String requestedCanonicalRowId,
                                                       String analysisVersion,
                                                       Long sourceImportId) {
        if (audits == null || audits.isEmpty() || requestedCanonicalRowId == null || requestedCanonicalRowId.isBlank()) return null;
        String requestedIdentity = canonicalIdentityFromRowId(requestedCanonicalRowId);
        List<RowAudit> exact = audits.stream()
            .filter(BudgetService::isDetailEligibleAudit)
            .filter(row -> requestedCanonicalRowId.equals(buildCanonicalRowId(analysisVersion, sourceImportId, row.blockId(), row.sectionKind(), row.accountingCode(), row.normalizedLabel(), row.canonicalIdentity())))
            .toList();
        if (!exact.isEmpty()) return new DetailLookupMatch("CANONICAL_ROW_ID", exact);

        RowAudit requestedProbe = audits.stream()
            .filter(BudgetService::isDetailEligibleAudit)
            .filter(row -> requestedIdentity.equals(row.canonicalIdentity()))
            .findFirst()
            .orElse(null);

        if (requestedProbe != null) {
            List<RowAudit> byBlockAndCode = audits.stream()
                .filter(BudgetService::isDetailEligibleAudit)
                .filter(row -> Objects.equals(row.blockId(), requestedProbe.blockId()))
                .filter(row -> Objects.equals(normalizeIdentityCode(row.accountingCode()), normalizeIdentityCode(requestedProbe.accountingCode())))
                .toList();
            if (!byBlockAndCode.isEmpty()) return new DetailLookupMatch("BLOCK_CODE", byBlockAndCode);

            List<RowAudit> byBlockAndLabel = audits.stream()
                .filter(BudgetService::isDetailEligibleAudit)
                .filter(row -> Objects.equals(row.blockId(), requestedProbe.blockId()))
                .filter(row -> Objects.equals(normalizeIdentityLabel(row.normalizedLabel()), normalizeIdentityLabel(requestedProbe.normalizedLabel())))
                .toList();
            if (!byBlockAndLabel.isEmpty()) return new DetailLookupMatch("BLOCK_LABEL", byBlockAndLabel);
        }

        List<RowAudit> byIdentity = audits.stream()
            .filter(BudgetService::isDetailEligibleAudit)
            .filter(row -> requestedIdentity.equals(row.canonicalIdentity()))
            .toList();
        if (!byIdentity.isEmpty()) return new DetailLookupMatch("CANONICAL_IDENTITY", byIdentity);
        return null;
    }

    private static boolean isDetailEligibleAudit(RowAudit row) {
        return row != null
            && "DETAIL".equals(row.rowType())
            && !"REVIEW".equals(row.mappingStatus())
            && row.exclusionReason() == null
            && row.plannedAmount() != null;
    }

    private record DetailLookupMatch(String strategy, List<RowAudit> rows) {}

    private static BudgetExecutionDiagnostics buildExecutionDiagnostics(LongBudgetSource source, List<RowAudit> audits) {
        if (source == null || audits == null) {
            return emptyDiagnostics();
        }
        int detailRows = 0;
        int subtotalRows = 0;
        int totalRows = 0;
        int derivedRows = 0;
        int reviewRows = 0;
        int pnlRows = 0;
        int cashflowRows = 0;
        int driverEligibleRows = 0;
        int aggregateRowsExcluded = 0;
        int duplicateRowsExcluded = 0;
        int noActivityRowsExcluded = 0;

        for (RowAudit audit : audits) {
            if ("DETAIL".equals(audit.rowType())) detailRows++;
            if ("SUBTOTAL".equals(audit.rowType())) subtotalRows++;
            if ("TOTAL".equals(audit.rowType())) totalRows++;
            if ("DERIVED_KPI".equals(audit.rowType())) derivedRows++;
            if ("REVIEW".equals(audit.mappingStatus())) reviewRows++;
            if ("P_AND_L".equals(audit.sectionKind())) pnlRows++;
            if ("CASHFLOW".equals(audit.sectionKind())) cashflowRows++;
            if (audit.includedInDrivers()) driverEligibleRows++;
            if ("EXCLUDED_AGGREGATE_WITH_DETAIL".equals(audit.exclusionReason())) aggregateRowsExcluded++;
            if ("EXCLUDED_DUPLICATE_CANONICAL_ROW".equals(audit.exclusionReason())) duplicateRowsExcluded++;
            if ("EXCLUDED_NO_ACTIVITY".equals(audit.exclusionReason())) noActivityRowsExcluded++;
        }

        BigDecimal revenueTotal = total(source.plannedIncomeByMonth().values()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal opexTotal = total(source.plannedExpenseByMonth().values()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal ebitdaTotal = revenueTotal.subtract(opexTotal).setScale(2, RoundingMode.HALF_UP);
        BigDecimal ebitTotal = ebitdaTotal.subtract(total(source.plannedDepreciationByMonth().values())).setScale(2, RoundingMode.HALF_UP);
        BigDecimal financialTotal = total(source.plannedFinancialResultByMonth().values()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal netTotal = ebitTotal.add(financialTotal).setScale(2, RoundingMode.HALF_UP);
        BigDecimal endingBalance = lastValue(source.plannedClosingBalanceByMonth().values()).setScale(2, RoundingMode.HALF_UP);

        return new BudgetExecutionDiagnostics(
            audits.size(),
            detailRows,
            subtotalRows,
            totalRows,
            derivedRows,
            reviewRows,
            pnlRows,
            cashflowRows,
            driverEligibleRows,
            aggregateRowsExcluded,
            duplicateRowsExcluded,
            noActivityRowsExcluded,
            revenueTotal,
            opexTotal,
            ebitdaTotal,
            ebitTotal,
            financialTotal,
            netTotal,
            endingBalance
        );
    }

    private static BigDecimal sharePct(BigDecimal part, BigDecimal total) {
        if (part == null) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (total == null || total.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return part
            .divide(total, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100))
            .setScale(2, RoundingMode.HALF_UP);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static BudgetLongInsightsDto emptyCanonicalInsights(String filename) {
        return new BudgetLongInsightsDto(
            filename,
            null,
            null,
            null,
            0,
            BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
            null,
            null,
            BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static BudgetLongInsightsDto attachAnalysisMetadata(BudgetLongInsightsDto insights, Long sourceImportId, String analysisVersion) {
        if (insights == null) return null;
        return new BudgetLongInsightsDto(
            insights.filename(),
            insights.createdAt(),
            sourceImportId,
            analysisVersion,
            insights.itemCount(),
            insights.totalAbsAnnual(),
            insights.bestMonth(),
            insights.worstMonth(),
            insights.concentrationTop3AbsPct(),
            insights.monthTotals(),
            enrichItems(insights.topDrivers(), sourceImportId, analysisVersion),
            enrichItems(insights.zeroHeavyItems(), sourceImportId, analysisVersion),
            enrichItems(insights.accountingAdjustments(), sourceImportId, analysisVersion)
        );
    }

    private static List<BudgetItemInsightDto> enrichItems(List<BudgetItemInsightDto> items, Long sourceImportId, String analysisVersion) {
        if (items == null || items.isEmpty()) return List.of();
        return items.stream()
            .map(item -> new BudgetItemInsightDto(
                item.code(),
                item.label(),
                item.normalizedLabel(),
                item.semanticKind(),
                item.financialNature(),
                item.cashflowNature(),
                item.annualTotal(),
                item.zeroMonths(),
                item.shareAbsPct(),
                item.zeroInterpretation(),
                item.rowType(),
                item.sectionKind(),
                item.mappingStatus(),
                item.sourceRow(),
                item.blockId(),
                item.exclusionReason(),
                item.canonicalIdentity(),
                buildCanonicalRowId(analysisVersion, sourceImportId, item.blockId(), item.sectionKind(), item.code(), item.normalizedLabel(), item.canonicalIdentity())
            ))
            .toList();
    }

    private static BudgetExecutionDiagnostics emptyDiagnostics() {
        BigDecimal zero = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return new BudgetExecutionDiagnostics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, zero, zero, zero, zero, zero, zero, zero);
    }

    private record DriverAggregate(String code,
                                   String label,
                                   String normalizedLabel,
                                   String semanticKind,
                                   String financialNature,
                                   String cashflowNature,
                                   String rowType,
                                   String sectionKind,
                                   String mappingStatus,
                                   Integer sourceRow,
                                   String blockId,
                                   String canonicalIdentity,
                                   Map<String, BigDecimal> months) {
        private DriverAggregate(String code,
                                String label,
                                String normalizedLabel,
                                String semanticKind,
                                String financialNature,
                                String cashflowNature,
                                String rowType,
                                String sectionKind,
                                String mappingStatus,
                                Integer sourceRow,
                                String blockId,
                                String canonicalIdentity) {
            this(code, label, normalizedLabel, semanticKind, financialNature, cashflowNature, rowType, sectionKind, mappingStatus, sourceRow, blockId, canonicalIdentity, new LinkedHashMap<>());
        }
    }

    private record DriverComputed(DriverAggregate aggregate,
                                  BigDecimal annual,
                                  BigDecimal absAnnual,
                                  int zeroMonths,
                                  String zeroInterpretation) {}

    private static void addDeclaredVsMonthlyCheck(List<ReconciliationCheck> checks,
                                                  String code,
                                                  List<CanonicalRow> rows,
                                                  Map<Integer, String> exclusions,
                                                  String semanticKind,
                                                  Map<String, BigDecimal> monthlySeries,
                                                  BigDecimal tolerance) {
        Map<String, CanonicalRow> selectedByMonth = new LinkedHashMap<>();
        for (CanonicalRow row : rows) {
            if (exclusions.containsKey(row.index())) continue;
            if ("DETAIL".equals(row.rowType()) || "DERIVED_KPI".equals(row.rowType())) continue;
            if (!(semanticKind.equals(row.effectiveFinancialKind()) || semanticKind.equals(row.effectiveCashflowKind()))) continue;
            CanonicalRow current = selectedByMonth.get(row.monthKey());
            if (current == null || aggregatePriority(row.rowType()) >= aggregatePriority(current.rowType())) {
                selectedByMonth.put(row.monthKey(), row);
            }
        }
        BigDecimal declared = selectedByMonth.values().stream()
            .map(CanonicalRow::plannedAmount)
            .filter(Objects::nonNull)
            .map(BigDecimal::abs)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (declared.compareTo(BigDecimal.ZERO) == 0) return;
        BigDecimal actual = total(monthlySeries.values());
        checks.add(reconciliation(code, declared, actual, tolerance, "El total declarado no cuadra con la suma mensual."));
    }

    private static int aggregatePriority(String rowType) {
        if ("TOTAL".equals(rowType)) return 3;
        if ("SUBTOTAL".equals(rowType)) return 2;
        if ("ASSUMPTION".equals(rowType)) return 1;
        return 0;
    }

    private static void addLabelBasedCheck(List<ReconciliationCheck> checks,
                                           String code,
                                           List<CanonicalRow> rows,
                                           Map<Integer, String> exclusions,
                                           String labelFragment,
                                           BigDecimal computed,
                                           BigDecimal tolerance,
                                           boolean lastValueOnly) {
        List<CanonicalRow> matchingRows = rows.stream()
            .filter(row -> !exclusions.containsKey(row.index()))
            .filter(row -> matchesAuditLabel(row.normalizedLabel(), labelFragment, lastValueOnly))
            .toList();
        BigDecimal declared = lastValueOnly
            ? matchingRows.stream().map(CanonicalRow::plannedAmount).filter(Objects::nonNull).reduce((first, second) -> second).orElse(null)
            : matchingRows.stream().map(CanonicalRow::plannedAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (declared == null) return;
        checks.add(reconciliation(code, declared, computed, tolerance, "La fila declarada no cuadra con la reconciliación calculada."));
    }

    private static ReconciliationCheck reconciliation(String code,
                                                       BigDecimal expected,
                                                       BigDecimal actual,
                                                       BigDecimal tolerance,
                                                       String warning) {
        BigDecimal left = expected == null ? BigDecimal.ZERO : expected;
        BigDecimal right = actual == null ? BigDecimal.ZERO : actual;
        boolean passed = left.subtract(right).abs().compareTo(tolerance) <= 0;
        return new ReconciliationCheck(code, passed, left.setScale(2, RoundingMode.HALF_UP), right.setScale(2, RoundingMode.HALF_UP), tolerance, passed ? null : warning);
    }

    private static BigDecimal lastValue(Iterable<BigDecimal> values) {
        BigDecimal last = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            if (value != null) last = value;
        }
        return last;
    }

    private static BigDecimal total(Iterable<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            if (value != null) {
                total = total.add(value);
            }
        }
        return total;
    }

    private static boolean matchesAuditLabel(String label, String target, boolean allowContains) {
        if (label == null || target == null) return false;
        String normalizedLabel = label.trim().toUpperCase(Locale.ROOT);
        String normalizedTarget = target.trim().toUpperCase(Locale.ROOT);
        return allowContains ? normalizedLabel.contains(normalizedTarget) : normalizedLabel.equals(normalizedTarget);
    }

    private static String normalizeAmount(BigDecimal amount) {
        return amount == null ? "" : amount.setScale(6, RoundingMode.HALF_UP).toPlainString();
    }

    private static String buildCanonicalRowId(String analysisVersion,
                                              Long sourceImportId,
                                              String blockId,
                                              String sectionKind,
                                              String accountingCode,
                                              String normalizedLabel,
                                              String canonicalIdentity) {
        return String.join("::",
            safeToken(analysisVersion),
            sourceImportId == null ? "" : String.valueOf(sourceImportId),
            safeToken(blockId),
            safeToken(sectionKind),
            normalizeIdentityCode(accountingCode),
            normalizeIdentityLabel(normalizedLabel),
            safeToken(canonicalIdentity)
        );
    }

    private static String canonicalIdentityFromRowId(String canonicalRowId) {
        if (canonicalRowId == null || canonicalRowId.isBlank()) return "";
        String[] parts = canonicalRowId.split("::", -1);
        return parts.length == 0 ? "" : parts[parts.length - 1];
    }

    private static String safeToken(String value) {
        return value == null ? "" : value;
    }

    private static String normalizeIdentityCode(String value) {
        if (value == null) return "";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        normalized = normalized.replaceAll("\\s+", "");
        normalized = normalized.replaceAll("[()\\[\\]]", "");
        normalized = normalized.replace('/', '-');
        normalized = normalized.replaceAll("-{2,}", "-");
        return normalized;
    }

    private static String normalizeIdentityLabel(String value) {
        if (value == null) return "";
        return BudgetSemanticResolver.normalize(value).trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeBudgetCode(String rawCode, String rawLabel) {
        String code = normalizeIdentityCode(rawCode);
        if (!code.isBlank()) {
            return code;
        }
        if (rawLabel == null || rawLabel.isBlank()) return null;
        java.util.regex.Matcher grouped = java.util.regex.Pattern.compile("\\((\\d{2,4}\\s*[-/]\\s*\\d{2,4})\\)").matcher(rawLabel);
        if (grouped.find()) {
            return normalizeIdentityCode(grouped.group(1));
        }
        java.util.regex.Matcher leading = java.util.regex.Pattern.compile("^\\s*(\\d{2,4}(?:\\s*[-/]\\s*\\d{1,4})?)\\b").matcher(rawLabel);
        if (leading.find()) {
            return normalizeIdentityCode(leading.group(1));
        }
        return null;
    }

    private static String normalizeBudgetLabel(String rawLabel, String normalizedCode) {
        if (rawLabel == null) return null;
        String label = rawLabel.trim().replaceAll("\\s+", " ");
        if (normalizedCode == null || normalizedCode.isBlank()) {
            return label;
        }
        label = label.replaceFirst("^\\s*\\(?\\Q" + normalizedCode.replace("-", "\\E\\s*[-/]\\s*\\Q") + "\\E\\)?\\s*", "");
        label = label.replaceFirst("^\\s*\\d{2,4}\\s*\\(\\Q" + normalizedCode.replace("-", "\\E\\s*[-/]\\s*\\Q") + "\\E\\)\\s*", "");
        label = label.trim();
        return label.isBlank() ? rawLabel.trim().replaceAll("\\s+", " ") : label;
    }

    private static BigDecimal scale2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static void mergeSemanticAmount(String rowType,
                                            String monthKey,
                                            BigDecimal value,
                                            Map<String, BigDecimal> detail,
                                            Map<String, BigDecimal> aggregate) {
        if (value == null || monthKey == null) return;
        if ("DETAIL".equals(rowType)) {
            detail.merge(monthKey, value, BigDecimal::add);
        } else {
            aggregate.put(monthKey, value);
        }
    }

    private static void mergeFlowAmount(String rowType,
                                        String monthKey,
                                        BigDecimal value,
                                        Map<String, BigDecimal> detail,
                                        Map<String, BigDecimal> aggregate) {
        if (value == null || monthKey == null) return;
        if ("DETAIL".equals(rowType)) {
            detail.merge(monthKey, value, BigDecimal::add);
        } else {
            aggregate.put(monthKey, value);
        }
    }

    private static void mergeAmount(Map<String, BigDecimal> target, String monthKey, BigDecimal value) {
        if (target == null || monthKey == null || value == null) return;
        target.merge(monthKey, value, BigDecimal::add);
    }

    private static BigDecimal adjustAmountForAggregation(CanonicalRow row,
                                                         BigDecimal value,
                                                         String semanticKind,
                                                         boolean useFinancialSemantic) {
        if (value == null) return null;
        if (!useFinancialSemantic && "FINANCING".equalsIgnoreCase(semanticKind)) {
            return signedFinancialValue(row, value);
        }
        return value;
    }

    private static BigDecimal signedFinancialValue(CanonicalRow row, BigDecimal value) {
        if (row == null || value == null) return value;
        String code = upper(row.accountingCode());
        String label = upper(row.normalizedLabel());
        if (code.startsWith("76")
            || label.contains("INGRESO FINANCI")
            || label.contains("INTEREST INCOME")
            || label.contains("RENDIMIENTO FINANCI")) {
            return value.abs();
        }
        if (code.startsWith("66")
            || code.startsWith("67")
            || label.contains("GASTO FINANCI")
            || label.contains("PERDIDA")
            || label.contains("LOSS")
            || label.contains("INTEREST EXPENSE")) {
            return value.abs().negate();
        }
        return value;
    }

    private static String accountingCodeFamily(String accountingCode) {
        if (accountingCode == null || accountingCode.isBlank()) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d{3,}").matcher(accountingCode);
        if (matcher.find()) {
            return matcher.group();
        }
        return accountingCode.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean matchesSection(String actualSection, String expectedSection) {
        if (expectedSection == null || expectedSection.isBlank()) return true;
        if (actualSection == null || actualSection.isBlank()) return false;
        return expectedSection.equalsIgnoreCase(actualSection.trim());
    }

    private static boolean isCanonicalBudgetCsv(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return false;
        String head = new String(bytes, 0, Math.min(bytes.length, 512), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        return head.contains("row_type") && head.contains("semantic_kind") && head.contains("month_key");
    }

    private static String confirmationMessage(BudgetLongNormalizer.Result result, String fallback) {
        if (result == null || result.mappingNotes() == null || result.mappingNotes().isEmpty()) return fallback;
        String detail = String.join(" | ", result.mappingNotes().stream().limit(4).toList());
        return fallback + " " + detail;
    }

    private static void putLastValue(Map<String, BigDecimal> target, String monthKey, BigDecimal value) {
        if (target == null || monthKey == null || value == null) return;
        target.put(monthKey, value);
    }

    private static Map<String, BigDecimal> preferSeries(Map<String, Boolean> seenMonths,
                                                        Map<String, BigDecimal> detail,
                                                        Map<String, BigDecimal> aggregate) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (String monthKey : seenMonths.keySet()) {
            if (detail.containsKey(monthKey)) {
                out.put(monthKey, detail.get(monthKey));
            } else if (aggregate.containsKey(monthKey)) {
                out.put(monthKey, aggregate.get(monthKey));
            }
        }
        return out;
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeMonthName(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim()
            .toUpperCase(Locale.ROOT)
            .replace('Á', 'A')
            .replace('É', 'E')
            .replace('Í', 'I')
            .replace('Ó', 'O')
            .replace('Ú', 'U');
        if (normalized.startsWith("ENE")) return "ENERO";
        if (normalized.startsWith("FEB")) return "FEBRERO";
        if (normalized.startsWith("MAR")) return "MARZO";
        if (normalized.startsWith("ABR")) return "ABRIL";
        if (normalized.startsWith("MAY")) return "MAYO";
        if (normalized.startsWith("JUN")) return "JUNIO";
        if (normalized.startsWith("JUL")) return "JULIO";
        if (normalized.startsWith("AGO")) return "AGOSTO";
        if (normalized.startsWith("SEP")) return "SEPTIEMBRE";
        if (normalized.startsWith("OCT")) return "OCTUBRE";
        if (normalized.startsWith("NOV")) return "NOVIEMBRE";
        if (normalized.startsWith("DIC")) return "DICIEMBRE";
        return MONTH_LABELS.containsKey(normalized) ? normalized : null;
    }

    private static String findHeader(List<String> headers, String... candidates) {
        if (headers == null || candidates == null) return null;
        for (String header : headers) {
            if (header == null) continue;
            String normalized = header.trim().toLowerCase(Locale.ROOT);
            for (String candidate : candidates) {
                if (candidate == null) continue;
                if (normalized.equals(candidate.toLowerCase(Locale.ROOT))) return header;
            }
        }
        return null;
    }

    private static String firstNonNull(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static BigDecimal firstNonNullAmount(BigDecimal... values) {
        if (values == null) return null;
        for (BigDecimal value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private static Integer parseYearFromMonthToken(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        if (trimmed.matches("^(19|20)\\d{2}[-/](0?[1-9]|1[0-2])$")) {
            return parseInteger(trimmed.substring(0, 4));
        }
        if (trimmed.matches("^(0?[1-9]|1[0-2])[-/](19|20)\\d{2}$")) {
            return parseInteger(trimmed.substring(trimmed.length() - 4));
        }
        return null;
    }

    private static String resolveCanonicalMonthKey(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        if (trimmed.matches("^(19|20)\\d{2}[-/](0?[1-9]|1[0-2])$")) {
            int monthNumber = Integer.parseInt(trimmed.substring(trimmed.length() - 2));
            return MONTH_KEYS.get(monthNumber - 1);
        }
        if (trimmed.matches("^(0?[1-9]|1[0-2])[-/](19|20)\\d{2}$")) {
            int monthNumber = Integer.parseInt(trimmed.substring(0, trimmed.indexOf('-') > 0 ? trimmed.indexOf('-') : trimmed.indexOf('/')));
            return MONTH_KEYS.get(monthNumber - 1);
        }
        return normalizeMonthName(trimmed);
    }

    private static Integer parseInteger(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean isTruthy(String raw) {
        if (raw == null) return false;
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("SI")
            || normalized.equals("SÍ")
            || normalized.equals("TRUE")
            || normalized.equals("1")
            || normalized.equals("YES")
            || normalized.equals("Y");
    }

    private static boolean matchesCashInflowRow(String upperLabel) {
        // TOTAL FACTURACIÓN / TOTAL FACTURACION / TOTAL COBROS
        return (upperLabel.contains("TOTAL") && (upperLabel.contains("FACTUR") || upperLabel.contains("COBRO")));
    }

    private static boolean matchesCashOutflowRow(String upperLabel) {
        // TOTAL GASTOS / TOTAL PAGOS
        return (upperLabel.contains("TOTAL") && (upperLabel.contains("GASTO") || upperLabel.contains("PAGO")));
    }

    private static String get(CSVRecord record, String header) {
        if (record == null || header == null) return null;
        try {
            if (!record.isMapped(header)) return null;
            return record.get(header);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String clean(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.isBlank() || "-".equals(s)) return null;
        return s;
    }

    private static BigDecimal parseMoney(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replace(" ", "");
        int comma = cleaned.lastIndexOf(',');
        int dot = cleaned.lastIndexOf('.');
        if (comma > dot) {
            cleaned = cleaned.replace(".", "").replace(",", ".");
        } else {
            cleaned = cleaned.replace(",", "");
        }
        cleaned = cleaned.replace("€", "");
        cleaned = cleaned.trim();
        if (cleaned.isBlank()) return null;
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BigDecimal firstMoney(CSVRecord record, Map<String, String> monthHeader, List<String> headers) {
        if (record == null) return null;
        // prefer any month cell that has a number, else scan other columns
        for (String mk : MONTH_KEYS) {
            String h = monthHeader.get(mk);
            if (h == null) continue;
            BigDecimal v = parseMoney(clean(get(record, h)));
            if (v != null) return v;
        }
        for (String h : headers) {
            if (h == null) continue;
            if (monthHeader.containsValue(h)) continue;
            BigDecimal v = parseMoney(clean(get(record, h)));
            if (v != null) return v;
        }
        return null;
    }

    private static char detectDelimiter(String head) {
        if (head == null) return ',';
        int commas = count(head, ',');
        int semis = count(head, ';');
        int tabs = count(head, '\t');
        int pipes = count(head, '|');
        int max = commas;
        char best = ',';
        if (semis > max) { max = semis; best = ';'; }
        if (tabs > max) { max = tabs; best = '\t'; }
        if (pipes > max) { max = pipes; best = '|'; }
        return best;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}

package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.BudgetMonthDto;
import com.asecon.enterpriseiq.dto.BudgetLongPreviewDto;
import com.asecon.enterpriseiq.dto.BudgetLongInsightsDto;
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

    public BudgetLongPreviewDto latestBudgetLongPreview(Long companyId) {
        UniversalImport imp = universalImportFileService.latest(companyId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No hay imports universales. Sube tu presupuesto (XLSX) a Universal."));

        byte[] bytes = universalImportFileService.latestNormalizedCsv(companyId);
        var result = BudgetLongNormalizer.normalizeToLongCsv(bytes, 10_000, 200);
        if (result.longCsvBytes().length == 0 || result.labelHeader() == null || result.monthKeys().isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "No pude detectar un presupuesto tabular (meses ENERO..DICIEMBRE). Usa Universal en modo guiado y elige hoja + fila de cabecera correcta."
            );
        }
        return new BudgetLongPreviewDto(
            imp.getFilename(),
            imp.getCreatedAt(),
            result.monthKeys(),
            result.labelHeader(),
            result.totalRowsProduced(),
            result.sampleRows()
        );
    }

    public byte[] latestBudgetLongCsv(Long companyId) {
        universalImportFileService.latest(companyId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No hay imports universales. Sube tu presupuesto (XLSX) a Universal."));
        byte[] bytes = universalImportFileService.latestNormalizedCsv(companyId);
        var result = BudgetLongNormalizer.normalizeToLongCsv(bytes, 50_000, 0);
        if (result.longCsvBytes().length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo normalizar el presupuesto a formato largo.");
        }
        return result.longCsvBytes();
    }

    public BudgetLongInsightsDto latestBudgetLongInsights(Long companyId) {
        UniversalImport imp = universalImportFileService.latest(companyId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No hay imports universales. Sube tu presupuesto (XLSX) a Universal."));
        byte[] bytes = universalImportFileService.latestNormalizedCsv(companyId);
        var insights = BudgetInsightsCalculator.compute(imp.getFilename(), imp.getCreatedAt(), bytes, 30_000);
        if (insights.itemCount() == 0 || insights.monthTotals().isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "No pude calcular insights (no detecto meses ENERO..DICIEMBRE o partidas). Usa Universal en modo guiado y elige hoja + fila de cabecera correcta."
            );
        }
        return insights;
    }

    public BudgetSummaryDto latestBudget(Long companyId) {
        UniversalImport imp = universalImportFileService.latest(companyId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No hay imports universales. Sube tu presupuesto (XLSX) a Universal."));

        byte[] bytes = universalImportFileService.latestNormalizedCsv(companyId);
        if (bytes == null || bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Import universal vacío.");
        }

        String head = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.UTF_8);
        int eol = head.indexOf('\n');
        if (eol >= 0) head = head.substring(0, eol);
        char delimiter = detectDelimiter(head);

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
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "No detecto meses (ENERO…DICIEMBRE) en el dataset. Usa el modo guiado y elige la fila de cabecera correcta."
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
                months,
                totalIncome.setScale(2, RoundingMode.HALF_UP),
                totalExpense.setScale(2, RoundingMode.HALF_UP),
                totalMargin,
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
        UniversalImport imp = universalImportFileService.latest(companyId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No hay imports universales. Sube tu presupuesto (XLSX) a Universal."));

        byte[] bytes = universalImportFileService.latestNormalizedCsv(companyId);
        if (bytes == null || bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Import universal vacío.");
        }

        String head = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.UTF_8);
        int eol = head.indexOf('\n');
        if (eol >= 0) head = head.substring(0, eol);
        char delimiter = detectDelimiter(head);

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
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "No detecto meses (ENERO…DICIEMBRE) en el dataset. Usa el modo guiado y elige la fila de cabecera correcta."
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

    private static boolean matchesIncomeRow(String upperLabel) {
        return upperLabel.contains("TOTAL") && upperLabel.contains("INGRES");
    }

    private static boolean matchesExpenseRow(String upperLabel) {
        return upperLabel.contains("GASTOS") && upperLabel.contains("EXPLOT");
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

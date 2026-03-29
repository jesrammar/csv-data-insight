package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.ImportPreviewDto;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ImportMappingService {
    private final TabularFileService tabularFileService;

    public ImportMappingService(TabularFileService tabularFileService) {
        this.tabularFileService = tabularFileService;
    }

    public ImportPreviewDto preview(MultipartFile file, TabularFileService.XlsxOptions xlsxOptions) throws IOException {
        TabularFileService.TabularCsv csv = tabularFileService.toCsv(file, xlsxOptions);
        byte[] bytes = csv.bytes();
        char delimiter = detectDelimiter(bytes);

        List<String> headers = new ArrayList<>();
        List<List<String>> sampleRows = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            CSVParser parser = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(reader);

            headers.addAll(parser.getHeaderMap().keySet());
            int maxRows = 8;
            for (CSVRecord record : parser) {
                List<String> row = new ArrayList<>(headers.size());
                for (String h : headers) {
                    String v = record.isMapped(h) ? record.get(h) : "";
                    row.add(v == null ? "" : v);
                }
                sampleRows.add(row);
                if (sampleRows.size() >= maxRows) break;
            }
        }

        Map<String, String> suggested = suggestMapping(headers, sampleRows);
        double confidence = estimateConfidence(suggested, headers, sampleRows);
        return new ImportPreviewDto(headers, sampleRows, suggested, confidence);
    }

    private static char detectDelimiter(byte[] bytes) {
        String head = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.UTF_8);
        int eol = head.indexOf('\n');
        if (eol >= 0) head = head.substring(0, eol);
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

    private static Map<String, String> suggestMapping(List<String> headers, List<List<String>> sampleRows) {
        Map<String, String> out = new HashMap<>();
        if (headers == null || headers.isEmpty()) return out;

        String date = bestMatch(headers, Set.of(
            "txn_date", "txndate", "date", "fecha", "fechaoperacion", "fecha_operacion", "foperacion", "f_operacion",
            "operationdate", "postingdate", "value_date", "valuedate"
        ));
        String amount = bestMatch(headers, Set.of(
            "amount", "importe", "total", "neto", "valor", "euros", "eur", "monto", "importe_total", "importe_total_eur"
        ));
        String description = bestMatch(headers, Set.of("description", "desc", "concepto", "detalle", "texto", "memo", "concept"));
        String counterparty = bestMatch(headers, Set.of("counterparty", "contrapartida", "proveedor", "cliente", "tercero", "beneficiario", "empresa"));
        String balanceEnd = bestMatch(headers, Set.of("balance_end", "saldo", "saldo_final", "ending_balance", "balance", "saldo_fin"));

        if (date != null) out.put("txn_date", date);
        if (amount != null) out.put("amount", amount);
        if (description != null) out.put("description", description);
        if (counterparty != null) out.put("counterparty", counterparty);
        if (balanceEnd != null) out.put("balance_end", balanceEnd);

        // Weak fallback: if nothing matched for amount, pick the most numeric column in sample.
        if (!out.containsKey("amount")) {
            String bestNumeric = mostNumericColumn(headers, sampleRows);
            if (bestNumeric != null) out.put("amount", bestNumeric);
        }

        // Weak fallback: if nothing matched for date, pick the most date-like column in sample.
        if (!out.containsKey("txn_date")) {
            String bestDate = mostDateLikeColumn(headers, sampleRows);
            if (bestDate != null) out.put("txn_date", bestDate);
        }

        return out;
    }

    private static String bestMatch(List<String> headers, Set<String> synonyms) {
        String best = null;
        int bestScore = -1;
        for (String h : headers) {
            String norm = normalizeHeader(h);
            int score = 0;
            for (String s : synonyms) {
                if (norm.equals(s)) score = Math.max(score, 4);
                else if (norm.contains(s)) score = Math.max(score, 2);
            }
            if (score > bestScore) {
                bestScore = score;
                best = h;
            }
        }
        return bestScore <= 0 ? null : best;
    }

    private static String normalizeHeader(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        s = s.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return s;
    }

    private static String mostNumericColumn(List<String> headers, List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) return null;
        int bestIdx = -1;
        double bestRatio = 0;
        for (int c = 0; c < headers.size(); c++) {
            int ok = 0;
            int total = 0;
            for (List<String> row : rows) {
                if (c >= row.size()) continue;
                String v = row.get(c);
                if (v == null || v.isBlank()) continue;
                total++;
                if (looksNumber(v)) ok++;
            }
            double ratio = total == 0 ? 0 : (double) ok / (double) total;
            if (ratio > bestRatio) {
                bestRatio = ratio;
                bestIdx = c;
            }
        }
        return bestRatio >= 0.6 && bestIdx >= 0 ? headers.get(bestIdx) : null;
    }

    private static String mostDateLikeColumn(List<String> headers, List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) return null;
        int bestIdx = -1;
        double bestRatio = 0;
        for (int c = 0; c < headers.size(); c++) {
            int ok = 0;
            int total = 0;
            for (List<String> row : rows) {
                if (c >= row.size()) continue;
                String v = row.get(c);
                if (v == null || v.isBlank()) continue;
                total++;
                if (looksDate(v)) ok++;
            }
            double ratio = total == 0 ? 0 : (double) ok / (double) total;
            if (ratio > bestRatio) {
                bestRatio = ratio;
                bestIdx = c;
            }
        }
        return bestRatio >= 0.6 && bestIdx >= 0 ? headers.get(bestIdx) : null;
    }

    private static boolean looksNumber(String v) {
        String s = v.trim();
        if (s.isEmpty()) return false;
        s = s.replace("€", "").replace(" ", "");
        s = s.replaceAll("[^0-9,\\.-]", "");
        if (s.isEmpty()) return false;
        int digits = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') digits++;
        }
        return digits >= 1;
    }

    private static boolean looksDate(String v) {
        String s = v.trim();
        if (s.isEmpty()) return false;
        // ISO yyyy-mm-dd
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) return true;
        // dd/mm/yyyy or d/m/yyyy
        if (s.matches("\\d{1,2}/\\d{1,2}/\\d{4}")) return true;
        // dd-mm-yyyy
        return s.matches("\\d{1,2}-\\d{1,2}-\\d{4}");
    }

    private static double estimateConfidence(Map<String, String> mapping, List<String> headers, List<List<String>> rows) {
        if (mapping == null || mapping.isEmpty()) return 0.0;
        double score = 0.0;
        if (mapping.containsKey("txn_date")) score += 0.35;
        if (mapping.containsKey("amount")) score += 0.35;
        if (mapping.containsKey("description")) score += 0.1;
        if (mapping.containsKey("counterparty")) score += 0.1;
        if (mapping.containsKey("balance_end")) score += 0.05;

        // Boost if sample values align.
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) idx.put(headers.get(i), i);
        score += sampleMatchBonus(mapping, idx, rows);
        return Math.max(0.0, Math.min(1.0, score));
    }

    private static double sampleMatchBonus(Map<String, String> mapping, Map<String, Integer> idx, List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) return 0.0;
        double bonus = 0.0;
        String dateH = mapping.get("txn_date");
        String amtH = mapping.get("amount");
        if (dateH != null && idx.containsKey(dateH)) {
            int c = idx.get(dateH);
            bonus += ratio(rows, c, ImportMappingService::looksDate) * 0.15;
        }
        if (amtH != null && idx.containsKey(amtH)) {
            int c = idx.get(amtH);
            bonus += ratio(rows, c, ImportMappingService::looksNumber) * 0.15;
        }
        return bonus;
    }

    private static double ratio(List<List<String>> rows, int col, java.util.function.Predicate<String> pred) {
        int ok = 0;
        int tot = 0;
        for (List<String> row : rows) {
            if (col >= row.size()) continue;
            String v = row.get(col);
            if (v == null || v.isBlank()) continue;
            tot++;
            if (pred.test(v)) ok++;
        }
        return tot == 0 ? 0.0 : (double) ok / (double) tot;
    }
}


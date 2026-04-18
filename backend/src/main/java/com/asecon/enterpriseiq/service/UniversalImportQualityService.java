package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.UniversalImportQualityDto;
import com.asecon.enterpriseiq.dto.UniversalSummaryDto;
import com.asecon.enterpriseiq.model.UniversalImport;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UniversalImportQualityService {
    private static final int MAX_ROWS = 20000;

    private final UniversalImportFileService universalImportFileService;
    private final UniversalCsvService universalCsvService;

    public UniversalImportQualityService(UniversalImportFileService universalImportFileService, UniversalCsvService universalCsvService) {
        this.universalImportFileService = universalImportFileService;
        this.universalCsvService = universalCsvService;
    }

    public UniversalImportQualityDto compute(Long companyId, Long importId) {
        if (companyId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta companyId");

        Optional<UniversalImport> impOpt = importId == null
            ? universalImportFileService.latest(companyId)
            : universalImportFileService.find(companyId, importId);
        UniversalImport imp = impOpt.orElse(null);
        if (imp == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No hay dataset Universal");

        UniversalSummaryDto summary = universalCsvService.summary(companyId, imp.getId()).orElse(null);

        Set<String> dateCols = new HashSet<>();
        Set<String> numberCols = new HashSet<>();
        if (summary != null && summary.columns() != null) {
            summary.columns().forEach((c) -> {
                if (c == null || c.name() == null) return;
                String t = c.detectedType() == null ? "" : c.detectedType().trim().toLowerCase(Locale.ROOT);
                if ("date".equals(t)) dateCols.add(c.name());
                if ("number".equals(t)) numberCols.add(c.name());
            });
        }

        byte[] bytes = universalImportFileService.normalizedCsv(companyId, imp.getId());
        if (bytes == null || bytes.length == 0) throw new ResponseStatusException(HttpStatus.GONE, "CSV normalizado no disponible");

        char delimiter = detectDelimiter(bytes);
        Stats s = scan(bytes, delimiter, dateCols, numberCols);
        List<UniversalImportQualityDto.Issue> issues = buildIssues(s);
        int score = computeScore(s, issues);
        String level = computeLevel(score, issues);

        return new UniversalImportQualityDto(
            imp.getId(),
            imp.getFilename(),
            s.rowsScanned,
            s.columns,
            s.irregularRows,
            s.nullCells,
            s.totalCells,
            s.dateParseErrors,
            s.numberParseErrors,
            s.minDate == null ? null : s.minDate.toString(),
            s.maxDate == null ? null : s.maxDate.toString(),
            score,
            level,
            issues,
            s.examples
        );
    }

    private static class Stats {
        long rowsScanned = 0;
        int columns = 0;
        long irregularRows = 0;
        long nullCells = 0;
        long totalCells = 0;
        long dateParseErrors = 0;
        long numberParseErrors = 0;
        LocalDate minDate = null;
        LocalDate maxDate = null;
        List<String> examples = new ArrayList<>();
    }

    private static Stats scan(byte[] bytes, char delimiter, Set<String> dateCols, Set<String> numberCols) {
        Stats s = new Stats();
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
            if (headers.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dataset sin cabeceras.");
            s.columns = headers.size();

            for (CSVRecord r : parser) {
                s.rowsScanned++;
                if (s.rowsScanned > MAX_ROWS) break;
                if (r.size() != headers.size()) s.irregularRows++;

                for (String h : headers) {
                    String raw = r.isMapped(h) ? r.get(h) : "";
                    String v = raw == null ? "" : raw.trim();
                    s.totalCells++;
                    if (v.isBlank()) {
                        s.nullCells++;
                        continue;
                    }

                    if (dateCols != null && dateCols.contains(h)) {
                        LocalDate d = UniversalViewService.parseFlexibleDate(v);
                        if (d == null) {
                            s.dateParseErrors++;
                            addExample(s, "Fecha inválida (" + h + "): " + truncate(v, 28));
                        } else {
                            if (s.minDate == null || d.isBefore(s.minDate)) s.minDate = d;
                            if (s.maxDate == null || d.isAfter(s.maxDate)) s.maxDate = d;
                        }
                    } else if (numberCols != null && numberCols.contains(h)) {
                        BigDecimal n = UniversalViewService.parseDecimal(v);
                        if (n == null) {
                            s.numberParseErrors++;
                            addExample(s, "Número inválido (" + h + "): " + truncate(v, 28));
                        }
                    }
                }
            }

            return s;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo calcular la calidad del dataset.");
        }
    }

    private static List<UniversalImportQualityDto.Issue> buildIssues(Stats s) {
        List<UniversalImportQualityDto.Issue> out = new ArrayList<>();
        if (s.rowsScanned <= 0) {
            out.add(new UniversalImportQualityDto.Issue("HIGH", "EMPTY", "Sin filas", "No se detectaron filas válidas en el CSV normalizado."));
            return out;
        }

        double irregularRate = (double) s.irregularRows / (double) Math.max(1L, s.rowsScanned);
        double nullRate = (double) s.nullCells / (double) Math.max(1L, s.totalCells);
        double dateErrRate = (double) s.dateParseErrors / (double) Math.max(1L, s.rowsScanned);
        double numErrRate = (double) s.numberParseErrors / (double) Math.max(1L, s.rowsScanned);

        if (irregularRate >= 0.02) {
            out.add(new UniversalImportQualityDto.Issue("HIGH", "IRREGULAR", "CSV irregular", "Hay filas con número de columnas distinto a la cabecera. Re-exporta a CSV UTF-8 o sube el XLSX original."));
        } else if (irregularRate > 0.0) {
            out.add(new UniversalImportQualityDto.Issue("MEDIUM", "IRREGULAR", "CSV irregular leve", "Algunas filas tienen columnas inconsistentes; puede afectar agregaciones."));
        }

        if (dateErrRate >= 0.12) {
            out.add(new UniversalImportQualityDto.Issue("HIGH", "DATE_PARSE", "Fechas no parseables", "Muchos valores en columnas tipo fecha no se pueden leer. Normaliza a YYYY-MM-DD."));
        } else if (dateErrRate >= 0.03) {
            out.add(new UniversalImportQualityDto.Issue("MEDIUM", "DATE_PARSE", "Fechas con errores", "Hay errores de parsing de fecha; revisa formatos mixtos."));
        }

        if (numErrRate >= 0.12) {
            out.add(new UniversalImportQualityDto.Issue("HIGH", "NUMBER_PARSE", "Números no parseables", "Muchos valores en columnas numéricas no se pueden leer. Revisa separadores y símbolos."));
        } else if (numErrRate >= 0.03) {
            out.add(new UniversalImportQualityDto.Issue("MEDIUM", "NUMBER_PARSE", "Números con errores", "Hay errores de parsing numérico; revisa comas/puntos."));
        }

        if (nullRate >= 0.30) {
            out.add(new UniversalImportQualityDto.Issue("MEDIUM", "NULLS", "Muchos nulos", "Hay muchas celdas vacías; puede distorsionar KPIs."));
        } else if (nullRate >= 0.12) {
            out.add(new UniversalImportQualityDto.Issue("LOW", "NULLS", "Nulos", "Hay celdas vacías; revisa columnas clave."));
        }

        return out;
    }

    private static int computeScore(Stats s, List<UniversalImportQualityDto.Issue> issues) {
        if (s.rowsScanned <= 0) return 0;
        double irregularRate = (double) s.irregularRows / (double) Math.max(1L, s.rowsScanned);
        double nullRate = (double) s.nullCells / (double) Math.max(1L, s.totalCells);
        double dateErrRate = (double) s.dateParseErrors / (double) Math.max(1L, s.rowsScanned);
        double numErrRate = (double) s.numberParseErrors / (double) Math.max(1L, s.rowsScanned);

        double score = 100.0;
        score -= irregularRate * 260.0;
        score -= dateErrRate * 220.0;
        score -= numErrRate * 220.0;
        score -= nullRate * 120.0;

        boolean high = issues != null && issues.stream().anyMatch(i -> i != null && "HIGH".equalsIgnoreCase(i.severity()));
        if (high) score -= 10.0;

        int out = (int) Math.round(Math.max(0.0, Math.min(100.0, score)));
        return out;
    }

    private static String computeLevel(int score, List<UniversalImportQualityDto.Issue> issues) {
        boolean high = issues != null && issues.stream().anyMatch(i -> i != null && "HIGH".equalsIgnoreCase(i.severity()));
        boolean medium = issues != null && issues.stream().anyMatch(i -> i != null && "MEDIUM".equalsIgnoreCase(i.severity()));
        if (high || score < 60) return "RED";
        if (medium || score < 80) return "YELLOW";
        return "GREEN";
    }

    private static void addExample(Stats s, String msg) {
        if (s.examples == null) s.examples = new ArrayList<>();
        if (s.examples.size() >= 8) return;
        if (msg == null || msg.isBlank()) return;
        s.examples.add(msg);
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        String v = s.trim();
        if (v.length() <= n) return v;
        return v.substring(0, Math.max(0, n - 1)) + "…";
    }

    private static char detectDelimiter(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return ',';
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
        if (pipes > max) { best = '|'; }
        return best;
    }

    private static int count(String s, char c) {
        if (s == null || s.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) n++;
        }
        return n;
    }
}

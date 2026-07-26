package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.UniversalColumnDto;
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
        long structuralNullCells = 0L;
        if (summary != null && summary.columns() != null) {
            for (UniversalColumnDto column : summary.columns()) {
                if (column == null || column.name() == null) continue;
                String detectedType = column.detectedType() == null ? "" : column.detectedType().trim().toLowerCase(Locale.ROOT);
                if ("date".equals(detectedType)) dateCols.add(column.name());
                if ("number".equals(detectedType)) numberCols.add(column.name());
                String nullSemantics = column.nullSemantics() == null ? "" : column.nullSemantics().trim().toUpperCase(Locale.ROOT);
                if ("STRUCTURAL".equals(nullSemantics) || "NOT_APPLICABLE".equals(nullSemantics)) {
                    structuralNullCells += Math.max(0L, column.nullCount());
                }
            }
        }

        byte[] bytes = universalImportFileService.normalizedCsv(companyId, imp.getId());
        if (bytes == null || bytes.length == 0) throw new ResponseStatusException(HttpStatus.GONE, "CSV normalizado no disponible");

        char delimiter = detectDelimiter(bytes);
        Stats stats = scan(bytes, delimiter, dateCols, numberCols);
        List<UniversalImportQualityDto.Issue> issues = buildIssues(stats, structuralNullCells);
        int score = computeScore(stats, issues, structuralNullCells);
        String level = computeLevel(score, issues);

        return new UniversalImportQualityDto(
            imp.getId(),
            imp.getFilename(),
            stats.rowsScanned,
            stats.columns,
            stats.irregularRows,
            stats.nullCells,
            stats.totalCells,
            stats.dateParseErrors,
            stats.numberParseErrors,
            stats.minDate == null ? null : stats.minDate.toString(),
            stats.maxDate == null ? null : stats.maxDate.toString(),
            score,
            level,
            issues,
            stats.examples
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
        Stats stats = new Stats();
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
            stats.columns = headers.size();

            for (CSVRecord record : parser) {
                stats.rowsScanned++;
                if (stats.rowsScanned > MAX_ROWS) break;
                if (record.size() != headers.size()) stats.irregularRows++;

                for (String header : headers) {
                    String raw = record.isMapped(header) ? record.get(header) : "";
                    String value = raw == null ? "" : raw.trim();
                    stats.totalCells++;
                    if (value.isBlank()) {
                        stats.nullCells++;
                        continue;
                    }

                    if (dateCols.contains(header)) {
                        LocalDate date = UniversalViewService.parseFlexibleDate(value);
                        if (date == null) {
                            stats.dateParseErrors++;
                            addExample(stats, "Fecha invalida (" + header + "): " + truncate(value, 28));
                        } else {
                            if (stats.minDate == null || date.isBefore(stats.minDate)) stats.minDate = date;
                            if (stats.maxDate == null || date.isAfter(stats.maxDate)) stats.maxDate = date;
                        }
                    } else if (numberCols.contains(header)) {
                        BigDecimal number = UniversalViewService.parseDecimal(value);
                        if (number == null) {
                            stats.numberParseErrors++;
                            addExample(stats, "Numero invalido (" + header + "): " + truncate(value, 28));
                        }
                    }
                }
            }

            return stats;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo calcular la calidad del dataset.");
        }
    }

    private static List<UniversalImportQualityDto.Issue> buildIssues(Stats stats, long structuralNullCells) {
        List<UniversalImportQualityDto.Issue> out = new ArrayList<>();
        if (stats.rowsScanned <= 0) {
            out.add(new UniversalImportQualityDto.Issue("HIGH", "EMPTY", "Sin filas", "No se detectaron filas validas en el CSV normalizado."));
            return out;
        }

        double irregularRate = (double) stats.irregularRows / (double) Math.max(1L, stats.rowsScanned);
        long effectiveNullCells = Math.max(0L, stats.nullCells - Math.max(0L, structuralNullCells));
        double nullRate = (double) effectiveNullCells / (double) Math.max(1L, stats.totalCells);
        double dateErrRate = (double) stats.dateParseErrors / (double) Math.max(1L, stats.rowsScanned);
        double numErrRate = (double) stats.numberParseErrors / (double) Math.max(1L, stats.rowsScanned);

        if (irregularRate >= 0.02d) {
            out.add(new UniversalImportQualityDto.Issue("HIGH", "IRREGULAR", "CSV irregular", "Hay filas con numero de columnas distinto a la cabecera. Reexporta a CSV UTF-8 o sube el XLSX original."));
        } else if (irregularRate > 0.0d) {
            out.add(new UniversalImportQualityDto.Issue("MEDIUM", "IRREGULAR", "CSV irregular leve", "Algunas filas tienen columnas inconsistentes; puede afectar agregaciones."));
        }

        if (dateErrRate >= 0.12d) {
            out.add(new UniversalImportQualityDto.Issue("HIGH", "DATE_PARSE", "Fechas no parseables", "Muchos valores en columnas tipo fecha no se pueden leer. Normaliza a YYYY-MM-DD."));
        } else if (dateErrRate >= 0.03d) {
            out.add(new UniversalImportQualityDto.Issue("MEDIUM", "DATE_PARSE", "Fechas con errores", "Hay errores de parsing de fecha; revisa formatos mixtos."));
        }

        if (numErrRate >= 0.12d) {
            out.add(new UniversalImportQualityDto.Issue("HIGH", "NUMBER_PARSE", "Numeros no parseables", "Muchos valores en columnas numericas no se pueden leer. Revisa separadores y simbolos."));
        } else if (numErrRate >= 0.03d) {
            out.add(new UniversalImportQualityDto.Issue("MEDIUM", "NUMBER_PARSE", "Numeros con errores", "Hay errores de parsing numerico; revisa comas y puntos."));
        }

        if (structuralNullCells > 0L) {
            out.add(new UniversalImportQualityDto.Issue(
                "LOW",
                "STRUCTURAL_NULLS",
                "Nulos estructurales detectados",
                "Parte de las celdas vacias parecen no aplicables por contexto de negocio y no se elevan automaticamente como error."
            ));
        }

        if (nullRate >= 0.30d) {
            out.add(new UniversalImportQualityDto.Issue("MEDIUM", "NULLS", "Muchos nulos", "Hay muchas celdas vacias fuera de los casos estructurales; pueden distorsionar KPIs."));
        } else if (nullRate >= 0.12d) {
            out.add(new UniversalImportQualityDto.Issue("LOW", "NULLS", "Nulos", "Hay celdas vacias fuera de los casos estructurales; revisa columnas clave."));
        }

        return out;
    }

    private static int computeScore(Stats stats, List<UniversalImportQualityDto.Issue> issues, long structuralNullCells) {
        if (stats.rowsScanned <= 0) return 0;
        double irregularRate = (double) stats.irregularRows / (double) Math.max(1L, stats.rowsScanned);
        long effectiveNullCells = Math.max(0L, stats.nullCells - Math.max(0L, structuralNullCells));
        double nullRate = (double) effectiveNullCells / (double) Math.max(1L, stats.totalCells);
        double dateErrRate = (double) stats.dateParseErrors / (double) Math.max(1L, stats.rowsScanned);
        double numErrRate = (double) stats.numberParseErrors / (double) Math.max(1L, stats.rowsScanned);

        double score = 100.0d;
        score -= irregularRate * 260.0d;
        score -= dateErrRate * 220.0d;
        score -= numErrRate * 220.0d;
        score -= nullRate * 120.0d;

        boolean high = issues != null && issues.stream().anyMatch(i -> i != null && "HIGH".equalsIgnoreCase(i.severity()));
        if (high) score -= 10.0d;

        return (int) Math.round(Math.max(0.0d, Math.min(100.0d, score)));
    }

    private static String computeLevel(int score, List<UniversalImportQualityDto.Issue> issues) {
        boolean high = issues != null && issues.stream().anyMatch(i -> i != null && "HIGH".equalsIgnoreCase(i.severity()));
        boolean medium = issues != null && issues.stream().anyMatch(i -> i != null && "MEDIUM".equalsIgnoreCase(i.severity()));
        if (high || score < 60) return "RED";
        if (medium || score < 80) return "YELLOW";
        return "GREEN";
    }

    private static void addExample(Stats stats, String message) {
        if (stats.examples == null) stats.examples = new ArrayList<>();
        if (stats.examples.size() >= 8) return;
        if (message == null || message.isBlank()) return;
        stats.examples.add(message);
    }

    private static String truncate(String value, int limit) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.length() <= limit) return trimmed;
        return trimmed.substring(0, Math.max(0, limit - 1)) + "...";
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
        if (semis > max) {
            max = semis;
            best = ';';
        }
        if (tabs > max) {
            max = tabs;
            best = '\t';
        }
        if (pipes > max) best = '|';
        return best;
    }

    private static int count(String value, char needle) {
        if (value == null || value.isEmpty()) return 0;
        int total = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == needle) total++;
        }
        return total;
    }
}

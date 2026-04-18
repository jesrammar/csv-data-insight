package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.UniversalChartDataDto;
import com.asecon.enterpriseiq.dto.UniversalEvidenceDto;
import com.asecon.enterpriseiq.dto.UniversalFilter;
import com.asecon.enterpriseiq.dto.UniversalViewRequest;
import com.asecon.enterpriseiq.model.UniversalImport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UniversalViewService {
    private static final int MAX_ROWS = 20000;
    private static final int DEFAULT_TOP_N = 8;
    private static final int DEFAULT_MAX_POINTS = 1500;
    private static final int HEATMAP_MAX_X = 20;
    private static final int HEATMAP_MAX_Y = 20;
    private static final List<DateTimeFormatter> FLEX_DATES = List.of(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("d/M/uuuu"),
        DateTimeFormatter.ofPattern("dd/MM/uuuu"),
        DateTimeFormatter.ofPattern("d-M-uuuu"),
        DateTimeFormatter.ofPattern("dd-MM-uuuu")
    );
    private static final Pattern YEAR_MONTH_NUMERIC = Pattern.compile("^(\\d{4})[-/.](\\d{1,2})$");
    private static final Pattern YEAR_MONTH_NUMERIC_REVERSED = Pattern.compile("^(\\d{1,2})[-/.](\\d{4})$");
    private static final Pattern YEAR_MONTH_TEXT = Pattern.compile("^(?<a>[\\p{L}.]+)[\\s\\-_/]+(?<b>\\d{4})$|^(?<c>\\d{4})[\\s\\-_/]+(?<d>[\\p{L}.]+)$");
    private static final Map<String, Integer> MONTH_ALIASES = buildMonthAliases();

    private final UniversalImportFileService universalImportFileService;
    private final ObjectMapper objectMapper;

    public UniversalViewService(UniversalImportFileService universalImportFileService, ObjectMapper objectMapper) {
        this.universalImportFileService = universalImportFileService;
        this.objectMapper = objectMapper;
    }

    public String encodeConfig(UniversalViewRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception ex) {
            return "{}";
        }
    }

    public UniversalViewRequest decodeConfig(String json) {
        try {
            return objectMapper.readValue(json, UniversalViewRequest.class);
        } catch (Exception ex) {
            return new UniversalViewRequest();
        }
    }

    public UniversalChartDataDto preview(Long companyId, UniversalViewRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Config vacío.");
        String type = normType(request.getType());
        if (type == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de dashboard inválido.");

        UniversalImport latestImp = null;
        try {
            latestImp = universalImportFileService.latest(companyId).orElse(null);
        } catch (Exception ignored) {
            latestImp = null;
        }

        byte[] bytes = universalImportFileService.latestNormalizedCsv(companyId);
        if (bytes == null || bytes.length == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No hay dataset Universal.");

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
            if (headers.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dataset sin cabeceras.");

            UniversalChartDataDto out;
            if ("TIME_SERIES".equals(type)) {
                out = buildTimeSeries(parser, headers, request);
            } else if ("CATEGORY_BAR".equals(type)) {
                out = buildCategoryBar(parser, headers, request);
            } else if ("KPI_CARDS".equals(type)) {
                out = buildKpiCards(parser, headers, request);
            } else if ("SCATTER".equals(type)) {
                out = buildScatter(parser, headers, request);
            } else if ("HEATMAP".equals(type)) {
                out = buildHeatmap(parser, headers, request);
            } else if ("PIVOT_MONTHLY".equals(type)) {
                out = buildPivotMonthly(parser, headers, request);
            } else {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo no soportado.");
            }

            Map<String, Object> meta = out.meta() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(out.meta());
            meta.putIfAbsent("request", requestLineage(request));
            if (latestImp != null) {
                meta.putIfAbsent("sourceFilename", latestImp.getFilename());
                meta.putIfAbsent("sourceImportedAt", latestImp.getCreatedAt() == null ? null : latestImp.getCreatedAt().toString());
                meta.putIfAbsent("sourceImportId", latestImp.getId());
            }
            if (meta.isEmpty()) return out;
            return new UniversalChartDataDto(out.type(), out.labels(), out.series(), meta);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo calcular el dashboard desde Universal: " + safeMsg(ex));
        }
    }

    public UniversalChartDataDto previewSnapshot(Long companyId, UniversalViewRequest request, Long sourceUniversalImportId) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Config vacío.");
        String type = normType(request.getType());
        if (type == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de dashboard inválido.");

        byte[] bytes;
        boolean fellBackToLatest = false;
        try {
            bytes = universalImportFileService.normalizedCsv(companyId, sourceUniversalImportId);
        } catch (ResponseStatusException ex) {
            // If a dashboard is pinned to an old import whose storage has been cleaned/moved, fall back to latest
            // so /universal/views/:id never renders as "vacío". We keep a warning in meta.
            if (sourceUniversalImportId != null) {
                bytes = universalImportFileService.latestNormalizedCsv(companyId);
                fellBackToLatest = true;
            } else {
                throw ex;
            }
        }
        if (bytes == null || bytes.length == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No hay dataset Universal.");

        UniversalChartDataDto out = previewBytes(bytes, request);
        Map<String, Object> meta = out.meta() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(out.meta());
        meta.putIfAbsent("request", requestLineage(request));

        UniversalImport imp = null;
        if (!fellBackToLatest) {
            imp = sourceUniversalImportId == null
                ? universalImportFileService.latest(companyId).orElse(null)
                : universalImportFileService.find(companyId, sourceUniversalImportId).orElse(null);
        } else {
            imp = universalImportFileService.latest(companyId).orElse(null);
            meta.putIfAbsent("pinnedImportId", sourceUniversalImportId);
            meta.putIfAbsent("fellBackToLatest", true);
            meta.putIfAbsent("warnings", List.of("El dataset original de este dashboard ya no está disponible. Se muestra el último dataset subido."));
        }

        if (sourceUniversalImportId != null) meta.putIfAbsent("sourceImportId", sourceUniversalImportId);
        if (imp != null) {
            meta.putIfAbsent("sourceFilename", imp.getFilename());
            meta.putIfAbsent("sourceImportedAt", imp.getCreatedAt() == null ? null : imp.getCreatedAt().toString());
            meta.putIfAbsent("sourceImportId", imp.getId());
        }
        if (meta.isEmpty()) return out;
        return new UniversalChartDataDto(out.type(), out.labels(), out.series(), meta);
    }

    public byte[] problemsCsv(Long companyId, UniversalViewRequest request, int limit, Long importId) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Config vacío.");
        String type = normType(request.getType());
        if (type == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de dashboard inválido.");
        if (limit < 1) limit = 1;
        if (limit > 200) limit = 200;

        byte[] bytes = universalImportFileService.normalizedCsv(companyId, importId);
        if (bytes == null || bytes.length == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No hay dataset Universal.");

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

            List<UniversalFilter> filters = normalizeFilters(parser, request);
            ProblemColumns pc = problemColumns(type, request);
            if (pc == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo no soportado.");
            if (pc.dateCol != null) requireMapped(parser, pc.dateCol);
            if (pc.valueCol != null) requireMapped(parser, pc.valueCol);
            if (pc.xCol != null) requireMapped(parser, pc.xCol);
            if (pc.yCol != null) requireMapped(parser, pc.yCol);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (CSVPrinter out = new CSVPrinter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
                out.printRecord("row", "reason", "date", "value", "x", "y");
                int scanned = 0;
                int written = 0;
                for (CSVRecord r : parser) {
                    scanned++;
                    if (scanned > MAX_ROWS) break;
                    if (!matchesFilters(r, filters)) continue;
                    String reason = problemReason(r, pc);
                    if (reason == null) continue;
                    written++;
                    out.printRecord(
                        scanned,
                        reason,
                        pc.dateCol == null ? "" : safeCell(get(r, pc.dateCol)),
                        pc.valueCol == null ? "" : safeCell(get(r, pc.valueCol)),
                        pc.xCol == null ? "" : safeCell(get(r, pc.xCol)),
                        pc.yCol == null ? "" : safeCell(get(r, pc.yCol))
                    );
                    if (written >= limit) break;
                }
            }
            return baos.toByteArray();
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo generar CSV de problemas: " + safeMsg(ex));
        }
    }

    public UniversalEvidenceDto evidence(Long companyId, UniversalViewRequest request, String focusLabel, int limit, Long importId) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Config vacío.");
        String type = normType(request.getType());
        if (type == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de dashboard inválido.");
        if (limit < 10) limit = 10;
        if (limit > 200) limit = 200;

        UniversalImport imp = null;
        try {
            Optional<UniversalImport> found = universalImportFileService.find(companyId, importId);
            imp = found.orElseGet(() -> universalImportFileService.latest(companyId).orElse(null));
        } catch (Exception ignored) {
            imp = null;
        }

        byte[] bytes = universalImportFileService.normalizedCsv(companyId, importId);
        if (bytes == null || bytes.length == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No hay dataset Universal.");

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
            if (headers.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dataset sin cabeceras.");
            List<UniversalFilter> filters = normalizeFilters(parser, request);

            EvidenceSpec spec = evidenceSpec(type, request, focusLabel);
            if (spec == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Evidencia no soportada para este tipo.");
            for (String h : spec.requiredColumns) requireMapped(parser, h);

            List<String> viewHeaders = pickEvidenceHeaders(headers, spec.importantColumns, filters);
            List<List<String>> rowsOut = new ArrayList<>();
            List<Integer> rowNums = new ArrayList<>();

            int scanned = 0;
            int matched = 0;
            int badInFocus = 0;
            for (CSVRecord r : parser) {
                scanned++;
                if (scanned > MAX_ROWS) break;
                if (!matchesFilters(r, filters)) continue;
                if (!spec.matchesFocus().test(r)) continue;
                matched++;

                if (spec.requiresValidValue) {
                    BigDecimal v = parseDecimal(clean(get(r, spec.valueColumn)));
                    if (v == null) {
                        badInFocus++;
                        continue;
                    }
                }

                rowsOut.add(projectRow(r, viewHeaders));
                rowNums.add(scanned);
                if (rowsOut.size() >= limit) break;
            }

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("type", type);
            meta.put("request", requestLineage(request));
            meta.put("filters", filtersToLineage(filters));
            meta.put("focusLabel", focusLabel == null ? "" : focusLabel);
            meta.put("rowsScanned", scanned);
            meta.put("rowsMatched", matched);
            meta.put("rowsReturned", rowsOut.size());
            meta.put("truncated", scanned > MAX_ROWS);
            meta.put("badRowsInFocus", badInFocus);
            meta.putAll(spec.meta());
            if (imp != null) {
                meta.putIfAbsent("sourceFilename", imp.getFilename());
                meta.putIfAbsent("sourceImportedAt", imp.getCreatedAt() == null ? null : imp.getCreatedAt().toString());
                meta.putIfAbsent("sourceImportId", imp.getId());
            }
            if (rowsOut.isEmpty()) {
                meta.put("warning", "Sin filas de evidencia para ese punto/segmento (revisa filtros, columna o formato).");
            }

            return new UniversalEvidenceDto(
                imp == null ? null : imp.getFilename(),
                viewHeaders,
                rowsOut,
                rowNums,
                meta
            );
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo calcular evidencia: " + safeMsg(ex));
        }
    }

    private record EvidenceSpec(
        List<String> requiredColumns,
        List<String> importantColumns,
        boolean requiresValidValue,
        String valueColumn,
        java.util.function.Predicate<CSVRecord> matchesFocus,
        Map<String, Object> meta
    ) {}

    private EvidenceSpec evidenceSpec(String type, UniversalViewRequest req, String focusLabel) {
        String t = normType(type);
        if (t == null) return null;

        if ("TIME_SERIES".equals(t)) {
            String dateCol = clean(req.getDateColumn());
            String valCol = clean(req.getValueColumn());
            if (isBlank(dateCol) || isBlank(valCol)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecciona columna fecha y valor.");
            }
            if (isBlank(focusLabel)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta focusLabel (YYYY-MM).");
            }
            YearMonth ym = parseYearMonth(String.valueOf(focusLabel).trim());
            if (ym == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "focusLabel inválido. Usa YYYY-MM.");
            String ymKey = ym.toString();
            return new EvidenceSpec(
                List.of(dateCol, valCol),
                List.of(dateCol, valCol),
                true,
                valCol,
                (r) -> {
                    YearMonth k = parseYearMonth(clean(get(r, dateCol)));
                    return k != null && ymKey.equals(k.toString());
                },
                Map.of("dateColumn", dateCol, "valueColumn", valCol, "bucket", ymKey)
            );
        }

        if ("CATEGORY_BAR".equals(t)) {
            String catCol = clean(req.getCategoryColumn());
            String valCol = clean(req.getValueColumn());
            if (isBlank(catCol) || isBlank(valCol)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecciona columna categoría y valor.");
            }
            if (isBlank(focusLabel)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta focusLabel (categoría).");
            }
            String key = String.valueOf(focusLabel).trim();
            return new EvidenceSpec(
                List.of(catCol, valCol),
                List.of(catCol, valCol),
                true,
                valCol,
                (r) -> Objects.equals(clean(get(r, catCol)), key),
                Map.of("categoryColumn", catCol, "valueColumn", valCol, "category", key)
            );
        }

        if ("KPI_CARDS".equals(t)) {
            String valCol = clean(req.getValueColumn());
            List<String> reqCols = new ArrayList<>();
            List<String> important = new ArrayList<>();
            if (!isBlank(valCol)) {
                reqCols.add(valCol);
                important.add(valCol);
            }
            return new EvidenceSpec(
                reqCols,
                important,
                false,
                valCol,
                (r) -> true,
                Map.of("valueColumn", valCol == null ? "" : valCol)
            );
        }

        if ("SCATTER".equals(t)) {
            String xCol = clean(req.getXColumn());
            String yCol = clean(req.getYColumn());
            if (isBlank(xCol) || isBlank(yCol)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecciona columnas X e Y.");
            }
            if (isBlank(focusLabel)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta focusLabel (x,y). Haz click en un punto.");
            }
            String[] parts = String.valueOf(focusLabel).split(",", 3);
            if (parts.length < 2) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "focusLabel inválido para scatter. Usa 'x,y'.");
            }
            BigDecimal x0 = parseDecimal(clean(parts[0]));
            BigDecimal y0 = parseDecimal(clean(parts[1]));
            if (x0 == null || y0 == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "focusLabel inválido para scatter. Usa números (x,y).");
            }
            BigDecimal epsX = x0.abs().multiply(new BigDecimal("0.0005")).max(new BigDecimal("0.000001"));
            BigDecimal epsY = y0.abs().multiply(new BigDecimal("0.0005")).max(new BigDecimal("0.000001"));
            return new EvidenceSpec(
                List.of(xCol, yCol),
                List.of(xCol, yCol),
                false,
                null,
                (r) -> {
                    BigDecimal x = parseDecimal(clean(get(r, xCol)));
                    BigDecimal y = parseDecimal(clean(get(r, yCol)));
                    if (x == null || y == null) return false;
                    return x.subtract(x0).abs().compareTo(epsX) <= 0 && y.subtract(y0).abs().compareTo(epsY) <= 0;
                },
                Map.of("xColumn", xCol, "yColumn", yCol, "x", x0, "y", y0, "epsX", epsX, "epsY", epsY)
            );
        }

        if ("HEATMAP".equals(t)) {
            String xCol = clean(req.getXColumn());
            String yCol = clean(req.getYColumn());
            String valCol = clean(req.getValueColumn());
            if (isBlank(xCol) || isBlank(yCol) || isBlank(valCol)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecciona columnas X, Y y Valor.");
            }
            if (isBlank(focusLabel)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta focusLabel (xLabel||yLabel). Haz click en una celda.");
            }
            String raw = String.valueOf(focusLabel);
            int sep = raw.indexOf("||");
            if (sep < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "focusLabel inválido para heatmap. Usa 'xLabel||yLabel'.");
            String xLabel = raw.substring(0, sep).trim();
            String yLabel = raw.substring(sep + 2).trim();
            if (isBlank(xLabel) || isBlank(yLabel)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "focusLabel inválido para heatmap.");
            return new EvidenceSpec(
                List.of(xCol, yCol, valCol),
                List.of(xCol, yCol, valCol),
                true,
                valCol,
                (r) -> Objects.equals(clean(get(r, xCol)), xLabel) && Objects.equals(clean(get(r, yCol)), yLabel),
                Map.of("xColumn", xCol, "yColumn", yCol, "valueColumn", valCol, "xLabel", xLabel, "yLabel", yLabel)
            );
        }

        if ("PIVOT_MONTHLY".equals(t)) {
            String dateCol = clean(req.getDateColumn());
            String catCol = clean(req.getCategoryColumn());
            String valCol = clean(req.getValueColumn());
            if (isBlank(dateCol) || isBlank(catCol) || isBlank(valCol)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecciona columna fecha, categoría y valor.");
            }
            if (isBlank(focusLabel)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta focusLabel (categoria||YYYY-MM). Haz click en una barra.");
            }
            String raw = String.valueOf(focusLabel);
            int sep = raw.indexOf("||");
            if (sep < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "focusLabel inválido para pivote. Usa 'categoria||YYYY-MM'.");
            String cat = raw.substring(0, sep).trim();
            String month = raw.substring(sep + 2).trim();
            YearMonth ym = parseYearMonth(month);
            if (isBlank(cat) || ym == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "focusLabel inválido para pivote. Usa 'categoria||YYYY-MM'.");
            }
            String ymKey = ym.toString();
            return new EvidenceSpec(
                List.of(dateCol, catCol, valCol),
                List.of(dateCol, catCol, valCol),
                true,
                valCol,
                (r) -> {
                    YearMonth k = parseYearMonth(clean(get(r, dateCol)));
                    return k != null && ymKey.equals(k.toString()) && Objects.equals(clean(get(r, catCol)), cat);
                },
                Map.of("dateColumn", dateCol, "categoryColumn", catCol, "valueColumn", valCol, "category", cat, "bucket", ymKey)
            );
        }

        return null;
    }

    private static List<String> pickEvidenceHeaders(List<String> headers, List<String> importantColumns, List<UniversalFilter> filters) {
        TreeSet<String> picked = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        List<String> out = new ArrayList<>();

        for (String h : importantColumns == null ? List.<String>of() : importantColumns) {
            if (isBlank(h)) continue;
            if (picked.add(h)) out.add(h);
        }
        for (UniversalFilter f : filters == null ? List.<UniversalFilter>of() : filters) {
            String c = clean(f == null ? null : f.getColumn());
            if (isBlank(c)) continue;
            if (picked.add(c)) out.add(c);
        }

        int max = 22;
        for (String h : headers) {
            if (out.size() >= max) break;
            if (isBlank(h)) continue;
            if (picked.add(h)) out.add(h);
        }
        return out;
    }

    private static List<String> projectRow(CSVRecord record, List<String> headers) {
        List<String> row = new ArrayList<>(headers.size());
        for (String h : headers) {
            row.add(record.isMapped(h) ? safeCell(record.get(h)) : "");
        }
        return row;
    }

    private record ProblemColumns(String dateCol, String valueCol, String xCol, String yCol) {}

    private static ProblemColumns problemColumns(String type, UniversalViewRequest req) {
        return switch (type) {
            case "TIME_SERIES" -> new ProblemColumns(clean(req.getDateColumn()), clean(req.getValueColumn()), null, null);
            case "CATEGORY_BAR" -> new ProblemColumns(null, clean(req.getValueColumn()), null, null);
            case "KPI_CARDS" -> new ProblemColumns(null, clean(req.getValueColumn()), null, null);
            case "PIVOT_MONTHLY" -> new ProblemColumns(clean(req.getDateColumn()), clean(req.getValueColumn()), null, null);
            case "SCATTER" -> new ProblemColumns(null, null, clean(req.getXColumn()), clean(req.getYColumn()));
            case "HEATMAP" -> new ProblemColumns(null, clean(req.getValueColumn()), clean(req.getXColumn()), clean(req.getYColumn()));
            default -> null;
        };
    }

    private static String problemReason(CSVRecord r, ProblemColumns pc) {
        if (pc == null) return null;
        boolean badDate = false;
        boolean badValue = false;
        boolean badX = false;
        boolean badY = false;
        if (pc.dateCol != null) {
            String dr = clean(get(r, pc.dateCol));
            badDate = dr != null && parseYearMonth(dr) == null;
        }
        if (pc.valueCol != null) {
            String vr = clean(get(r, pc.valueCol));
            badValue = vr != null && parseDecimal(vr) == null;
        }
        if (pc.xCol != null) {
            String xr = clean(get(r, pc.xCol));
            badX = xr != null && parseDecimal(xr) == null;
        }
        if (pc.yCol != null) {
            String yr = clean(get(r, pc.yCol));
            badY = yr != null && parseDecimal(yr) == null;
        }
        if (!(badDate || badValue || badX || badY)) return null;
        List<String> parts = new ArrayList<>();
        if (badDate) parts.add("bad_date");
        if (badValue) parts.add("bad_value");
        if (badX) parts.add("bad_x");
        if (badY) parts.add("bad_y");
        return String.join(",", parts);
    }

    private static String safeCell(String s) {
        if (s == null) return "";
        String v = s.replace("\r", " ").replace("\n", " ").trim();
        if (v.length() > 200) v = v.substring(0, 200) + "…";
        return v;
    }

    // package-private for tests
    UniversalChartDataDto previewBytes(byte[] bytes, UniversalViewRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Config vacío.");
        String type = normType(request.getType());
        if (type == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de dashboard inválido.");
        if (bytes == null || bytes.length == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No hay dataset Universal.");

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
            if (headers.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dataset sin cabeceras.");

            if ("TIME_SERIES".equals(type)) return buildTimeSeries(parser, headers, request);
            if ("CATEGORY_BAR".equals(type)) return buildCategoryBar(parser, headers, request);
            if ("KPI_CARDS".equals(type)) return buildKpiCards(parser, headers, request);
            if ("SCATTER".equals(type)) return buildScatter(parser, headers, request);
            if ("HEATMAP".equals(type)) return buildHeatmap(parser, headers, request);
            if ("PIVOT_MONTHLY".equals(type)) return buildPivotMonthly(parser, headers, request);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo no soportado.");
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo calcular el dashboard desde Universal: " + safeMsg(ex));
        }
    }

    private UniversalChartDataDto buildTimeSeries(CSVParser parser, List<String> headers, UniversalViewRequest req) {
        String dateCol = req.getDateColumn();
        String valCol = req.getValueColumn();
        if (isBlank(dateCol) || isBlank(valCol)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecciona columna fecha y valor.");
        }
        requireMapped(parser, dateCol);
        requireMapped(parser, valCol);
        List<UniversalFilter> filters = normalizeFilters(parser, req);
        String agg = normAgg(req.getAggregation());

        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();

        int rows = 0;
        int used = 0;
        int badDates = 0;
        int badNums = 0;
        List<String> badDateSamples = new ArrayList<>();
        List<String> badNumSamples = new ArrayList<>();
        for (CSVRecord record : parser) {
            rows++;
            if (rows > MAX_ROWS) break;
            if (!matchesFilters(record, filters)) continue;
            String dateRaw = clean(get(record, dateCol));
            String valRaw = clean(get(record, valCol));
            if (dateRaw == null || valRaw == null) continue;
            YearMonth ym = parseYearMonth(dateRaw);
            if (ym == null) {
                badDates++;
                if (badDateSamples.size() < 3) badDateSamples.add(dateRaw);
                continue;
            }
            BigDecimal v = parseDecimal(valRaw);
            if (v == null) {
                badNums++;
                if (badNumSamples.size() < 3) badNumSamples.add(valRaw);
                continue;
            }

            String key = ym.toString();
            sums.put(key, sums.getOrDefault(key, BigDecimal.ZERO).add(v));
            counts.put(key, counts.getOrDefault(key, 0) + 1);
            used++;
        }
        if (used == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, explainNoRows("serie temporal", dateCol, valCol, badDates, badDateSamples, badNums, badNumSamples));
        }

        List<String> labels = new ArrayList<>(sums.keySet());
        labels.sort(String::compareTo);

        List<Number> data = new ArrayList<>(labels.size());
        for (String k : labels) {
            BigDecimal sum = sums.getOrDefault(k, BigDecimal.ZERO);
            if ("avg".equals(agg)) {
                int n = Math.max(1, counts.getOrDefault(k, 1));
                sum = sum.divide(BigDecimal.valueOf(n), 6, RoundingMode.HALF_UP);
            }
            data.add(sum.setScale(2, RoundingMode.HALF_UP));
        }

        Map<String, Object> s = Map.of("name", "Valor", "data", data);
        List<String> warnings = new ArrayList<>();
        addWarnIf(warnings, rows > MAX_ROWS, "Dataset recortado: se analizaron " + rows + " filas (límite " + MAX_ROWS + ").");
        addWarnIf(warnings, badDates > 0, "Fechas no parseables en '" + dateCol + "': " + badDates + sampleSuffix(badDateSamples) + ".");
        addWarnIf(warnings, badNums > 0, "Números no parseables en '" + valCol + "': " + badNums + sampleSuffix(badNumSamples) + ".");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("aggregation", agg);
        meta.put("rowsUsed", used);
        meta.put("rowsScanned", rows);
        meta.put("truncated", rows > MAX_ROWS);
        meta.put("filters", filtersToLineage(filters));
        meta.put("dateColumn", dateCol);
        meta.put("valueColumn", valCol);
        meta.put("badDateCount", badDates);
        meta.put("badNumberCount", badNums);
        if (rows > 0) {
            meta.put("badDatePct", roundPct(badDates, rows));
            meta.put("badNumberPct", roundPct(badNums, rows));
        }
        if (!warnings.isEmpty()) meta.put("warnings", warnings);
        return new UniversalChartDataDto("TIME_SERIES", labels, List.of(s), meta);
    }

    private UniversalChartDataDto buildCategoryBar(CSVParser parser, List<String> headers, UniversalViewRequest req) {
        String catCol = req.getCategoryColumn();
        String valCol = req.getValueColumn();
        if (isBlank(catCol) || isBlank(valCol)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecciona columna categoría y valor.");
        }
        requireMapped(parser, catCol);
        requireMapped(parser, valCol);
        List<UniversalFilter> filters = normalizeFilters(parser, req);
        String agg = normAgg(req.getAggregation());

        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();

        int rows = 0;
        int used = 0;
        int badNums = 0;
        List<String> badNumSamples = new ArrayList<>();
        for (CSVRecord record : parser) {
            rows++;
            if (rows > MAX_ROWS) break;
            if (!matchesFilters(record, filters)) continue;
            String cat = clean(get(record, catCol));
            String valRaw = clean(get(record, valCol));
            if (cat == null || valRaw == null) continue;
            BigDecimal v = parseDecimal(valRaw);
            if (v == null) {
                badNums++;
                if (badNumSamples.size() < 3) badNumSamples.add(valRaw);
                continue;
            }
            String key = cat;
            sums.put(key, sums.getOrDefault(key, BigDecimal.ZERO).add(v));
            counts.put(key, counts.getOrDefault(key, 0) + 1);
            used++;
        }
        if (used == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No hay filas válidas para graficar (revisa categoría/valor).");
        }

        List<Map.Entry<String, BigDecimal>> ordered = new ArrayList<>(sums.entrySet());
        ordered.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        if (ordered.size() > 12) ordered = ordered.subList(0, 12);

        List<String> labels = ordered.stream().map(Map.Entry::getKey).toList();
        List<Number> data = new ArrayList<>(labels.size());
        for (String k : labels) {
            BigDecimal sum = sums.getOrDefault(k, BigDecimal.ZERO);
            if ("avg".equals(agg)) {
                int n = Math.max(1, counts.getOrDefault(k, 1));
                sum = sum.divide(BigDecimal.valueOf(n), 6, RoundingMode.HALF_UP);
            }
            data.add(sum.setScale(2, RoundingMode.HALF_UP));
        }

        Map<String, Object> s = Map.of("name", "Valor", "data", data);
        List<String> warnings = new ArrayList<>();
        addWarnIf(warnings, rows > MAX_ROWS, "Dataset recortado: se analizaron " + rows + " filas (límite " + MAX_ROWS + ").");
        addWarnIf(warnings, badNums > 0, "Números no parseables en '" + valCol + "': " + badNums + sampleSuffix(badNumSamples) + ".");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("aggregation", agg);
        meta.put("rowsUsed", used);
        meta.put("rowsScanned", rows);
        meta.put("truncated", rows > MAX_ROWS);
        meta.put("filters", filtersToLineage(filters));
        meta.put("categoryColumn", catCol);
        meta.put("valueColumn", valCol);
        meta.put("badNumberCount", badNums);
        if (rows > 0) meta.put("badNumberPct", roundPct(badNums, rows));
        if (!warnings.isEmpty()) meta.put("warnings", warnings);
        return new UniversalChartDataDto("CATEGORY_BAR", labels, List.of(s), meta);
    }

    private UniversalChartDataDto buildKpiCards(CSVParser parser, List<String> headers, UniversalViewRequest req) {
        String valCol = clean(req.getValueColumn());
        if (!isBlank(valCol)) requireMapped(parser, valCol);
        List<UniversalFilter> filters = normalizeFilters(parser, req);

        int rows = 0;
        int used = 0;
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal min = null;
        BigDecimal max = null;
        int badNums = 0;
        List<String> badNumSamples = new ArrayList<>();
        for (CSVRecord record : parser) {
            rows++;
            if (rows > MAX_ROWS) break;
            if (!matchesFilters(record, filters)) continue;

            used++;
            if (isBlank(valCol)) continue;
            String valRaw = clean(get(record, valCol));
            BigDecimal v = parseDecimal(valRaw);
            if (v == null) {
                badNums++;
                if (badNumSamples.size() < 3 && valRaw != null) badNumSamples.add(valRaw);
                continue;
            }
            sum = sum.add(v);
            if (min == null || v.compareTo(min) < 0) min = v;
            if (max == null || v.compareTo(max) > 0) max = v;
        }
        if (used == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No hay filas válidas para calcular KPIs (revisa filtro).");
        }

        List<String> labels = new ArrayList<>();
        List<Number> data = new ArrayList<>();
        labels.add("count");
        data.add(used);
        if (!isBlank(valCol)) {
            labels.add("sum");
            data.add(sum.setScale(2, RoundingMode.HALF_UP));
            labels.add("avg");
            data.add(sum.divide(BigDecimal.valueOf(Math.max(1, used)), 6, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP));
            if (min != null) {
                labels.add("min");
                data.add(min.setScale(2, RoundingMode.HALF_UP));
            }
            if (max != null) {
                labels.add("max");
                data.add(max.setScale(2, RoundingMode.HALF_UP));
            }
        }

        Map<String, Object> s = Map.of("name", "KPIs", "data", data);
        List<String> warnings = new ArrayList<>();
        addWarnIf(warnings, rows > MAX_ROWS, "Dataset recortado: se analizaron " + rows + " filas (límite " + MAX_ROWS + ").");
        addWarnIf(warnings, badNums > 0, "Números no parseables en '" + valCol + "': " + badNums + sampleSuffix(badNumSamples) + ".");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("rowsUsed", used);
        meta.put("rowsScanned", rows);
        meta.put("truncated", rows > MAX_ROWS);
        meta.put("filters", filtersToLineage(filters));
        meta.put("valueColumn", valCol == null ? "" : valCol);
        meta.put("filterColumn", clean(req.getFilterColumn()));
        meta.put("filterValue", clean(req.getFilterValue()));
        meta.put("badNumberCount", badNums);
        if (rows > 0) meta.put("badNumberPct", roundPct(badNums, rows));
        if (!warnings.isEmpty()) meta.put("warnings", warnings);
        return new UniversalChartDataDto("KPI_CARDS", labels, List.of(s), meta);
    }

    private UniversalChartDataDto buildScatter(CSVParser parser, List<String> headers, UniversalViewRequest req) {
        String xCol = req.getXColumn();
        String yCol = req.getYColumn();
        if (isBlank(xCol) || isBlank(yCol)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecciona columnas X e Y.");
        }
        requireMapped(parser, xCol);
        requireMapped(parser, yCol);
        List<UniversalFilter> filters = normalizeFilters(parser, req);
        int maxPoints = clamp(req.getMaxPoints(), 50, 10000, DEFAULT_MAX_POINTS);

        int rows = 0;
        int used = 0;
        int badX = 0;
        int badY = 0;
        List<String> badXSamples = new ArrayList<>();
        List<String> badYSamples = new ArrayList<>();
        List<List<Number>> points = new ArrayList<>();
        long seen = 0;
        SplittableRandom rnd = new SplittableRandom(12345);
        for (CSVRecord record : parser) {
            rows++;
            if (rows > MAX_ROWS) break;
            if (!matchesFilters(record, filters)) continue;
            String xr = clean(get(record, xCol));
            String yr = clean(get(record, yCol));
            BigDecimal x = parseDecimal(xr);
            BigDecimal y = parseDecimal(yr);
            if (x == null) { badX++; if (badXSamples.size() < 3 && xr != null) badXSamples.add(xr); }
            if (y == null) { badY++; if (badYSamples.size() < 3 && yr != null) badYSamples.add(yr); }
            if (x == null || y == null) continue;

            seen++;
            used++;
            List<Number> p = List.of(x, y);
            if (points.size() < maxPoints) {
                points.add(p);
            } else {
                long j = rnd.nextLong(seen);
                if (j < maxPoints) points.set((int) j, p);
            }
        }
        if (used == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, explainNoRowsScatter(xCol, yCol, badX, badXSamples, badY, badYSamples));
        }

        Map<String, Object> s = Map.of("name", "Puntos", "data", points);
        boolean sampled = used > maxPoints;
        List<String> warnings = new ArrayList<>();
        addWarnIf(warnings, rows > MAX_ROWS, "Dataset recortado: se analizaron " + rows + " filas (límite " + MAX_ROWS + ").");
        addWarnIf(warnings, sampled, "Muestreo: se muestran " + points.size() + " puntos (de " + used + ").");
        addWarnIf(warnings, badX > 0, "X no parseable en '" + xCol + "': " + badX + sampleSuffix(badXSamples) + ".");
        addWarnIf(warnings, badY > 0, "Y no parseable en '" + yCol + "': " + badY + sampleSuffix(badYSamples) + ".");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("rowsUsed", used);
        meta.put("rowsScanned", rows);
        meta.put("truncated", rows > MAX_ROWS);
        meta.put("sampled", sampled);
        meta.put("filters", filtersToLineage(filters));
        meta.put("xColumn", xCol);
        meta.put("yColumn", yCol);
        meta.put("maxPoints", maxPoints);
        meta.put("filterColumn", clean(req.getFilterColumn()));
        meta.put("filterValue", clean(req.getFilterValue()));
        meta.put("badXCount", badX);
        meta.put("badYCount", badY);
        if (rows > 0) {
            meta.put("badXPct", roundPct(badX, rows));
            meta.put("badYPct", roundPct(badY, rows));
        }
        if (!warnings.isEmpty()) meta.put("warnings", warnings);
        return new UniversalChartDataDto("SCATTER", List.of(), List.of(s), meta);
    }

    private UniversalChartDataDto buildHeatmap(CSVParser parser, List<String> headers, UniversalViewRequest req) {
        String xCol = req.getXColumn();
        String yCol = req.getYColumn();
        String valCol = req.getValueColumn();
        if (isBlank(xCol) || isBlank(yCol) || isBlank(valCol)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecciona columnas X, Y y Valor.");
        }
        requireMapped(parser, xCol);
        requireMapped(parser, yCol);
        requireMapped(parser, valCol);
        List<UniversalFilter> filters = normalizeFilters(parser, req);

        Map<String, BigDecimal> xTotals = new HashMap<>();
        Map<String, BigDecimal> yTotals = new HashMap<>();
        Map<String, Map<String, BigDecimal>> matrix = new HashMap<>();

        int rows = 0;
        int used = 0;
        int badNums = 0;
        List<String> badNumSamples = new ArrayList<>();
        for (CSVRecord record : parser) {
            rows++;
            if (rows > MAX_ROWS) break;
            if (!matchesFilters(record, filters)) continue;

            String x = clean(get(record, xCol));
            String y = clean(get(record, yCol));
            String valRaw = clean(get(record, valCol));
            BigDecimal v = parseDecimal(valRaw);
            if (x == null || y == null || v == null) {
                if (v == null && valRaw != null) {
                    badNums++;
                    if (badNumSamples.size() < 3) badNumSamples.add(valRaw);
                }
                continue;
            }

            xTotals.put(x, xTotals.getOrDefault(x, BigDecimal.ZERO).add(v));
            yTotals.put(y, yTotals.getOrDefault(y, BigDecimal.ZERO).add(v));
            matrix.computeIfAbsent(y, k -> new HashMap<>()).put(x, matrix.getOrDefault(y, Map.of()).getOrDefault(x, BigDecimal.ZERO).add(v));
            used++;
        }
        if (used == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No hay filas válidas para heatmap (revisa X/Y/valor o filtro).");
        }

        List<String> xLabels = topKeysByValue(xTotals, HEATMAP_MAX_X);
        List<String> yLabels = topKeysByValue(yTotals, HEATMAP_MAX_Y);
        Map<String, Integer> xIndex = new HashMap<>();
        Map<String, Integer> yIndex = new HashMap<>();
        for (int i = 0; i < xLabels.size(); i++) xIndex.put(xLabels.get(i), i);
        for (int i = 0; i < yLabels.size(); i++) yIndex.put(yLabels.get(i), i);

        List<List<Number>> points = new ArrayList<>();
        for (String y : yLabels) {
            Map<String, BigDecimal> row = matrix.getOrDefault(y, Map.of());
            for (String x : xLabels) {
                BigDecimal v = row.get(x);
                if (v == null) continue;
                points.add(List.of(xIndex.get(x), yIndex.get(y), v.setScale(2, RoundingMode.HALF_UP)));
            }
        }

        Map<String, Object> s = Map.of("name", "Heatmap", "data", points);
        List<String> warnings = new ArrayList<>();
        addWarnIf(warnings, rows > MAX_ROWS, "Dataset recortado: se analizaron " + rows + " filas (límite " + MAX_ROWS + ").");
        addWarnIf(warnings, badNums > 0, "Números no parseables en '" + valCol + "': " + badNums + sampleSuffix(badNumSamples) + ".");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("rowsUsed", used);
        meta.put("rowsScanned", rows);
        meta.put("truncated", rows > MAX_ROWS);
        meta.put("filters", filtersToLineage(filters));
        meta.put("xColumn", xCol);
        meta.put("yColumn", yCol);
        meta.put("valueColumn", valCol);
        meta.put("yLabels", yLabels);
        meta.put("filterColumn", clean(req.getFilterColumn()));
        meta.put("filterValue", clean(req.getFilterValue()));
        meta.put("badNumberCount", badNums);
        if (rows > 0) meta.put("badNumberPct", roundPct(badNums, rows));
        if (!warnings.isEmpty()) meta.put("warnings", warnings);
        return new UniversalChartDataDto("HEATMAP", xLabels, List.of(s), meta);
    }

    private UniversalChartDataDto buildPivotMonthly(CSVParser parser, List<String> headers, UniversalViewRequest req) {
        String dateCol = req.getDateColumn();
        String catCol = req.getCategoryColumn();
        String valCol = req.getValueColumn();
        if (isBlank(dateCol) || isBlank(catCol) || isBlank(valCol)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecciona columna fecha, categoría y valor.");
        }
        requireMapped(parser, dateCol);
        requireMapped(parser, catCol);
        requireMapped(parser, valCol);
        List<UniversalFilter> filters = normalizeFilters(parser, req);
        String agg = normAgg(req.getAggregation());
        int topN = clamp(req.getTopN(), 1, 30, DEFAULT_TOP_N);

        Map<YearMonth, Map<String, BigDecimal>> sums = new HashMap<>();
        Map<YearMonth, Map<String, Integer>> counts = new HashMap<>();
        Map<String, BigDecimal> categoryTotals = new HashMap<>();

        int rows = 0;
        int used = 0;
        int badDates = 0;
        int badNums = 0;
        List<String> badDateSamples = new ArrayList<>();
        List<String> badNumSamples = new ArrayList<>();
        for (CSVRecord record : parser) {
            rows++;
            if (rows > MAX_ROWS) break;
            if (!matchesFilters(record, filters)) continue;

            String dateRaw = clean(get(record, dateCol));
            YearMonth ym = parseYearMonth(dateRaw);
            String cat = clean(get(record, catCol));
            String valRaw = clean(get(record, valCol));
            BigDecimal v = parseDecimal(valRaw);
            if (ym == null) {
                badDates++;
                if (badDateSamples.size() < 3 && dateRaw != null) badDateSamples.add(dateRaw);
            }
            if (v == null) {
                badNums++;
                if (badNumSamples.size() < 3 && valRaw != null) badNumSamples.add(valRaw);
            }
            if (ym == null || cat == null || v == null) continue;

            sums.computeIfAbsent(ym, k -> new HashMap<>()).put(cat, sums.getOrDefault(ym, Map.of()).getOrDefault(cat, BigDecimal.ZERO).add(v));
            counts.computeIfAbsent(ym, k -> new HashMap<>()).put(cat, counts.getOrDefault(ym, Map.of()).getOrDefault(cat, 0) + 1);
            categoryTotals.put(cat, categoryTotals.getOrDefault(cat, BigDecimal.ZERO).add(v));
            used++;
        }
        if (used == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, explainNoRows("pivote", dateCol, valCol, badDates, badDateSamples, badNums, badNumSamples));
        }

        List<String> months = new ArrayList<>(sums.keySet()).stream().filter(Objects::nonNull).map(YearMonth::toString).sorted().toList();
        List<String> categories = topKeysByValue(categoryTotals, topN);

        List<Map<String, Object>> series = new ArrayList<>();
        for (String cat : categories) {
            List<Number> data = new ArrayList<>(months.size());
            for (String m : months) {
                YearMonth ym = YearMonth.parse(m);
                BigDecimal cell = sums.getOrDefault(ym, Map.of()).getOrDefault(cat, BigDecimal.ZERO);
                if ("avg".equals(agg)) {
                    int n = Math.max(1, counts.getOrDefault(ym, Map.of()).getOrDefault(cat, 1));
                    cell = cell.divide(BigDecimal.valueOf(n), 6, RoundingMode.HALF_UP);
                }
                data.add(cell.setScale(2, RoundingMode.HALF_UP));
            }
            series.add(Map.of("name", cat, "data", data));
        }

        List<String> warnings = new ArrayList<>();
        addWarnIf(warnings, rows > MAX_ROWS, "Dataset recortado: se analizaron " + rows + " filas (límite " + MAX_ROWS + ").");
        addWarnIf(warnings, badDates > 0, "Fechas no parseables en '" + dateCol + "': " + badDates + sampleSuffix(badDateSamples) + ".");
        addWarnIf(warnings, badNums > 0, "Números no parseables en '" + valCol + "': " + badNums + sampleSuffix(badNumSamples) + ".");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("aggregation", agg);
        meta.put("rowsUsed", used);
        meta.put("rowsScanned", rows);
        meta.put("truncated", rows > MAX_ROWS);
        meta.put("filters", filtersToLineage(filters));
        meta.put("dateColumn", dateCol);
        meta.put("categoryColumn", catCol);
        meta.put("valueColumn", valCol);
        meta.put("topN", topN);
        meta.put("filterColumn", clean(req.getFilterColumn()));
        meta.put("filterValue", clean(req.getFilterValue()));
        meta.put("badDateCount", badDates);
        meta.put("badNumberCount", badNums);
        if (rows > 0) {
            meta.put("badDatePct", roundPct(badDates, rows));
            meta.put("badNumberPct", roundPct(badNums, rows));
        }
        if (!warnings.isEmpty()) meta.put("warnings", warnings);
        return new UniversalChartDataDto("PIVOT_MONTHLY", months, series, meta);
    }

    private static void requireMapped(CSVParser parser, String header) {
        if (parser.getHeaderMap() == null || !parser.getHeaderMap().containsKey(header)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Columna no encontrada: " + header);
        }
    }

    private static List<UniversalFilter> normalizeFilters(CSVParser parser, UniversalViewRequest req) {
        List<UniversalFilter> out = new ArrayList<>();
        if (req != null && req.getFilters() != null) {
            for (UniversalFilter f : req.getFilters()) {
                if (f == null) continue;
                String col = clean(f.getColumn());
                String op = clean(f.getOp());
                String val = clean(f.getValue());
                if (col == null || op == null || val == null) continue;
                requireMapped(parser, col);
                UniversalFilter nf = new UniversalFilter();
                nf.setColumn(col);
                nf.setOp(normFilterOp(op));
                nf.setValue(val);
                out.add(nf);
            }
        }

        // Legacy single filter (backwards compatible)
        String legacyCol = clean(req == null ? null : req.getFilterColumn());
        String legacyVal = clean(req == null ? null : req.getFilterValue());
        if (legacyCol != null) {
            requireMapped(parser, legacyCol);
            if (legacyVal == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filtro incompleto: falta valor del filtro.");
            UniversalFilter lf = new UniversalFilter();
            lf.setColumn(legacyCol);
            lf.setOp("eq");
            lf.setValue(legacyVal);
            out.add(lf);
        }

        return out;
    }

    private static List<Map<String, String>> filtersToLineage(List<UniversalFilter> filters) {
        if (filters == null || filters.isEmpty()) return List.of();
        List<Map<String, String>> out = new ArrayList<>();
        for (UniversalFilter f : filters) {
            if (f == null) continue;
            String col = clean(f.getColumn());
            String op = normFilterOp(f.getOp());
            String val = clean(f.getValue());
            if (col == null || op == null || val == null) continue;
            Map<String, String> m = new LinkedHashMap<>();
            m.put("column", col);
            m.put("op", op);
            m.put("value", val.length() > 120 ? val.substring(0, 120) + "…" : val);
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> requestLineage(UniversalViewRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (req == null) return out;

        String name = clean(req.getName());
        String type = normType(req.getType());
        if (name != null) out.put("name", name);
        if (type != null) out.put("type", type);

        String dateCol = clean(req.getDateColumn());
        String valueCol = clean(req.getValueColumn());
        String categoryCol = clean(req.getCategoryColumn());
        String xCol = clean(req.getXColumn());
        String yCol = clean(req.getYColumn());
        String aggregation = clean(req.getAggregation());

        if (dateCol != null) out.put("dateColumn", dateCol);
        if (valueCol != null) out.put("valueColumn", valueCol);
        if (categoryCol != null) out.put("categoryColumn", categoryCol);
        if (xCol != null) out.put("xColumn", xCol);
        if (yCol != null) out.put("yColumn", yCol);
        if (aggregation != null) out.put("aggregation", aggregation);

        if (req.getTopN() != null) out.put("topN", req.getTopN());
        if (req.getMaxPoints() != null) out.put("maxPoints", req.getMaxPoints());

        List<Map<String, String>> filters = filtersToLineage(req.getFilters());
        if (!filters.isEmpty()) out.put("filters", filters);

        String legacyCol = clean(req.getFilterColumn());
        String legacyVal = clean(req.getFilterValue());
        if (legacyCol != null || legacyVal != null) {
            Map<String, String> legacy = new LinkedHashMap<>();
            if (legacyCol != null) legacy.put("column", legacyCol);
            legacy.put("op", "eq");
            if (legacyVal != null) legacy.put("value", legacyVal.length() > 120 ? legacyVal.substring(0, 120) + "…" : legacyVal);
            out.put("legacyFilter", legacy);
        }
        return out;
    }

    private static boolean matchesFilters(CSVRecord record, List<UniversalFilter> filters) {
        if (filters == null || filters.isEmpty()) return true;
        for (UniversalFilter f : filters) {
            if (!matchesOneFilter(record, f)) return false;
        }
        return true;
    }

    private static boolean matchesOneFilter(CSVRecord record, UniversalFilter f) {
        if (record == null || f == null) return true;
        String col = clean(f.getColumn());
        String op = normFilterOp(f.getOp());
        String wanted = clean(f.getValue());
        if (col == null || op == null || wanted == null) return true;
        String actual = clean(get(record, col));

        if ("month_eq".equals(op)) {
            YearMonth w = parseYearMonth(wanted);
            if (w == null) return false;
            YearMonth a = parseYearMonth(actual);
            return a != null && a.equals(w);
        }

        if ("year_eq".equals(op)) {
            Integer y = parseIntSafe(wanted);
            if (y == null) return false;
            YearMonth ym = parseYearMonth(actual);
            return ym != null && ym.getYear() == y;
        }

        if ("contains".equals(op)) {
            if (actual == null) return false;
            return actual.toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT));
        }

        if ("eq".equals(op)) {
            if (actual == null) return false;
            return actual.equalsIgnoreCase(wanted);
        }

        if (List.of("gt", "gte", "lt", "lte").contains(op)) {
            BigDecimal a = parseDecimal(actual);
            BigDecimal b = parseDecimal(wanted);
            if (a == null || b == null) return false;
            int cmp = a.compareTo(b);
            return switch (op) {
                case "gt" -> cmp > 0;
                case "gte" -> cmp >= 0;
                case "lt" -> cmp < 0;
                case "lte" -> cmp <= 0;
                default -> true;
            };
        }

        return true;
    }

    private static String normFilterOp(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "eq", "equals" -> "eq";
            case "contains", "like" -> "contains";
            case "month", "month_eq", "monthEquals" -> "month_eq";
            case "year", "year_eq", "yearEquals" -> "year_eq";
            case "gt" -> "gt";
            case "gte" -> "gte";
            case "lt" -> "lt";
            case "lte" -> "lte";
            default -> "eq";
        };
    }

    private static Integer parseIntSafe(String raw) {
        try {
            if (raw == null) return null;
            String s = raw.trim();
            if (s.isBlank()) return null;
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String get(CSVRecord record, String header) {
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
        return s.isBlank() ? null : s;
    }

    static BigDecimal parseDecimal(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replace(" ", "").trim();
        boolean negativeParens = cleaned.startsWith("(") && cleaned.endsWith(")");
        if (negativeParens) cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        int comma = cleaned.lastIndexOf(',');
        int dot = cleaned.lastIndexOf('.');
        if (comma > dot) {
            cleaned = cleaned.replace(".", "").replace(",", ".");
        } else {
            cleaned = cleaned.replace(",", "");
        }
        cleaned = cleaned.replace("\u20AC", "").replace("$", "").replace("£", "").replace("¥", "").trim();
        if (cleaned.isBlank()) return null;
        try {
            BigDecimal v = new BigDecimal(cleaned);
            return negativeParens ? v.negate() : v;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static YearMonth parseYearMonth(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isBlank()) return null;
        YearMonth direct = parseLooseYearMonth(s);
        if (direct != null) return direct;

        LocalDate d = parseFlexibleDate(s);
        if (d != null) {
            return YearMonth.of(d.getYear(), d.getMonth());
        }
        return null;
    }

    static LocalDate parseFlexibleDate(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isBlank()) return null;
        for (DateTimeFormatter f : FLEX_DATES) {
            try {
                return LocalDate.parse(s, f);
            } catch (DateTimeParseException ignored) {}
        }
        return null;
    }

    private static YearMonth parseLooseYearMonth(String raw) {
        String s = cleanMonthText(raw);
        if (s == null) return null;

        try {
            if (s.matches("\\d{4}-\\d{2}")) return YearMonth.parse(s);
        } catch (Exception ignored) {}

        Matcher numeric = YEAR_MONTH_NUMERIC.matcher(s);
        if (numeric.matches()) {
            return buildYearMonth(parseIntSafe(numeric.group(1)), parseIntSafe(numeric.group(2)));
        }

        Matcher reversed = YEAR_MONTH_NUMERIC_REVERSED.matcher(s);
        if (reversed.matches()) {
            return buildYearMonth(parseIntSafe(reversed.group(2)), parseIntSafe(reversed.group(1)));
        }

        Matcher textual = YEAR_MONTH_TEXT.matcher(s);
        if (textual.matches()) {
            String yearRaw = textual.group("b") != null ? textual.group("b") : textual.group("c");
            String monthRaw = textual.group("a") != null ? textual.group("a") : textual.group("d");
            Integer year = parseIntSafe(yearRaw);
            Integer month = monthFromAlias(monthRaw);
            return buildYearMonth(year, month);
        }

        return null;
    }

    private static YearMonth buildYearMonth(Integer year, Integer month) {
        if (year == null || month == null) return null;
        if (month < 1 || month > 12) return null;
        try {
            return YearMonth.of(year, month);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Integer monthFromAlias(String raw) {
        String normalized = cleanMonthText(raw);
        if (normalized == null) return null;
        return MONTH_ALIASES.get(normalized);
    }

    private static String cleanMonthText(String raw) {
        if (raw == null) return null;
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT)
            .replace('.', ' ')
            .trim()
            .replaceAll("\\s+", " ");
        return normalized.isBlank() ? null : normalized;
    }

    private static Map<String, Integer> buildMonthAliases() {
        Map<String, Integer> out = new HashMap<>();
        addMonthAlias(out, 1, "jan", "january", "ene", "enero");
        addMonthAlias(out, 2, "feb", "february", "febrero");
        addMonthAlias(out, 3, "mar", "march", "marzo");
        addMonthAlias(out, 4, "apr", "april", "abr", "abril");
        addMonthAlias(out, 5, "may", "mayo");
        addMonthAlias(out, 6, "jun", "june", "junio");
        addMonthAlias(out, 7, "jul", "july", "julio");
        addMonthAlias(out, 8, "aug", "august", "ago", "agosto");
        addMonthAlias(out, 9, "sep", "sept", "september", "set", "septiembre", "setiembre");
        addMonthAlias(out, 10, "oct", "october", "octubre");
        addMonthAlias(out, 11, "nov", "november", "noviembre");
        addMonthAlias(out, 12, "dec", "december", "dic", "diciembre");
        return out;
    }

    private static void addMonthAlias(Map<String, Integer> out, int month, String... aliases) {
        for (String alias : aliases) {
            out.put(alias, month);
        }
    }

    private static String normAgg(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if ("avg".equals(s) || "mean".equals(s)) return "avg";
        return "sum";
    }

    private static String normType(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (s.isBlank()) return null;
        if (s.contains("TIME")) return "TIME_SERIES";
        if (s.contains("CATEGORY") || s.contains("BAR")) return "CATEGORY_BAR";
        if (s.contains("KPI")) return "KPI_CARDS";
        if (s.contains("SCATTER")) return "SCATTER";
        if (s.contains("HEATMAP")) return "HEATMAP";
        if (s.contains("PIVOT")) return "PIVOT_MONTHLY";
        return null;
    }

    private static int clamp(Integer v, int min, int max, int fallback) {
        if (v == null) return fallback;
        int n = v;
        if (n < min) return min;
        if (n > max) return max;
        return n;
    }

    private static List<String> topKeysByValue(Map<String, BigDecimal> totals, int limit) {
        if (totals == null || totals.isEmpty()) return List.of();
        List<Map.Entry<String, BigDecimal>> ordered = new ArrayList<>(totals.entrySet());
        ordered.sort((a, b) -> b.getValue().abs().compareTo(a.getValue().abs()));
        if (ordered.size() > limit) ordered = ordered.subList(0, limit);
        return ordered.stream().map(Map.Entry::getKey).toList();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isBlank();
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

    private static String safeMsg(Exception ex) {
        try {
            String m = ex.getMessage();
            if (m == null) return "error";
            m = m.replace("\r", " ").replace("\n", " ").trim();
            if (m.length() > 160) m = m.substring(0, 160) + "\u2026";
            return m;
        } catch (Exception ignored) {
            return "error";
        }
    }

    private static String explainNoRows(String kind, String dateCol, String valCol, int badDates, List<String> badDateSamples, int badNums, List<String> badNumSamples) {
        StringBuilder sb = new StringBuilder();
        sb.append("No hay filas válidas para ").append(kind).append(".");
        if (badDates > 0 && !isBlank(dateCol)) {
            sb.append(" No pude parsear fechas en '").append(dateCol).append("' (").append(badDates).append(").");
            if (badDateSamples != null && !badDateSamples.isEmpty()) sb.append(" Ej: ").append(String.join(", ", badDateSamples.stream().map(UniversalViewService::q).toList())).append(".");
            sb.append(" Formatos: YYYY-MM, YYYY-MM-DD, d/M/aaaa, dd/MM/aaaa, d-M-aaaa, dd-MM-aaaa.");
        }
        if (badNums > 0 && !isBlank(valCol)) {
            sb.append(" No pude parsear números en '").append(valCol).append("' (").append(badNums).append(").");
            if (badNumSamples != null && !badNumSamples.isEmpty()) sb.append(" Ej: ").append(String.join(", ", badNumSamples.stream().map(UniversalViewService::q).toList())).append(".");
        }
        sb.append(" Revisa columnas/filtros.");
        return sb.toString();
    }

    private static String explainNoRowsScatter(String xCol, String yCol, int badX, List<String> badXSamples, int badY, List<String> badYSamples) {
        StringBuilder sb = new StringBuilder();
        sb.append("No hay filas válidas para scatter (X/Y o filtro).");
        if (badX > 0) {
            sb.append(" X no parseable en '").append(xCol).append("' (").append(badX).append(").");
            if (badXSamples != null && !badXSamples.isEmpty()) sb.append(" Ej: ").append(String.join(", ", badXSamples.stream().map(UniversalViewService::q).toList())).append(".");
        }
        if (badY > 0) {
            sb.append(" Y no parseable en '").append(yCol).append("' (").append(badY).append(").");
            if (badYSamples != null && !badYSamples.isEmpty()) sb.append(" Ej: ").append(String.join(", ", badYSamples.stream().map(UniversalViewService::q).toList())).append(".");
        }
        return sb.toString();
    }

    private static String q(String s) {
        if (s == null) return "''";
        String v = s.replace("\r", " ").replace("\n", " ").trim();
        if (v.length() > 30) v = v.substring(0, 30) + "\u2026";
        return "'" + v + "'";
    }

    private static void addWarnIf(List<String> warnings, boolean cond, String msg) {
        if (!cond) return;
        if (warnings == null) return;
        String m = msg == null ? "" : msg.trim();
        if (m.isEmpty()) return;
        warnings.add(m);
    }

    private static String sampleSuffix(List<String> samples) {
        if (samples == null || samples.isEmpty()) return "";
        return " (ej: " + String.join(", ", samples.stream().map(UniversalViewService::q).toList()) + ")";
    }

    private static double roundPct(int n, int denom) {
        if (denom <= 0 || n <= 0) return 0.0;
        double v = (100.0 * n) / denom;
        return Math.round(v * 100.0) / 100.0;
    }
}



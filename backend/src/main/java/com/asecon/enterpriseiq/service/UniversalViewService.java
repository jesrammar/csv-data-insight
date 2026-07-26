package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.UniversalChartDataDto;
import com.asecon.enterpriseiq.dto.UniversalColumnDto;
import com.asecon.enterpriseiq.dto.UniversalDetectedEntityDto;
import com.asecon.enterpriseiq.dto.UniversalEvidenceDto;
import com.asecon.enterpriseiq.dto.UniversalFilter;
import com.asecon.enterpriseiq.dto.UniversalSummaryDto;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    public UniversalViewRequest canonicalizeRequest(UniversalViewRequest request, Long companyId, Long importId) {
        UniversalImport imp = resolveImport(companyId, importId);
        return canonicalizeRequest(request, decodeSummary(imp));
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

            UniversalSummaryDto summary = decodeSummary(latestImp);
            SemanticContext semantic = semanticContext(summary);
            UniversalViewRequest normalizedRequest = canonicalizeRequest(request, summary);
            UniversalChartDataDto out;
            if ("TIME_SERIES".equals(type)) {
                out = buildTimeSeries(parser, headers, normalizedRequest, semantic);
            } else if ("CATEGORY_BAR".equals(type)) {
                out = buildCategoryBar(parser, headers, normalizedRequest, semantic);
            } else if ("KPI_CARDS".equals(type)) {
                out = buildKpiCards(parser, headers, normalizedRequest, semantic);
            } else if ("SCATTER".equals(type)) {
                out = buildScatter(parser, headers, normalizedRequest, semantic);
            } else if ("HEATMAP".equals(type)) {
                out = buildHeatmap(parser, headers, normalizedRequest, semantic);
            } else if ("PIVOT_MONTHLY".equals(type)) {
                out = buildPivotMonthly(parser, headers, normalizedRequest, semantic);
            } else {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo no soportado.");
            }

            Map<String, Object> meta = out.meta() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(out.meta());
            meta.putIfAbsent("request", requestLineage(normalizedRequest));
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
        UniversalImport imp = null;
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
        imp = fellBackToLatest
            ? universalImportFileService.latest(companyId).orElse(null)
            : (sourceUniversalImportId == null
                ? universalImportFileService.latest(companyId).orElse(null)
                : universalImportFileService.find(companyId, sourceUniversalImportId).orElse(null));

        UniversalSummaryDto summary = decodeSummary(imp);
        UniversalViewRequest normalizedRequest = canonicalizeRequest(request, summary);
        UniversalChartDataDto out = previewBytes(bytes, normalizedRequest, summary);
        Map<String, Object> meta = out.meta() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(out.meta());
        meta.putIfAbsent("request", requestLineage(normalizedRequest));

        if (fellBackToLatest) {
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
        UniversalSummaryDto summary = decodeSummary(resolveImport(companyId, importId));
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Config vacío.");
        UniversalViewRequest normalizedRequest = canonicalizeRequest(request, summary);
        String type = normType(normalizedRequest.getType());
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

            List<UniversalFilter> filters = normalizeFilters(parser, normalizedRequest);
            ProblemColumns pc = problemColumns(type, normalizedRequest);
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
        UniversalSummaryDto summary = decodeSummary(resolveImport(companyId, importId));
        UniversalViewRequest normalizedRequest = canonicalizeRequest(request, summary);
        String type = normType(normalizedRequest.getType());
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
            List<UniversalFilter> filters = normalizeFilters(parser, normalizedRequest);

            EvidenceSpec spec = evidenceSpec(type, normalizedRequest, focusLabel);
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
            meta.put("request", requestLineage(normalizedRequest));
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
        boolean requireValue = modeNeedsValueColumn(req);

        if ("TIME_SERIES".equals(t)) {
            String dateCol = clean(req.getDateColumn());
            String valCol = clean(req.getValueColumn());
            if (isBlank(dateCol) || (requireValue && isBlank(valCol))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, requireValue
                    ? "Selecciona columna fecha y valor."
                    : "Selecciona columna fecha.");
            }
            if (isBlank(focusLabel)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta focusLabel (YYYY-MM).");
            }
            YearMonth ym = parseYearMonth(String.valueOf(focusLabel).trim());
            if (ym == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "focusLabel inválido. Usa YYYY-MM.");
            String ymKey = ym.toString();
            List<String> requiredColumns = requireValue ? List.of(dateCol, valCol) : List.of(dateCol);
            List<String> importantColumns = requireValue ? List.of(dateCol, valCol) : List.of(dateCol);
            return new EvidenceSpec(
                requiredColumns,
                importantColumns,
                requireValue,
                requireValue ? valCol : null,
                (r) -> {
                    YearMonth k = parseYearMonth(clean(get(r, dateCol)));
                    return k != null && ymKey.equals(k.toString());
                },
                requireValue
                    ? Map.of("dateColumn", dateCol, "valueColumn", valCol, "bucket", ymKey)
                    : Map.of("dateColumn", dateCol, "bucket", ymKey)
            );
        }

        if ("CATEGORY_BAR".equals(t)) {
            String catCol = clean(req.getCategoryColumn());
            String valCol = clean(req.getValueColumn());
            if (isBlank(catCol) || (requireValue && isBlank(valCol))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecciona columna categoría y valor.");
            }
            if (isBlank(focusLabel)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta focusLabel (categoría).");
            }
            String key = String.valueOf(focusLabel).trim();
            List<String> requiredColumns = requireValue ? List.of(catCol, valCol) : List.of(catCol);
            List<String> importantColumns = requireValue ? List.of(catCol, valCol) : List.of(catCol);
            return new EvidenceSpec(
                requiredColumns,
                importantColumns,
                requireValue,
                requireValue ? valCol : null,
                (r) -> Objects.equals(clean(get(r, catCol)), key),
                requireValue
                    ? Map.of("categoryColumn", catCol, "valueColumn", valCol, "category", key)
                    : Map.of("categoryColumn", catCol, "category", key)
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
            if (isBlank(xCol) || isBlank(yCol) || (requireValue && isBlank(valCol))) {
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
            List<String> requiredColumns = requireValue ? List.of(xCol, yCol, valCol) : List.of(xCol, yCol);
            List<String> importantColumns = requireValue ? List.of(xCol, yCol, valCol) : List.of(xCol, yCol);
            return new EvidenceSpec(
                requiredColumns,
                importantColumns,
                requireValue,
                requireValue ? valCol : null,
                (r) -> Objects.equals(clean(get(r, xCol)), xLabel) && Objects.equals(clean(get(r, yCol)), yLabel),
                requireValue
                    ? Map.of("xColumn", xCol, "yColumn", yCol, "valueColumn", valCol, "xLabel", xLabel, "yLabel", yLabel)
                    : Map.of("xColumn", xCol, "yColumn", yCol, "xLabel", xLabel, "yLabel", yLabel)
            );
        }

        if ("PIVOT_MONTHLY".equals(t)) {
            String dateCol = clean(req.getDateColumn());
            String catCol = clean(req.getCategoryColumn());
            String valCol = clean(req.getValueColumn());
            if (isBlank(dateCol) || isBlank(catCol) || (requireValue && isBlank(valCol))) {
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
            List<String> requiredColumns = requireValue ? List.of(dateCol, catCol, valCol) : List.of(dateCol, catCol);
            List<String> importantColumns = requireValue ? List.of(dateCol, catCol, valCol) : List.of(dateCol, catCol);
            return new EvidenceSpec(
                requiredColumns,
                importantColumns,
                requireValue,
                requireValue ? valCol : null,
                (r) -> {
                    YearMonth k = parseYearMonth(clean(get(r, dateCol)));
                    return k != null && ymKey.equals(k.toString()) && Objects.equals(clean(get(r, catCol)), cat);
                },
                requireValue
                    ? Map.of("dateColumn", dateCol, "categoryColumn", catCol, "valueColumn", valCol, "category", cat, "bucket", ymKey)
                    : Map.of("dateColumn", dateCol, "categoryColumn", catCol, "category", cat, "bucket", ymKey)
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
        String valueColumn = modeNeedsValueColumn(req) ? clean(req.getValueColumn()) : null;
        return switch (type) {
            case "TIME_SERIES" -> new ProblemColumns(clean(req.getDateColumn()), valueColumn, null, null);
            case "CATEGORY_BAR" -> new ProblemColumns(null, valueColumn, null, null);
            case "KPI_CARDS" -> new ProblemColumns(null, valueColumn, null, null);
            case "PIVOT_MONTHLY" -> new ProblemColumns(clean(req.getDateColumn()), valueColumn, null, null);
            case "SCATTER" -> new ProblemColumns(null, null, clean(req.getXColumn()), clean(req.getYColumn()));
            case "HEATMAP" -> new ProblemColumns(null, valueColumn, clean(req.getXColumn()), clean(req.getYColumn()));
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

    private record SemanticContext(
        Map<String, UniversalColumnDto> columnsByName,
        Map<String, UniversalDetectedEntityDto> entitiesByType,
        String rowGranularity,
        String entryKeyColumn,
        String documentKeyColumn,
        String invoiceKeyColumn,
        String partyKeyColumn,
        String debitColumn,
        String creditColumn,
        String signedAmountColumn
    ) {
        UniversalColumnDto column(String name) {
            if (name == null || columnsByName == null) return null;
            return columnsByName.get(name.trim().toLowerCase(Locale.ROOT));
        }
    }

    private record AggregationSelection(
        String mode,
        String legacyAggregation,
        String measureColumn,
        String secondaryMeasureColumn,
        String distinctKeyColumn,
        String dedupKeyColumn,
        String unitLabel,
        List<String> warnings
    ) {}

    private static final class AggregateState {
        int rowsMatched;
        int numericCount;
        int missingKeyCount;
        int invalidNumberCount;
        int dedupConflictCount;
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal min;
        BigDecimal max;
        final Set<String> distinctKeys = new HashSet<>();
        final Map<String, BigDecimal> dedupValues = new LinkedHashMap<>();
    }

    private UniversalImport resolveImport(Long companyId, Long importId) {
        if (companyId == null || universalImportFileService == null) return null;
        try {
            if (importId == null) {
                return universalImportFileService.latest(companyId).orElse(null);
            }
            return universalImportFileService.find(companyId, importId)
                .orElseGet(() -> universalImportFileService.latest(companyId).orElse(null));
        } catch (Exception ignored) {
            return null;
        }
    }

    UniversalViewRequest canonicalizeRequest(UniversalViewRequest request, UniversalSummaryDto summary) {
        return canonicalizeRequest(request, semanticContext(summary));
    }

    private static UniversalViewRequest canonicalizeRequest(UniversalViewRequest request, SemanticContext semantic) {
        UniversalViewRequest out = copyRequest(request);
        String type = normType(out.getType());
        if (type == null) return out;
        out.setType(type);

        AggregationSelection selection = resolveAggregationSelection(type, out, semantic);
        out.setAggregationMode(selection.mode());
        out.setAggregation(selection.legacyAggregation());
        if (!isBlank(selection.measureColumn())) out.setValueColumn(selection.measureColumn());
        return out;
    }

    private static UniversalViewRequest copyRequest(UniversalViewRequest request) {
        UniversalViewRequest out = new UniversalViewRequest();
        if (request == null) return out;
        out.setName(clean(request.getName()));
        out.setType(clean(request.getType()));
        out.setDateColumn(clean(request.getDateColumn()));
        out.setValueColumn(clean(request.getValueColumn()));
        out.setCategoryColumn(clean(request.getCategoryColumn()));
        out.setXColumn(clean(request.getXColumn()));
        out.setYColumn(clean(request.getYColumn()));
        out.setAggregation(clean(request.getAggregation()));
        out.setAggregationMode(clean(request.getAggregationMode()));
        out.setFilterColumn(clean(request.getFilterColumn()));
        out.setFilterValue(clean(request.getFilterValue()));
        if (request.getFilters() != null) {
            List<UniversalFilter> filters = new ArrayList<>();
            for (UniversalFilter filter : request.getFilters()) {
                if (filter == null) continue;
                UniversalFilter copy = new UniversalFilter();
                copy.setColumn(clean(filter.getColumn()));
                copy.setOp(clean(filter.getOp()));
                copy.setValue(clean(filter.getValue()));
                filters.add(copy);
            }
            out.setFilters(filters);
        }
        out.setTopN(request.getTopN());
        out.setMaxPoints(request.getMaxPoints());
        return out;
    }

    private UniversalSummaryDto decodeSummary(UniversalImport imp) {
        if (imp == null || isBlank(imp.getSummaryJson())) return null;
        try {
            return objectMapper.readValue(imp.getSummaryJson(), UniversalSummaryDto.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static SemanticContext semanticContext(UniversalSummaryDto summary) {
        if (summary == null) {
            return new SemanticContext(Map.of(), Map.of(), null, null, null, null, null, null, null, null);
        }

        Map<String, UniversalColumnDto> columnsByName = new HashMap<>();
        if (summary.columns() != null) {
            for (UniversalColumnDto column : summary.columns()) {
                String name = clean(column == null ? null : column.name());
                if (name != null) columnsByName.put(name.toLowerCase(Locale.ROOT), column);
            }
        }

        Map<String, UniversalDetectedEntityDto> entitiesByType = new HashMap<>();
        if (summary.detectedEntities() != null) {
            for (UniversalDetectedEntityDto entity : summary.detectedEntities()) {
                String type = upper(entity == null ? null : entity.entityType());
                if (type != null) entitiesByType.put(type, entity);
            }
        }

        return new SemanticContext(
            columnsByName,
            entitiesByType,
            clean(summary.rowGranularity()),
            entityKey(summary, "ENTRY"),
            entityKey(summary, "DOCUMENT"),
            entityKey(summary, "INVOICE"),
            entityKey(summary, "PARTY"),
            firstSemanticColumn(summary, "DEBIT_AMOUNT"),
            firstSemanticColumn(summary, "CREDIT_AMOUNT"),
            firstSemanticColumn(summary, "SIGNED_AMOUNT")
        );
    }

    private static String entityKey(UniversalSummaryDto summary, String entityType) {
        if (summary == null || summary.detectedEntities() == null) return null;
        String wanted = upper(entityType);
        for (UniversalDetectedEntityDto entity : summary.detectedEntities()) {
            if (entity == null) continue;
            if (Objects.equals(wanted, upper(entity.entityType()))) return clean(entity.keyColumn());
        }
        return null;
    }

    private static String firstSemanticColumn(UniversalSummaryDto summary, String... semanticTypes) {
        if (summary == null || summary.columns() == null || semanticTypes == null) return null;
        for (String semanticType : semanticTypes) {
            String wanted = upper(semanticType);
            for (UniversalColumnDto column : summary.columns()) {
                if (column == null) continue;
                if (Objects.equals(wanted, upper(column.semanticType()))) return clean(column.name());
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            String cleaned = clean(value);
            if (cleaned != null) return cleaned;
        }
        return null;
    }

    private static String aggregationUnit(String mode) {
        return switch (upper(mode)) {
            case "ROW_COUNT" -> "filas";
            case "DISTINCT_ENTRY_COUNT" -> "asientos";
            case "DISTINCT_DOCUMENT_COUNT" -> "documentos";
            case "DISTINCT_INVOICE_COUNT" -> "facturas";
            case "DISTINCT_PARTY_COUNT" -> "terceros";
            case "SUM_DEBIT" -> "debe";
            case "SUM_CREDIT" -> "haber";
            case "NET_BALANCE" -> "saldo";
            case "AVG_VALUE" -> "media";
            default -> "importe";
        };
    }

    private static String normAggregationMode(String raw) {
        String value = upper(raw);
        if (value == null) return null;
        return switch (value) {
            case "ROW_COUNT", "DISTINCT_ENTRY_COUNT", "DISTINCT_DOCUMENT_COUNT", "DISTINCT_INVOICE_COUNT", "DISTINCT_PARTY_COUNT",
                "SUM_DEBIT", "SUM_CREDIT", "NET_BALANCE", "SUM_AMOUNT", "AVG_VALUE" -> value;
            case "SUM_VALUE" -> "SUM_AMOUNT";
            default -> null;
        };
    }

    private static boolean supportsDistinctValue(UniversalColumnDto column) {
        if (column == null || column.validAggregations() == null) return false;
        for (String aggregation : column.validAggregations()) {
            if (Objects.equals("SUM_DISTINCT_VALUE", upper(aggregation))) return true;
        }
        return false;
    }

    private static boolean isIssueDateColumn(SemanticContext ctx, String columnName) {
        UniversalColumnDto column = ctx == null ? null : ctx.column(columnName);
        if (column == null) return false;
        if (column.validAggregations() != null) {
            for (String aggregation : column.validAggregations()) {
                if (Objects.equals("DISTINCT_INVOICE_COUNT", upper(aggregation))) return true;
            }
        }
        String normalized = clean(columnName);
        return normalized != null && normalized.toLowerCase(Locale.ROOT).contains("emision");
    }

    private static BigDecimal parseDecimalOrZero(String raw, AggregateState state) {
        String cleaned = clean(raw);
        if (cleaned == null) return BigDecimal.ZERO;
        BigDecimal parsed = parseDecimal(cleaned);
        if (parsed == null) {
            if (state != null) state.invalidNumberCount++;
            return BigDecimal.ZERO;
        }
        return parsed;
    }

    private static void updateMinMax(AggregateState state, BigDecimal value) {
        if (state == null || value == null) return;
        if (state.min == null || value.compareTo(state.min) < 0) state.min = value;
        if (state.max == null || value.compareTo(state.max) > 0) state.max = value;
    }

    private static String upper(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isBlank() ? null : cleaned.toUpperCase(Locale.ROOT);
    }

    private static AggregationSelection resolveAggregationSelection(String chartType, UniversalViewRequest req, SemanticContext ctx) {
        String explicitMode = normAggregationMode(req == null ? null : req.getAggregationMode());
        boolean strict = explicitMode != null;
        String mode = explicitMode;
        List<String> warnings = new ArrayList<>();

        if ("SCATTER".equals(chartType)) {
            if (explicitMode != null) {
                warnings.add("El modo " + explicitMode + " no aplica a scatter: este gráfico sigue mostrando puntos fila a fila.");
            }
            return new AggregationSelection("ROW_COUNT", "sum", null, null, null, null, "puntos", warnings);
        }

        if (mode == null) {
            String legacy = normAgg(req == null ? null : req.getAggregation());
            String rawValueColumn = clean(req == null ? null : req.getValueColumn());
            UniversalColumnDto valueProfile = ctx == null ? null : ctx.column(rawValueColumn);
            String semanticType = upper(valueProfile == null ? null : valueProfile.semanticType());
            if ("avg".equals(legacy)) {
                mode = "AVG_VALUE";
            } else if ("DEBIT_AMOUNT".equals(semanticType)) {
                mode = "SUM_DEBIT";
            } else if ("CREDIT_AMOUNT".equals(semanticType)) {
                mode = "SUM_CREDIT";
            } else if ("SIGNED_AMOUNT".equals(semanticType)) {
                mode = "NET_BALANCE";
            } else if (rawValueColumn != null) {
                mode = "SUM_AMOUNT";
            } else if (!isBlank(req == null ? null : req.getDateColumn()) && ctx != null && ctx.invoiceKeyColumn() != null && isIssueDateColumn(ctx, req.getDateColumn())) {
                mode = "DISTINCT_INVOICE_COUNT";
            } else {
                mode = "ROW_COUNT";
            }
        }

        String measureColumn = clean(req == null ? null : req.getValueColumn());
        String secondaryMeasureColumn = null;
        String distinctKeyColumn = null;
        String dedupKeyColumn = null;
        String unitLabel = aggregationUnit(mode);

        switch (mode) {
            case "ROW_COUNT" -> {
                return new AggregationSelection(mode, "sum", null, null, null, null, unitLabel, warnings);
            }
            case "DISTINCT_ENTRY_COUNT" -> distinctKeyColumn = ctx == null ? null : ctx.entryKeyColumn();
            case "DISTINCT_DOCUMENT_COUNT" -> distinctKeyColumn = ctx == null ? null : ctx.documentKeyColumn();
            case "DISTINCT_INVOICE_COUNT" -> distinctKeyColumn = ctx == null ? null : ctx.invoiceKeyColumn();
            case "DISTINCT_PARTY_COUNT" -> distinctKeyColumn = ctx == null ? null : ctx.partyKeyColumn();
            case "SUM_DEBIT" -> {
                measureColumn = firstNonBlank(ctx == null ? null : ctx.debitColumn(), measureColumn);
                if (isBlank(measureColumn)) {
                    if (strict) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No encuentro una columna semántica de debe para aplicar SUM_DEBIT.");
                    measureColumn = clean(req == null ? null : req.getValueColumn());
                }
            }
            case "SUM_CREDIT" -> {
                measureColumn = firstNonBlank(ctx == null ? null : ctx.creditColumn(), measureColumn);
                if (isBlank(measureColumn)) {
                    if (strict) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No encuentro una columna semántica de haber para aplicar SUM_CREDIT.");
                    measureColumn = clean(req == null ? null : req.getValueColumn());
                }
            }
            case "NET_BALANCE" -> {
                UniversalColumnDto selected = ctx == null ? null : ctx.column(measureColumn);
                if ("SIGNED_AMOUNT".equals(upper(selected == null ? null : selected.semanticType()))) {
                    secondaryMeasureColumn = null;
                } else if (!isBlank(ctx == null ? null : ctx.signedAmountColumn())) {
                    measureColumn = ctx.signedAmountColumn();
                } else if (!isBlank(ctx == null ? null : ctx.debitColumn()) || !isBlank(ctx == null ? null : ctx.creditColumn())) {
                    measureColumn = firstNonBlank(ctx == null ? null : ctx.debitColumn(), measureColumn);
                    secondaryMeasureColumn = ctx == null ? null : ctx.creditColumn();
                }
                if (isBlank(measureColumn)) {
                    if (strict) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No encuentro columnas de saldo/debe/haber para aplicar NET_BALANCE.");
                    measureColumn = clean(req == null ? null : req.getValueColumn());
                }
            }
            case "AVG_VALUE", "SUM_AMOUNT" -> {
                measureColumn = firstNonBlank(measureColumn, ctx == null ? null : ctx.signedAmountColumn(), ctx == null ? null : ctx.debitColumn(), ctx == null ? null : ctx.creditColumn());
                if (isBlank(measureColumn)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecciona una columna de valor para calcular importe o media.");
                }
                UniversalColumnDto valueProfile = ctx == null ? null : ctx.column(measureColumn);
                if ("SUM_AMOUNT".equals(mode) && supportsDistinctValue(valueProfile)) {
                    dedupKeyColumn = firstNonBlank(ctx == null ? null : ctx.invoiceKeyColumn(), ctx == null ? null : ctx.documentKeyColumn(), ctx == null ? null : ctx.entryKeyColumn());
                    if (!isBlank(dedupKeyColumn)) {
                        warnings.add("El importe se deduplica por " + dedupKeyColumn + " para evitar doble conteo por granularidad.");
                    }
                }
            }
            default -> {
                warnings.add("Modo no reconocido. Se usa conteo de filas.");
                return new AggregationSelection("ROW_COUNT", "sum", null, null, null, null, aggregationUnit("ROW_COUNT"), warnings);
            }
        }

        if (mode.startsWith("DISTINCT_") && isBlank(distinctKeyColumn)) {
            if (strict) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No encuentro la clave necesaria para aplicar " + mode + ".");
            }
            warnings.add("No encuentro clave semántica para " + mode + ". Se usa conteo de filas.");
            return new AggregationSelection("ROW_COUNT", "sum", null, null, null, null, aggregationUnit("ROW_COUNT"), warnings);
        }

        String legacyAggregation = "AVG_VALUE".equals(mode) ? "avg" : "sum";
        return new AggregationSelection(mode, legacyAggregation, measureColumn, secondaryMeasureColumn, distinctKeyColumn, dedupKeyColumn, unitLabel, warnings);
    }

    private static boolean accumulateState(CSVRecord record, AggregationSelection selection, AggregateState state) {
        if (record == null || selection == null || state == null) return false;
        String mode = upper(selection.mode());

        switch (mode) {
            case "ROW_COUNT" -> {
                state.rowsMatched++;
                return true;
            }
            case "DISTINCT_ENTRY_COUNT", "DISTINCT_DOCUMENT_COUNT", "DISTINCT_INVOICE_COUNT", "DISTINCT_PARTY_COUNT" -> {
                String key = clean(get(record, selection.distinctKeyColumn()));
                if (key == null) {
                    state.missingKeyCount++;
                    return false;
                }
                state.rowsMatched++;
                state.distinctKeys.add(key);
                return true;
            }
            case "SUM_DEBIT", "SUM_CREDIT" -> {
                BigDecimal value = parseDecimalOrZero(get(record, selection.measureColumn()), state);
                state.rowsMatched++;
                state.numericCount++;
                state.sum = state.sum.add(value);
                updateMinMax(state, value);
                return true;
            }
            case "NET_BALANCE" -> {
                BigDecimal value;
                if (!isBlank(selection.secondaryMeasureColumn())) {
                    BigDecimal left = parseDecimalOrZero(get(record, selection.measureColumn()), state);
                    BigDecimal right = parseDecimalOrZero(get(record, selection.secondaryMeasureColumn()), state);
                    value = left.subtract(right);
                } else {
                    String raw = clean(get(record, selection.measureColumn()));
                    BigDecimal parsed = parseDecimal(raw);
                    if (parsed == null) {
                        if (raw != null) state.invalidNumberCount++;
                        return false;
                    }
                    value = parsed;
                }
                state.rowsMatched++;
                state.numericCount++;
                state.sum = state.sum.add(value);
                updateMinMax(state, value);
                return true;
            }
            case "AVG_VALUE", "SUM_AMOUNT" -> {
                String raw = clean(get(record, selection.measureColumn()));
                BigDecimal parsed = parseDecimal(raw);
                if (parsed == null) {
                    if (raw != null) state.invalidNumberCount++;
                    return false;
                }
                state.rowsMatched++;
                state.numericCount++;
                updateMinMax(state, parsed);
                if (!isBlank(selection.dedupKeyColumn())) {
                    String dedupKey = clean(get(record, selection.dedupKeyColumn()));
                    if (dedupKey == null) {
                        state.missingKeyCount++;
                        return false;
                    }
                    BigDecimal existing = state.dedupValues.putIfAbsent(dedupKey, parsed);
                    if (existing != null && existing.compareTo(parsed) != 0) state.dedupConflictCount++;
                } else {
                    state.sum = state.sum.add(parsed);
                }
                return true;
            }
            default -> {
                state.rowsMatched++;
                return true;
            }
        }
    }

    private static BigDecimal computeStateValue(AggregationSelection selection, AggregateState state) {
        if (selection == null || state == null) return BigDecimal.ZERO;
        String mode = upper(selection.mode());
        return switch (mode) {
            case "ROW_COUNT" -> BigDecimal.valueOf(state.rowsMatched);
            case "DISTINCT_ENTRY_COUNT", "DISTINCT_DOCUMENT_COUNT", "DISTINCT_INVOICE_COUNT", "DISTINCT_PARTY_COUNT" -> BigDecimal.valueOf(state.distinctKeys.size());
            case "AVG_VALUE" -> state.numericCount <= 0
                ? BigDecimal.ZERO
                : state.sum.divide(BigDecimal.valueOf(Math.max(1, state.numericCount)), 6, RoundingMode.HALF_UP);
            case "SUM_AMOUNT" -> {
                if (state.dedupValues.isEmpty()) yield state.sum;
                BigDecimal total = BigDecimal.ZERO;
                for (BigDecimal value : state.dedupValues.values()) total = total.add(value == null ? BigDecimal.ZERO : value);
                yield total;
            }
            default -> state.sum;
        };
    }

    private static boolean modeNeedsValueColumn(UniversalViewRequest req) {
        String mode = normAggregationMode(req == null ? null : req.getAggregationMode());
        return mode == null || "SUM_AMOUNT".equals(mode) || "AVG_VALUE".equals(mode);
    }

    // package-private for tests
    UniversalChartDataDto previewBytes(byte[] bytes, UniversalViewRequest request) {
        return previewBytes(bytes, request, null);
    }

    // package-private for tests
    UniversalChartDataDto previewBytes(byte[] bytes, UniversalViewRequest request, UniversalSummaryDto summary) {
        UniversalViewRequest normalizedRequest = canonicalizeRequest(request, summary);
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Config vacío.");
        String type = normType(normalizedRequest.getType());
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
            SemanticContext semantic = semanticContext(summary);
            if ("TIME_SERIES".equals(type)) return buildTimeSeries(parser, headers, normalizedRequest, semantic);
            if ("CATEGORY_BAR".equals(type)) return buildCategoryBar(parser, headers, normalizedRequest, semantic);
            if ("KPI_CARDS".equals(type)) return buildKpiCards(parser, headers, normalizedRequest, semantic);
            if ("SCATTER".equals(type)) return buildScatter(parser, headers, normalizedRequest, semantic);
            if ("HEATMAP".equals(type)) return buildHeatmap(parser, headers, normalizedRequest, semantic);
            if ("PIVOT_MONTHLY".equals(type)) return buildPivotMonthly(parser, headers, normalizedRequest, semantic);
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

    private UniversalChartDataDto buildTimeSeries(CSVParser parser, List<String> headers, UniversalViewRequest req, SemanticContext semantic) {
        String dateCol = clean(req.getDateColumn());
        if (isBlank(dateCol)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecciona una columna fecha.");
        }
        requireMapped(parser, dateCol);
        AggregationSelection selection = resolveAggregationSelection("TIME_SERIES", req, semantic);
        if (!isBlank(selection.measureColumn())) requireMapped(parser, selection.measureColumn());
        if (!isBlank(selection.secondaryMeasureColumn())) requireMapped(parser, selection.secondaryMeasureColumn());
        if (!isBlank(selection.distinctKeyColumn())) requireMapped(parser, selection.distinctKeyColumn());
        if (!isBlank(selection.dedupKeyColumn())) requireMapped(parser, selection.dedupKeyColumn());

        List<UniversalFilter> filters = normalizeFilters(parser, req);
        Map<String, AggregateState> buckets = new LinkedHashMap<>();
        int rows = 0;
        int used = 0;
        int badDates = 0;
        List<String> badDateSamples = new ArrayList<>();
        for (CSVRecord record : parser) {
            rows++;
            if (rows > MAX_ROWS) break;
            if (!matchesFilters(record, filters)) continue;
            String dateRaw = clean(get(record, dateCol));
            if (dateRaw == null) continue;
            YearMonth ym = parseYearMonth(dateRaw);
            if (ym == null) {
                badDates++;
                if (badDateSamples.size() < 3) badDateSamples.add(dateRaw);
                continue;
            }

            AggregateState state = buckets.computeIfAbsent(ym.toString(), key -> new AggregateState());
            if (accumulateState(record, selection, state)) used++;
        }

        if (used == 0) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                explainNoRows("serie temporal", dateCol, selection.measureColumn(), badDates, badDateSamples, 0, List.of())
            );
        }

        List<String> labels = new ArrayList<>(buckets.keySet());
        labels.sort(String::compareTo);
        List<Number> data = new ArrayList<>(labels.size());
        int badNums = 0;
        int missingKeys = 0;
        int dedupConflicts = 0;
        for (String label : labels) {
            AggregateState state = buckets.get(label);
            badNums += state == null ? 0 : state.invalidNumberCount;
            missingKeys += state == null ? 0 : state.missingKeyCount;
            dedupConflicts += state == null ? 0 : state.dedupConflictCount;
            BigDecimal value = computeStateValue(selection, state == null ? new AggregateState() : state);
            data.add(value.setScale(2, RoundingMode.HALF_UP));
        }

        Map<String, Object> series = Map.of("name", selection.unitLabel(), "data", data);
        List<String> warnings = new ArrayList<>(selection.warnings() == null ? List.of() : selection.warnings());
        addWarnIf(warnings, rows > MAX_ROWS, "Dataset recortado: se analizaron " + rows + " filas (lÃ­mite " + MAX_ROWS + ").");
        addWarnIf(warnings, badDates > 0, "Fechas no parseables en '" + dateCol + "': " + badDates + sampleSuffix(badDateSamples) + ".");
        addWarnIf(warnings, badNums > 0, "NÃºmeros no parseables en '" + firstNonBlank(selection.measureColumn(), selection.secondaryMeasureColumn()) + "': " + badNums + ".");
        addWarnIf(warnings, missingKeys > 0, "Filas sin clave suficiente para " + selection.mode() + ": " + missingKeys + ".");
        addWarnIf(warnings, dedupConflicts > 0, "Hay " + dedupConflicts + " claves con importes distintos al deduplicar; se conserva la primera observaciÃ³n por clave.");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("aggregation", selection.legacyAggregation());
        meta.put("aggregationMode", selection.mode());
        meta.put("aggregationUnit", selection.unitLabel());
        meta.put("rowsUsed", used);
        meta.put("rowsScanned", rows);
        meta.put("truncated", rows > MAX_ROWS);
        meta.put("filters", filtersToLineage(filters));
        meta.put("dateColumn", dateCol);
        meta.put("valueColumn", selection.measureColumn());
        meta.put("distinctKeyColumn", selection.distinctKeyColumn());
        meta.put("dedupKeyColumn", selection.dedupKeyColumn());
        meta.put("badDateCount", badDates);
        meta.put("badNumberCount", badNums);
        meta.put("missingKeyCount", missingKeys);
        if (rows > 0) {
            meta.put("badDatePct", roundPct(badDates, rows));
            meta.put("badNumberPct", roundPct(badNums, rows));
        }
        if (!warnings.isEmpty()) meta.put("warnings", warnings);
        return new UniversalChartDataDto("TIME_SERIES", labels, List.of(series), meta);
    }

    private UniversalChartDataDto buildCategoryBar(CSVParser parser, List<String> headers, UniversalViewRequest req, SemanticContext semantic) {
        String catCol = clean(req.getCategoryColumn());
        if (isBlank(catCol)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecciona una columna categoría.");
        }
        requireMapped(parser, catCol);
        AggregationSelection selection = resolveAggregationSelection("CATEGORY_BAR", req, semantic);
        if (!isBlank(selection.measureColumn())) requireMapped(parser, selection.measureColumn());
        if (!isBlank(selection.secondaryMeasureColumn())) requireMapped(parser, selection.secondaryMeasureColumn());
        if (!isBlank(selection.distinctKeyColumn())) requireMapped(parser, selection.distinctKeyColumn());
        if (!isBlank(selection.dedupKeyColumn())) requireMapped(parser, selection.dedupKeyColumn());

        List<UniversalFilter> filters = normalizeFilters(parser, req);
        Map<String, AggregateState> buckets = new LinkedHashMap<>();
        int rows = 0;
        int used = 0;
        for (CSVRecord record : parser) {
            rows++;
            if (rows > MAX_ROWS) break;
            if (!matchesFilters(record, filters)) continue;
            String category = clean(get(record, catCol));
            if (category == null) continue;
            AggregateState state = buckets.computeIfAbsent(category, key -> new AggregateState());
            if (accumulateState(record, selection, state)) used++;
        }

        if (used == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No hay filas válidas para graficar (revisa categoría/unidad).");
        }

        List<Map.Entry<String, AggregateState>> ordered = new ArrayList<>(buckets.entrySet());
        ordered.sort((a, b) -> computeStateValue(selection, b.getValue()).compareTo(computeStateValue(selection, a.getValue())));
        if (ordered.size() > 12) ordered = ordered.subList(0, 12);

        List<String> labels = ordered.stream().map(Map.Entry::getKey).toList();
        List<Number> data = new ArrayList<>(labels.size());
        int badNums = 0;
        int missingKeys = 0;
        int dedupConflicts = 0;
        for (Map.Entry<String, AggregateState> entry : ordered) {
            AggregateState state = entry.getValue();
            badNums += state == null ? 0 : state.invalidNumberCount;
            missingKeys += state == null ? 0 : state.missingKeyCount;
            dedupConflicts += state == null ? 0 : state.dedupConflictCount;
            data.add(computeStateValue(selection, state).setScale(2, RoundingMode.HALF_UP));
        }

        Map<String, Object> series = Map.of("name", selection.unitLabel(), "data", data);
        List<String> warnings = new ArrayList<>(selection.warnings() == null ? List.of() : selection.warnings());
        addWarnIf(warnings, rows > MAX_ROWS, "Dataset recortado: se analizaron " + rows + " filas (lÃ­mite " + MAX_ROWS + ").");
        addWarnIf(warnings, badNums > 0, "NÃºmeros no parseables en '" + firstNonBlank(selection.measureColumn(), selection.secondaryMeasureColumn()) + "': " + badNums + ".");
        addWarnIf(warnings, missingKeys > 0, "Filas sin clave suficiente para " + selection.mode() + ": " + missingKeys + ".");
        addWarnIf(warnings, dedupConflicts > 0, "Hay " + dedupConflicts + " claves con importes distintos al deduplicar; se conserva la primera observaciÃ³n por clave.");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("aggregation", selection.legacyAggregation());
        meta.put("aggregationMode", selection.mode());
        meta.put("aggregationUnit", selection.unitLabel());
        meta.put("rowsUsed", used);
        meta.put("rowsScanned", rows);
        meta.put("truncated", rows > MAX_ROWS);
        meta.put("filters", filtersToLineage(filters));
        meta.put("categoryColumn", catCol);
        meta.put("valueColumn", selection.measureColumn());
        meta.put("distinctKeyColumn", selection.distinctKeyColumn());
        meta.put("dedupKeyColumn", selection.dedupKeyColumn());
        meta.put("badNumberCount", badNums);
        meta.put("missingKeyCount", missingKeys);
        if (rows > 0) meta.put("badNumberPct", roundPct(badNums, rows));
        if (!warnings.isEmpty()) meta.put("warnings", warnings);
        return new UniversalChartDataDto("CATEGORY_BAR", labels, List.of(series), meta);
    }

    private UniversalChartDataDto buildKpiCards(CSVParser parser, List<String> headers, UniversalViewRequest req, SemanticContext semantic) {
        AggregationSelection selection = resolveAggregationSelection("KPI_CARDS", req, semantic);
        if (!isBlank(selection.measureColumn())) requireMapped(parser, selection.measureColumn());
        if (!isBlank(selection.secondaryMeasureColumn())) requireMapped(parser, selection.secondaryMeasureColumn());
        if (!isBlank(selection.distinctKeyColumn())) requireMapped(parser, selection.distinctKeyColumn());
        if (!isBlank(selection.dedupKeyColumn())) requireMapped(parser, selection.dedupKeyColumn());

        List<UniversalFilter> filters = normalizeFilters(parser, req);
        AggregateState total = new AggregateState();
        int rows = 0;
        int used = 0;
        for (CSVRecord record : parser) {
            rows++;
            if (rows > MAX_ROWS) break;
            if (!matchesFilters(record, filters)) continue;
            if (accumulateState(record, selection, total)) used++;
        }

        if (used == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No hay filas válidas para calcular KPIs (revisa filtros o unidad).");
        }

        BigDecimal primary = computeStateValue(selection, total).setScale(2, RoundingMode.HALF_UP);
        List<String> labels = new ArrayList<>();
        List<Number> data = new ArrayList<>();
        labels.add(selection.unitLabel());
        data.add(primary);
        labels.add("filas_origen");
        data.add(used);
        if (("SUM_AMOUNT".equals(selection.mode()) || "AVG_VALUE".equals(selection.mode())) && total.numericCount > 0) {
            labels.add("media_fila");
            data.add(total.sum.divide(BigDecimal.valueOf(Math.max(1, total.numericCount)), 6, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP));
        }
        if (total.min != null) {
            labels.add("min");
            data.add(total.min.setScale(2, RoundingMode.HALF_UP));
        }
        if (total.max != null) {
            labels.add("max");
            data.add(total.max.setScale(2, RoundingMode.HALF_UP));
        }

        List<String> warnings = new ArrayList<>(selection.warnings() == null ? List.of() : selection.warnings());
        addWarnIf(warnings, rows > MAX_ROWS, "Dataset recortado: se analizaron " + rows + " filas (lÃ­mite " + MAX_ROWS + ").");
        addWarnIf(warnings, total.invalidNumberCount > 0, "NÃºmeros no parseables en '" + firstNonBlank(selection.measureColumn(), selection.secondaryMeasureColumn()) + "': " + total.invalidNumberCount + ".");
        addWarnIf(warnings, total.missingKeyCount > 0, "Filas sin clave suficiente para " + selection.mode() + ": " + total.missingKeyCount + ".");
        addWarnIf(warnings, total.dedupConflictCount > 0, "Hay " + total.dedupConflictCount + " claves con importes distintos al deduplicar; se conserva la primera observaciÃ³n por clave.");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("aggregation", selection.legacyAggregation());
        meta.put("aggregationMode", selection.mode());
        meta.put("aggregationUnit", selection.unitLabel());
        meta.put("rowsUsed", used);
        meta.put("rowsScanned", rows);
        meta.put("truncated", rows > MAX_ROWS);
        meta.put("filters", filtersToLineage(filters));
        meta.put("valueColumn", selection.measureColumn());
        meta.put("distinctKeyColumn", selection.distinctKeyColumn());
        meta.put("dedupKeyColumn", selection.dedupKeyColumn());
        meta.put("badNumberCount", total.invalidNumberCount);
        meta.put("missingKeyCount", total.missingKeyCount);
        if (rows > 0) meta.put("badNumberPct", roundPct(total.invalidNumberCount, rows));
        if (!warnings.isEmpty()) meta.put("warnings", warnings);
        return new UniversalChartDataDto("KPI_CARDS", labels, List.of(Map.of("name", selection.unitLabel(), "data", data)), meta);
    }

    private UniversalChartDataDto buildScatter(CSVParser parser, List<String> headers, UniversalViewRequest req, SemanticContext semantic) {
        UniversalChartDataDto base = buildScatter(parser, headers, req);
        Map<String, Object> meta = base.meta() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(base.meta());
        AggregationSelection selection = resolveAggregationSelection("SCATTER", req, semantic);
        meta.put("aggregation", selection.legacyAggregation());
        meta.put("aggregationMode", selection.mode());
        meta.put("aggregationUnit", selection.unitLabel());
        List<String> warnings = new ArrayList<>();
        Object existingWarnings = meta.get("warnings");
        if (existingWarnings instanceof List<?> list) {
            for (Object warning : list) warnings.add(String.valueOf(warning));
        }
        warnings.addAll(selection.warnings() == null ? List.of() : selection.warnings());
        if (!warnings.isEmpty()) meta.put("warnings", warnings);
        return new UniversalChartDataDto(base.type(), base.labels(), base.series(), meta);
    }

    private UniversalChartDataDto buildHeatmap(CSVParser parser, List<String> headers, UniversalViewRequest req, SemanticContext semantic) {
        String xCol = clean(req.getXColumn());
        String yCol = clean(req.getYColumn());
        if (isBlank(xCol) || isBlank(yCol)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecciona columnas X e Y.");
        }
        requireMapped(parser, xCol);
        requireMapped(parser, yCol);
        AggregationSelection selection = resolveAggregationSelection("HEATMAP", req, semantic);
        if (!isBlank(selection.measureColumn())) requireMapped(parser, selection.measureColumn());
        if (!isBlank(selection.secondaryMeasureColumn())) requireMapped(parser, selection.secondaryMeasureColumn());
        if (!isBlank(selection.distinctKeyColumn())) requireMapped(parser, selection.distinctKeyColumn());
        if (!isBlank(selection.dedupKeyColumn())) requireMapped(parser, selection.dedupKeyColumn());

        List<UniversalFilter> filters = normalizeFilters(parser, req);
        Map<String, AggregateState> xTotals = new HashMap<>();
        Map<String, AggregateState> yTotals = new HashMap<>();
        Map<String, Map<String, AggregateState>> matrix = new HashMap<>();

        int rows = 0;
        int used = 0;
        for (CSVRecord record : parser) {
            rows++;
            if (rows > MAX_ROWS) break;
            if (!matchesFilters(record, filters)) continue;
            String x = clean(get(record, xCol));
            String y = clean(get(record, yCol));
            if (x == null || y == null) continue;

            AggregateState cell = matrix.computeIfAbsent(y, key -> new HashMap<>()).computeIfAbsent(x, key -> new AggregateState());
            AggregateState xState = xTotals.computeIfAbsent(x, key -> new AggregateState());
            AggregateState yState = yTotals.computeIfAbsent(y, key -> new AggregateState());
            boolean accepted = accumulateState(record, selection, cell);
            if (accepted) {
                accumulateState(record, selection, xState);
                accumulateState(record, selection, yState);
                used++;
            }
        }

        if (used == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No hay filas válidas para heatmap (revisa ejes, filtros o unidad).");
        }

        Map<String, BigDecimal> xValues = new HashMap<>();
        Map<String, BigDecimal> yValues = new HashMap<>();
        int badNums = 0;
        int missingKeys = 0;
        int dedupConflicts = 0;
        for (Map.Entry<String, AggregateState> entry : xTotals.entrySet()) {
            xValues.put(entry.getKey(), computeStateValue(selection, entry.getValue()));
            badNums += entry.getValue().invalidNumberCount;
            missingKeys += entry.getValue().missingKeyCount;
            dedupConflicts += entry.getValue().dedupConflictCount;
        }
        for (Map.Entry<String, AggregateState> entry : yTotals.entrySet()) {
            yValues.put(entry.getKey(), computeStateValue(selection, entry.getValue()));
        }

        List<String> xLabels = topKeysByValue(xValues, HEATMAP_MAX_X);
        List<String> yLabels = topKeysByValue(yValues, HEATMAP_MAX_Y);
        Map<String, Integer> xIndex = new HashMap<>();
        Map<String, Integer> yIndex = new HashMap<>();
        for (int i = 0; i < xLabels.size(); i++) xIndex.put(xLabels.get(i), i);
        for (int i = 0; i < yLabels.size(); i++) yIndex.put(yLabels.get(i), i);

        List<List<Number>> points = new ArrayList<>();
        for (String y : yLabels) {
            Map<String, AggregateState> row = matrix.getOrDefault(y, Map.of());
            for (String x : xLabels) {
                AggregateState state = row.get(x);
                if (state == null) continue;
                BigDecimal value = computeStateValue(selection, state).setScale(2, RoundingMode.HALF_UP);
                points.add(List.of(xIndex.get(x), yIndex.get(y), value));
            }
        }

        List<String> warnings = new ArrayList<>(selection.warnings() == null ? List.of() : selection.warnings());
        addWarnIf(warnings, rows > MAX_ROWS, "Dataset recortado: se analizaron " + rows + " filas (lÃ­mite " + MAX_ROWS + ").");
        addWarnIf(warnings, badNums > 0, "NÃºmeros no parseables en '" + firstNonBlank(selection.measureColumn(), selection.secondaryMeasureColumn()) + "': " + badNums + ".");
        addWarnIf(warnings, missingKeys > 0, "Filas sin clave suficiente para " + selection.mode() + ": " + missingKeys + ".");
        addWarnIf(warnings, dedupConflicts > 0, "Hay " + dedupConflicts + " claves con importes distintos al deduplicar; se conserva la primera observaciÃ³n por clave.");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("aggregation", selection.legacyAggregation());
        meta.put("aggregationMode", selection.mode());
        meta.put("aggregationUnit", selection.unitLabel());
        meta.put("rowsUsed", used);
        meta.put("rowsScanned", rows);
        meta.put("truncated", rows > MAX_ROWS);
        meta.put("filters", filtersToLineage(filters));
        meta.put("xColumn", xCol);
        meta.put("yColumn", yCol);
        meta.put("valueColumn", selection.measureColumn());
        meta.put("distinctKeyColumn", selection.distinctKeyColumn());
        meta.put("dedupKeyColumn", selection.dedupKeyColumn());
        meta.put("yLabels", yLabels);
        meta.put("badNumberCount", badNums);
        meta.put("missingKeyCount", missingKeys);
        if (rows > 0) meta.put("badNumberPct", roundPct(badNums, rows));
        if (!warnings.isEmpty()) meta.put("warnings", warnings);
        return new UniversalChartDataDto("HEATMAP", xLabels, List.of(Map.of("name", selection.unitLabel(), "data", points)), meta);
    }

    private UniversalChartDataDto buildPivotMonthly(CSVParser parser, List<String> headers, UniversalViewRequest req, SemanticContext semantic) {
        String dateCol = clean(req.getDateColumn());
        String catCol = clean(req.getCategoryColumn());
        if (isBlank(dateCol) || isBlank(catCol)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecciona columna fecha y categoría.");
        }
        requireMapped(parser, dateCol);
        requireMapped(parser, catCol);
        AggregationSelection selection = resolveAggregationSelection("PIVOT_MONTHLY", req, semantic);
        if (!isBlank(selection.measureColumn())) requireMapped(parser, selection.measureColumn());
        if (!isBlank(selection.secondaryMeasureColumn())) requireMapped(parser, selection.secondaryMeasureColumn());
        if (!isBlank(selection.distinctKeyColumn())) requireMapped(parser, selection.distinctKeyColumn());
        if (!isBlank(selection.dedupKeyColumn())) requireMapped(parser, selection.dedupKeyColumn());

        List<UniversalFilter> filters = normalizeFilters(parser, req);
        int topN = clamp(req.getTopN(), 1, 30, DEFAULT_TOP_N);
        Map<YearMonth, Map<String, AggregateState>> matrix = new HashMap<>();
        Map<String, AggregateState> categoryTotals = new HashMap<>();

        int rows = 0;
        int used = 0;
        int badDates = 0;
        List<String> badDateSamples = new ArrayList<>();
        for (CSVRecord record : parser) {
            rows++;
            if (rows > MAX_ROWS) break;
            if (!matchesFilters(record, filters)) continue;

            String dateRaw = clean(get(record, dateCol));
            YearMonth ym = parseYearMonth(dateRaw);
            if (ym == null) {
                badDates++;
                if (badDateSamples.size() < 3 && dateRaw != null) badDateSamples.add(dateRaw);
                continue;
            }

            String category = clean(get(record, catCol));
            if (category == null) continue;
            AggregateState cell = matrix.computeIfAbsent(ym, key -> new HashMap<>()).computeIfAbsent(category, key -> new AggregateState());
            AggregateState total = categoryTotals.computeIfAbsent(category, key -> new AggregateState());
            if (accumulateState(record, selection, cell)) {
                accumulateState(record, selection, total);
                used++;
            }
        }

        if (used == 0) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                explainNoRows("pivote", dateCol, selection.measureColumn(), badDates, badDateSamples, 0, List.of())
            );
        }

        List<String> months = matrix.keySet().stream().filter(Objects::nonNull).map(YearMonth::toString).sorted().toList();
        Map<String, BigDecimal> categoryValues = new HashMap<>();
        int badNums = 0;
        int missingKeys = 0;
        int dedupConflicts = 0;
        for (Map.Entry<String, AggregateState> entry : categoryTotals.entrySet()) {
            categoryValues.put(entry.getKey(), computeStateValue(selection, entry.getValue()));
            badNums += entry.getValue().invalidNumberCount;
            missingKeys += entry.getValue().missingKeyCount;
            dedupConflicts += entry.getValue().dedupConflictCount;
        }
        List<String> categories = topKeysByValue(categoryValues, topN);

        List<Map<String, Object>> series = new ArrayList<>();
        for (String category : categories) {
            List<Number> data = new ArrayList<>(months.size());
            for (String month : months) {
                AggregateState state = matrix.getOrDefault(YearMonth.parse(month), Map.of()).get(category);
                BigDecimal value = computeStateValue(selection, state == null ? new AggregateState() : state);
                data.add(value.setScale(2, RoundingMode.HALF_UP));
            }
            series.add(Map.of("name", category, "data", data));
        }

        List<String> warnings = new ArrayList<>(selection.warnings() == null ? List.of() : selection.warnings());
        addWarnIf(warnings, rows > MAX_ROWS, "Dataset recortado: se analizaron " + rows + " filas (lÃ­mite " + MAX_ROWS + ").");
        addWarnIf(warnings, badDates > 0, "Fechas no parseables en '" + dateCol + "': " + badDates + sampleSuffix(badDateSamples) + ".");
        addWarnIf(warnings, badNums > 0, "NÃºmeros no parseables en '" + firstNonBlank(selection.measureColumn(), selection.secondaryMeasureColumn()) + "': " + badNums + ".");
        addWarnIf(warnings, missingKeys > 0, "Filas sin clave suficiente para " + selection.mode() + ": " + missingKeys + ".");
        addWarnIf(warnings, dedupConflicts > 0, "Hay " + dedupConflicts + " claves con importes distintos al deduplicar; se conserva la primera observaciÃ³n por clave.");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("aggregation", selection.legacyAggregation());
        meta.put("aggregationMode", selection.mode());
        meta.put("aggregationUnit", selection.unitLabel());
        meta.put("rowsUsed", used);
        meta.put("rowsScanned", rows);
        meta.put("truncated", rows > MAX_ROWS);
        meta.put("filters", filtersToLineage(filters));
        meta.put("dateColumn", dateCol);
        meta.put("categoryColumn", catCol);
        meta.put("valueColumn", selection.measureColumn());
        meta.put("distinctKeyColumn", selection.distinctKeyColumn());
        meta.put("dedupKeyColumn", selection.dedupKeyColumn());
        meta.put("topN", topN);
        meta.put("badDateCount", badDates);
        meta.put("badNumberCount", badNums);
        meta.put("missingKeyCount", missingKeys);
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
        String aggregationMode = normAggregationMode(req.getAggregationMode());

        if (dateCol != null) out.put("dateColumn", dateCol);
        if (valueCol != null) out.put("valueColumn", valueCol);
        if (categoryCol != null) out.put("categoryColumn", categoryCol);
        if (xCol != null) out.put("xColumn", xCol);
        if (yCol != null) out.put("yColumn", yCol);
        if (aggregation != null) out.put("aggregation", aggregation);
        if (aggregationMode != null) out.put("aggregationMode", aggregationMode);

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




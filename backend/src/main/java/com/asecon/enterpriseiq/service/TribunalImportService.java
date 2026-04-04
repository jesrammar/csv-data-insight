package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.TribunalActivityPointDto;
import com.asecon.enterpriseiq.dto.TribunalGestorDto;
import com.asecon.enterpriseiq.dto.TribunalImportDto;
import com.asecon.enterpriseiq.dto.TribunalKpiDto;
import com.asecon.enterpriseiq.dto.TribunalRiskDto;
import com.asecon.enterpriseiq.dto.TribunalSummaryDto;
import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.TribunalActivity;
import com.asecon.enterpriseiq.model.TribunalClient;
import com.asecon.enterpriseiq.model.TribunalImport;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.asecon.enterpriseiq.repo.TribunalActivityRepository;
import com.asecon.enterpriseiq.repo.TribunalClientRepository;
import com.asecon.enterpriseiq.repo.TribunalImportRepository;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TribunalImportService {
    private static final int[] ACTIVITY_YEARS = {2015, 2016, 2017, 2018, 2019, 2020, 2021, 2022, 2023, 2024};

    private final CompanyRepository companyRepository;
    private final TribunalImportRepository importRepository;
    private final TribunalClientRepository clientRepository;
    private final TribunalActivityRepository activityRepository;

    public TribunalImportService(CompanyRepository companyRepository,
                                 TribunalImportRepository importRepository,
                                 TribunalClientRepository clientRepository,
                                 TribunalActivityRepository activityRepository) {
        this.companyRepository = companyRepository;
        this.importRepository = importRepository;
        this.clientRepository = clientRepository;
        this.activityRepository = activityRepository;
    }

    @Transactional(rollbackFor = Exception.class)
    public TribunalImportDto importCsv(Long companyId, MultipartFile file) throws IOException {
        Company company = companyRepository.findById(companyId).orElseThrow();

        int warnings = 0;
        int errors = 0;
        int rowCount = 0;
        List<TribunalClient> clients = new ArrayList<>();
        List<String> rowErrors = new ArrayList<>();

        byte[] bytes = file.getBytes();
        Charset charset = detectCharset(bytes);
        String firstLine = readFirstLine(bytes, charset);
        if (firstLine == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV vacio");
        }
        firstLine = stripBom(firstLine);
        char delimiter = detectDelimiter(firstLine);

        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(new ByteArrayInputStream(bytes), charset))) {
            CSVParser parser;
            try {
                parser = CSVFormat.DEFAULT.builder()
                    .setDelimiter(delimiter)
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setAllowMissingColumnNames(true)
                    .setIgnoreEmptyLines(true)
                    .build()
                    .parse(reader);
            } catch (Exception ex) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "CSV malformado o no compatible. Re-exporta como CSV UTF-8 (una sola tabla con cabeceras en la primera fila).",
                    ex
                );
            }

            Map<String, String> headerIndex = normalizeHeaders(parser.getHeaderMap().keySet());
            List<String> missing = requiredMissing(headerIndex);
            if (!missing.isEmpty()) {
                String headersSeen = parser.getHeaderMap().keySet().stream()
                    .filter(Objects::nonNull)
                    .limit(30)
                    .collect(Collectors.joining(", "));
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "CSV sin columnas requeridas: " + String.join(", ", missing)
                        + (headersSeen.isBlank() ? "" : ". Cabeceras detectadas: " + headersSeen)
                );
            }

            for (CSVRecord record : parser) {
                rowCount++;

                TribunalClient client = new TribunalClient();
                client.setCompany(company);
                client.setRowId(parseInteger(safeGet(record, headerIndex, "")));
                client.setTipoCliente(safeGet(record, headerIndex, "tipocliente"));
                client.setCliente(safeGetAny(record, headerIndex, "clientes2026", "cliente", "clientes"));
                client.setCif(safeGet(record, headerIndex, "cif"));
                client.setAdministrador(safeGet(record, headerIndex, "administrador"));
                client.setDniNie(safeGet(record, headerIndex, "dninie"));
                String minutasRaw = safeGet(record, headerIndex, "minutas");
                BigDecimal minutas = parseDecimal(minutasRaw);
                if (minutasRaw != null && minutas == null) {
                    warnings++;
                    rowWarnings(rowErrors, rowCount, "minutas");
                }
                client.setMinutas(minutas);

                String fAltaRaw = safeGet(record, headerIndex, "falta");
                String fBajaRaw = safeGet(record, headerIndex, "fbaja");
                LocalDate fAlta = parseMonthYear(fAltaRaw);
                LocalDate fBaja = parseMonthYear(fBajaRaw);
                if (fAltaRaw != null && fAlta == null) warnings++;
                if (fBajaRaw != null && fBaja == null) warnings++;
                client.setFAlta(fAlta);
                client.setFBaja(fBaja);

                client.setFPago(safeGet(record, headerIndex, "fpago"));
                client.setGestor(safeGet(record, headerIndex, "gestor"));

                String contModelosRaw = safeGet(record, headerIndex, "contmodelos");
                client.setContModelos(parseYesNo(contModelosRaw));

                String isIrpfRaw = safeGet(record, headerIndex, "isirpf");
                client.setIsIrpfOk(parseYesNo(isIrpfRaw));
                client.setIsIrpfStatus(isIrpfRaw);

                String ddccRaw = safeGet(record, headerIndex, "ddcc");
                client.setDdccOk(parseYesNo(ddccRaw));
                client.setDdccStatus(ddccRaw);

                String librosRaw = safeGet(record, headerIndex, "libros");
                client.setLibrosOk(parseYesNo(librosRaw));
                client.setLibrosStatus(librosRaw);

                String cargaRaw = safeGet(record, headerIndex, "cargadetrabajo");
                BigDecimal carga = parseDecimal(cargaRaw);
                if (cargaRaw != null && carga == null) {
                    warnings++;
                    rowWarnings(rowErrors, rowCount, "cargadetrabajo");
                }
                client.setCargaDeTrabajo(carga);

                String pctRaw = safeGet(record, headerIndex, "pctcontabilidad");
                BigDecimal pct = parseDecimal(pctRaw);
                if (pctRaw != null && pct == null) {
                    warnings++;
                    rowWarnings(rowErrors, rowCount, "pctcontabilidad");
                }
                client.setPctContabilidad(pct);

                String promedioRaw = safeGet(record, headerIndex, "promedio");
                BigDecimal promedio = parseDecimal(promedioRaw);
                if (promedioRaw != null && promedio == null) {
                    warnings++;
                    rowWarnings(rowErrors, rowCount, "promedio");
                }
                client.setPromedio(promedio);

                for (int year : ACTIVITY_YEARS) {
                    String key = "nas" + year;
                    String raw = safeGet(record, headerIndex, key);
                    Integer nAs = parseInteger(raw);
                    if (raw != null && nAs == null) {
                        warnings++;
                        continue;
                    }
                    if (nAs != null) {
                        TribunalActivity activity = new TribunalActivity();
                        activity.setClient(client);
                        activity.setCompany(company);
                        activity.setYear(year);
                        activity.setNAs(nAs);
                        client.getActivities().add(activity);
                    }
                }

                if (isBlank(client.getCliente()) || isBlank(client.getCif())) {
                    errors++;
                    rowErrors.add("Fila " + rowCount + ": faltan cliente o CIF");
                    continue;
                }

                clients.add(client);
            }
        }

        if (clients.isEmpty()) {
            String msg = rowErrors.isEmpty() ? "CSV sin filas validas" : "CSV sin filas validas. " + String.join(" | ", rowErrors.stream().limit(5).toList());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
        }

        activityRepository.deleteByCompanyId(companyId);
        clientRepository.deleteByCompanyId(companyId);
        importRepository.deleteByCompanyId(companyId);

        TribunalImport imp = new TribunalImport();
        imp.setCompany(company);
        imp.setFilename(file.getOriginalFilename() == null ? "tribunal.csv" : file.getOriginalFilename());
        imp.setCreatedAt(Instant.now());
        imp.setRowCount(rowCount);
        imp.setWarningCount(warnings);
        imp.setErrorCount(errors);
        if (!rowErrors.isEmpty()) {
            imp.setErrorSummary(String.join(" | ", rowErrors));
        }
        imp = importRepository.save(imp);

        for (TribunalClient client : clients) {
            client.setTribunalImport(imp);
        }
        clientRepository.saveAll(clients);
        return toDto(imp);
    }

    @Transactional(readOnly = true)
    public TribunalImportDto getLatestImport(Long companyId) {
        return importRepository.findFirstByCompanyIdOrderByCreatedAtDesc(companyId)
            .map(this::toDto)
            .orElse(null);
    }

    @Transactional(readOnly = true)
    public TribunalSummaryDto getSummary(Long companyId) {
        List<TribunalClient> clients = clientRepository.findByCompanyId(companyId);
        List<TribunalActivity> activities = activityRepository.findByCompanyId(companyId);

        long total = clients.size();
        long active = clients.stream().filter(c -> c.getFBaja() == null).count();
        long contOk = clients.stream().filter(c -> Boolean.TRUE.equals(c.getContModelos())).count();
        long fiscalOk = clients.stream().filter(c -> Boolean.TRUE.equals(c.getIsIrpfOk())
            && Boolean.TRUE.equals(c.getDdccOk())
            && Boolean.TRUE.equals(c.getLibrosOk())).count();

        double minutasAvg = avg(clients.stream()
            .filter(c -> c.getMinutas() != null)
            .map(TribunalClient::getMinutas)
            .collect(Collectors.toList()));

        double cargaAvg = avg(clients.stream()
            .filter(c -> c.getCargaDeTrabajo() != null)
            .map(TribunalClient::getCargaDeTrabajo)
            .collect(Collectors.toList()));

        double bajaPct = total > 0 ? ((double) (total - active) / total) * 100.0 : 0.0;
        double contPct = total > 0 ? ((double) contOk / total) * 100.0 : 0.0;
        double fiscalPct = total > 0 ? ((double) fiscalOk / total) * 100.0 : 0.0;

        TribunalKpiDto kpis = new TribunalKpiDto(
            total,
            active,
            round(bajaPct),
            round(minutasAvg),
            round(cargaAvg),
            round(contPct),
            round(fiscalPct)
        );

        List<TribunalGestorDto> gestores = clients.stream()
            .collect(Collectors.groupingBy(c -> c.getGestor() == null ? "SIN GESTOR" : c.getGestor()))
            .entrySet()
            .stream()
            .map(entry -> {
                List<TribunalClient> group = entry.getValue();
                long totalG = group.size();
                long activeG = group.stream().filter(c -> c.getFBaja() == null).count();
                double minAvg = avg(group.stream()
                    .filter(c -> c.getMinutas() != null)
                    .map(TribunalClient::getMinutas)
                    .collect(Collectors.toList()));
                double carga = avg(group.stream()
                    .filter(c -> c.getCargaDeTrabajo() != null)
                    .map(TribunalClient::getCargaDeTrabajo)
                    .collect(Collectors.toList()));
                return new TribunalGestorDto(entry.getKey(), totalG, activeG, round(minAvg), round(carga));
            })
            .sorted(Comparator.comparingLong(TribunalGestorDto::totalClients).reversed())
            .collect(Collectors.toList());

        Map<Integer, Long> activityTotals = new HashMap<>();
        for (TribunalActivity activity : activities) {
            activityTotals.merge(activity.getYear(), (long) activity.getNAs(), Long::sum);
        }
        List<TribunalActivityPointDto> activityPoints = new ArrayList<>();
        for (int year : ACTIVITY_YEARS) {
            activityPoints.add(new TribunalActivityPointDto(year, activityTotals.getOrDefault(year, 0L)));
        }

        List<TribunalRiskDto> risk = clients.stream()
            .map(this::toRisk)
            .filter(Objects::nonNull)
            .limit(50)
            .collect(Collectors.toList());

        return new TribunalSummaryDto(kpis, gestores, activityPoints, risk);
    }

    @Transactional(readOnly = true)
    public String exportCsv(Long companyId) throws IOException {
        List<TribunalClient> clients = clientRepository.findByCompanyId(companyId);
        Map<Long, Map<Integer, Integer>> activityMap = activityRepository.findByCompanyId(companyId).stream()
            .collect(Collectors.groupingBy(
                a -> a.getClient().getId(),
                Collectors.toMap(TribunalActivity::getYear, TribunalActivity::getNAs, (a, b) -> a)
            ));

        StringWriter writer = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.withDelimiter(';'))) {
            List<String> headers = new ArrayList<>();
            headers.add("row_id");
            headers.add("tipo_cliente");
            headers.add("cliente");
            headers.add("cif");
            headers.add("administrador");
            headers.add("dni_nie");
            headers.add("minutas");
            headers.add("f_alta");
            headers.add("f_baja");
            headers.add("f_pago");
            headers.add("gestor");
            headers.add("cont_modelos");
            headers.add("is_irpf");
            headers.add("ddcc");
            headers.add("libros");
            headers.add("carga_de_trabajo");
            headers.add("pct_contabilidad");
            for (int year : ACTIVITY_YEARS) {
                headers.add("n_as_" + year);
            }
            headers.add("promedio");
            printer.printRecord(headers);

            for (TribunalClient client : clients) {
                List<String> row = new ArrayList<>();
                row.add(value(client.getRowId()));
                row.add(value(client.getTipoCliente()));
                row.add(value(client.getCliente()));
                row.add(value(client.getCif()));
                row.add(value(client.getAdministrador()));
                row.add(value(client.getDniNie()));
                row.add(value(client.getMinutas()));
                row.add(value(client.getFAlta()));
                row.add(value(client.getFBaja()));
                row.add(value(client.getFPago()));
                row.add(value(client.getGestor()));
                row.add(value(client.getContModelos()));
                row.add(value(client.getIsIrpfStatus()));
                row.add(value(client.getDdccStatus()));
                row.add(value(client.getLibrosStatus()));
                row.add(value(client.getCargaDeTrabajo()));
                row.add(value(client.getPctContabilidad()));
                Map<Integer, Integer> byYear = activityMap.getOrDefault(client.getId(), Map.of());
                for (int year : ACTIVITY_YEARS) {
                    row.add(value(byYear.get(year)));
                }
                row.add(value(client.getPromedio()));
                printer.printRecord(row);
            }
        }
        return writer.toString();
    }

    private TribunalImportDto toDto(TribunalImport imp) {
        return new TribunalImportDto(
            imp.getId(),
            imp.getCompany().getId(),
            imp.getFilename(),
            imp.getCreatedAt(),
            imp.getRowCount(),
            imp.getWarningCount(),
            imp.getErrorCount(),
            imp.getErrorSummary()
        );
    }

    private static Map<String, String> normalizeHeaders(Iterable<String> headers) {
        Map<String, String> map = new HashMap<>();
        for (String header : headers) {
            map.put(normalize(header), header);
        }
        return map;
    }

    private static List<String> requiredMissing(Map<String, String> headers) {
        List<String> missing = new ArrayList<>();
        if (!hasAny(headers, "clientes2026", "cliente", "clientes")) missing.add("cliente");
        if (!hasAny(headers, "cif")) missing.add("cif");
        return missing;
    }

    private static boolean hasAny(Map<String, String> headers, String... keys) {
        for (String key : keys) {
            if (headers.containsKey(normalize(key))) return true;
        }
        return false;
    }

    private static String normalize(String header) {
        if (header == null) return "";
        String h = header.trim().toLowerCase(Locale.ROOT);
        h = h.replace(" ", "");
        h = h.replace("/", "");
        h = h.replace("%", "pct");
        h = h.replace(".", "");
        return h;
    }

    private static String safeGet(CSVRecord record, Map<String, String> headers, String key) {
        String normalized = normalize(key);
        String header = headers.get(normalized);
        if (header == null) {
            if (normalized.isEmpty() && record.size() > 0) {
                return clean(record.get(0));
            }
            return null;
        }
        return clean(record.get(header));
    }

    private static String safeGetAny(CSVRecord record, Map<String, String> headers, String... keys) {
        for (String key : keys) {
            String value = safeGet(record, headers, key);
            if (value != null) return value;
        }
        return null;
    }

    private static String clean(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() || "-".equals(trimmed) ? null : trimmed;
    }

    private static BigDecimal parseDecimal(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replace(".", "").replace(",", ".");
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer parseInteger(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replace(".", "").replace(",", ".").trim();
        if (cleaned.isEmpty()) return null;
        try {
            if (cleaned.contains(".")) {
                return new BigDecimal(cleaned).setScale(0, RoundingMode.HALF_UP).intValue();
            }
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void rowWarnings(List<String> rowErrors, int row, String field) {
        if (rowErrors.size() >= 5) return;
        rowErrors.add("Fila " + row + ": valor invalido en " + field);
    }

    private static String readFirstLine(byte[] bytes, Charset charset) throws IOException {
        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(new ByteArrayInputStream(bytes), charset))) {
            return reader.readLine();
        }
    }

    private static char detectDelimiter(String line) {
        int semi = count(line, ';');
        int comma = count(line, ',');
        int tab = count(line, '\t');
        int pipe = count(line, '|');
        if (tab >= semi && tab >= comma && tab >= pipe) return '\t';
        if (semi >= comma && semi >= pipe) return ';';
        if (pipe >= comma) return '|';
        return ',';
    }

    private static int count(String line, char ch) {
        int c = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == ch) c++;
        }
        return c;
    }

    private static Charset detectCharset(byte[] bytes) {
        if (bytes.length >= 3
            && (bytes[0] & 0xFF) == 0xEF
            && (bytes[1] & 0xFF) == 0xBB
            && (bytes[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (bytes.length >= 2) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xFE) return StandardCharsets.UTF_16LE;
            if (b0 == 0xFE && b1 == 0xFF) return StandardCharsets.UTF_16BE;
        }
        return StandardCharsets.UTF_8;
    }

    private static String stripBom(String value) {
        if (value == null) return null;
        if (!value.isEmpty() && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }

    private static LocalDate parseMonthYear(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return null;
        String[] parts = value.split("-");
        if (parts.length < 2) return null;
        Integer month = parseSpanishMonth(parts[0]);
        String yy = parts[1];
        if (month == null) return null;
        try {
            int year = yy.length() == 2 ? Integer.parseInt("20" + yy) : Integer.parseInt(yy);
            return LocalDate.of(year, month, 1);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer parseSpanishMonth(String raw) {
        return switch (raw) {
            case "ene", "enero" -> 1;
            case "feb", "febrero" -> 2;
            case "mar", "marzo" -> 3;
            case "abr", "abril" -> 4;
            case "may", "mayo" -> 5;
            case "jun", "junio" -> 6;
            case "jul", "julio" -> 7;
            case "ago", "agosto" -> 8;
            case "sep", "sept", "septiembre" -> 9;
            case "oct", "octubre" -> 10;
            case "nov", "noviembre" -> 11;
            case "dic", "diciembre" -> 12;
            default -> null;
        };
    }

    private static Boolean parseYesNo(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (value.startsWith("SI")) return Boolean.TRUE;
        if (value.equals("NO")) return Boolean.FALSE;
        return null;
    }

    private static double avg(List<BigDecimal> values) {
        if (values.isEmpty()) return 0.0;
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            sum = sum.add(v);
        }
        return sum.divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP).doubleValue();
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private TribunalRiskDto toRisk(TribunalClient client) {
        List<String> issues = new ArrayList<>();
        if (Boolean.FALSE.equals(client.getContModelos())) {
            issues.add("CONT/MODELOS=NO");
        }
        if (Boolean.FALSE.equals(client.getIsIrpfOk()) || hasPending(client.getIsIrpfStatus())) {
            issues.add("IS/IRPF");
        }
        if (Boolean.FALSE.equals(client.getDdccOk()) || hasPending(client.getDdccStatus())) {
            issues.add("DDCC");
        }
        if (Boolean.FALSE.equals(client.getLibrosOk()) || hasPending(client.getLibrosStatus())) {
            issues.add("LIBROS");
        }
        if (issues.isEmpty()) return null;
        return new TribunalRiskDto(
            client.getCliente(),
            client.getCif(),
            client.getGestor(),
            String.join(", ", issues)
        );
    }

    private boolean hasPending(String status) {
        if (status == null) return false;
        String value = status.toUpperCase(Locale.ROOT);
        return value.contains("PDTE") || value.contains("NEGATIVO");
    }
}

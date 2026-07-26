package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.UniversalColumnDto;
import com.asecon.enterpriseiq.dto.UniversalDetectedEntityDto;
import com.asecon.enterpriseiq.dto.UniversalIntakeCandidateDto;
import com.asecon.enterpriseiq.dto.UniversalIntakeDiagnosisDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class UniversalIntakeDiagnosisService {
    private UniversalIntakeDiagnosisService() {}

    private static final Set<String> BUDGET_ALIASES = setOf(
        "presupuesto", "ppto", "budget", "plan", "plan anual", "forecast", "prevision", "prevision cierre",
        "ejecutado", "real", "actual", "desviacion", "variance", "cifra de negocio", "facturacion"
    );
    private static final Set<String> CASH_ALIASES = setOf(
        "txn_date", "fecha", "fecha_valor", "movimiento", "movimientos", "extracto", "banco", "bank",
        "amount", "importe", "saldo", "balance", "counterparty", "contraparte", "descripcion", "description"
    );
    private static final Set<String> TRIBUNAL_ALIASES = setOf(
        "cliente", "clientes", "cif", "nif", "gestor", "manager", "minutas", "minuta", "irpf",
        "ddcc", "libros", "carga_de_trabajo", "carga", "pct_contabilidad", "nas2024"
    );
    private static final Set<String> PAYROLL_ALIASES = setOf(
        "employee_name", "employee", "pay_date", "gross_pay", "net_pay", "role", "employer_cpp", "employer_ei"
    );

    public static UniversalIntakeDiagnosisDto diagnose(String clientKey,
                                                       String filename,
                                                       List<String> headers,
                                                       List<UniversalColumnDto> columns,
                                                       String rowGranularity,
                                                       List<UniversalDetectedEntityDto> entities,
                                                       List<String> semanticWarnings,
                                                       List<Map<String, String>> sampleRows,
                                                       byte[] normalizedCsvBytes,
                                                       boolean convertedFromXlsx) {
        List<String> safeHeaders = headers == null ? List.of() : headers.stream().filter(Objects::nonNull).toList();
        List<UniversalColumnDto> safeColumns = columns == null ? List.of() : columns.stream().filter(Objects::nonNull).toList();
        List<Map<String, String>> safeRows = sampleRows == null ? List.of() : sampleRows;
        List<String> warnings = semanticWarnings == null ? List.of() : semanticWarnings.stream().filter(Objects::nonNull).limit(4).toList();

        BudgetLongNormalizer.Result budgetResult = normalizedCsvBytes == null
            ? new BudgetLongNormalizer.Result(List.of(), null, 0, List.of(), false, List.of(), new byte[0])
            : BudgetLongNormalizer.normalizeToLongCsv(normalizedCsvBytes, clientKey, 8_000, 20);

        CandidateScore budget = scoreBudget(filename, safeHeaders, safeColumns, rowGranularity, budgetResult, convertedFromXlsx);
        CandidateScore accounting = scoreAccounting(filename, safeHeaders, safeColumns, rowGranularity, entities);
        CandidateScore cash = scoreCash(filename, safeHeaders, safeColumns, rowGranularity, safeRows);
        CandidateScore tribunal = scoreTribunal(filename, safeHeaders, safeColumns);
        CandidateScore payroll = scorePayroll(filename, safeHeaders, safeColumns);
        CandidateScore generic = scoreGeneric(filename, safeHeaders, safeColumns, rowGranularity);

        List<CandidateScore> ranked = List.of(budget, accounting, cash, tribunal, payroll, generic).stream()
            .sorted(Comparator.comparingDouble(CandidateScore::score).reversed())
            .toList();

        CandidateScore top = ranked.get(0);
        CandidateScore second = ranked.size() > 1 ? ranked.get(1) : top;
        boolean needsConfirmation = top.score < 0.72d || (top.score - second.score) < 0.12d;
        boolean structureRecognized = !"GENERIC_TABLE".equals(top.kind) && top.score >= 0.58d;
        String canonicalStatus = canonicalStatus(top.kind, top.score, budgetResult, convertedFromXlsx);
        String canonicalDetail = canonicalDetail(top.kind, budgetResult, convertedFromXlsx, safeRows, safeColumns);
        String headline = headline(top.kind, needsConfirmation);
        String detail = detail(top.kind, top.score, budgetResult, safeRows, safeColumns);

        List<String> reasons = new ArrayList<>(top.reasons);
        if (needsConfirmation) {
            reasons.add("La lectura no es totalmente cerrada; conviene confirmar la ruta antes de automatizar el flujo.");
        }

        List<UniversalIntakeCandidateDto> candidates = ranked.stream()
            .limit(3)
            .map(candidate -> new UniversalIntakeCandidateDto(
                candidate.kind,
                candidate.label,
                round(candidate.score),
                confidenceLabel(candidate.score),
                candidate.route,
                candidate.actionLabel,
                candidate.reasons.stream().limit(3).toList()
            ))
            .toList();

        return new UniversalIntakeDiagnosisDto(
            top.kind,
            top.label,
            round(top.score),
            confidenceLabel(top.score),
            needsConfirmation,
            structureRecognized,
            headline,
            detail,
            top.module,
            top.route,
            top.actionLabel,
            canonicalStatus,
            canonicalDetail,
            reasons,
            warnings,
            candidates
        );
    }

    private static CandidateScore scoreBudget(String filename,
                                              List<String> headers,
                                              List<UniversalColumnDto> columns,
                                              String rowGranularity,
                                              BudgetLongNormalizer.Result budgetResult,
                                              boolean convertedFromXlsx) {
        CandidateScore score = new CandidateScore("ANNUAL_BUDGET", "Plan anual / presupuesto", "BUDGET", "/budget", "Abrir plan anual");
        int monthHeaderHits = wideMonthHeaderHits(headers);
        int budgetHeaderHits = headerAliasHits(headers, BUDGET_ALIASES);
        int amountFamilyHits = budgetAmountFamilyHits(headers);
        int semanticKinds = distinctBudgetSemanticKinds(budgetResult);
        boolean looksAccounting = "ACCOUNTING_ENTRY_LINE".equalsIgnoreCase(safe(rowGranularity))
            || hasSemantic(columns, "ACCOUNT_CODE")
            || (hasSemantic(columns, "DEBIT_AMOUNT") && hasSemantic(columns, "CREDIT_AMOUNT"));
        boolean hasPeriodHeader = containsHeaderFragment(headers, "period")
            || containsHeaderFragment(headers, "periodo")
            || containsHeaderFragment(headers, "posting");
        boolean hasActualOrForecast = containsHeaderFragment(headers, "actual") || containsHeaderFragment(headers, "real")
            || containsHeaderFragment(headers, "forecast") || containsHeaderFragment(headers, "prevision")
            || containsHeaderFragment(headers, "desviacion") || containsHeaderFragment(headers, "variance");

        if (monthHeaderHits >= 6) score.add(0.42d, "Detecta meses anuales en cabecera.");
        if (budgetResult.monthKeys() != null && budgetResult.monthKeys().size() >= 6) score.add(0.26d, "Se puede reconstruir una estructura anual por meses.");
        if (amountFamilyHits >= 3) score.add(0.24d, "Combina budget, real, forecast o desviacion en columnas diferenciadas.");
        else if (amountFamilyHits >= 2) score.add(0.14d, "Mezcla al menos dos familias de plan, real o forecast.");
        if (semanticKinds >= 2) score.add(0.14d, "Distingue ingresos, gasto operativo o CAPEX.");
        if (budgetHeaderHits > 0) score.add(Math.min(0.16d, budgetHeaderHits * 0.04d), "Las cabeceras hablan de presupuesto, plan o forecast.");
        if (hasPeriodHeader) score.add(0.08d, "Hay una columna explicita de periodo.");
        if (hasActualOrForecast) score.add(0.08d, "Aparecen columnas o etiquetas de real, forecast o desviacion.");
        if (convertedFromXlsx) score.add(0.04d, "Viene de XLSX, formato comun en presupuestos anuales.");
        if (budgetResult.requiresConfirmation()) score.add(-0.08d, "La lectura anual existe, pero aun necesita confirmacion.");
        if (looksAccounting) score.add(-0.24d, "Tambien tiene rasgos fuertes de mayor contable.");

        return score.clamp();
    }

    private static CandidateScore scoreAccounting(String filename,
                                                  List<String> headers,
                                                  List<UniversalColumnDto> columns,
                                                  String rowGranularity,
                                                  List<UniversalDetectedEntityDto> entities) {
        CandidateScore score = new CandidateScore("ACCOUNTING_LEDGER", "Mayor contable / export contable", "UNIVERSAL", "/universal", "Abrir Universal");
        if ("ACCOUNTING_ENTRY_LINE".equalsIgnoreCase(safe(rowGranularity))) score.add(0.38d, "La granularidad detectada es linea contable.");
        if (hasSemantic(columns, "ACCOUNT_CODE")) score.add(0.18d, "Hay cuenta contable.");
        if (hasSemantic(columns, "DEBIT_AMOUNT")) score.add(0.12d, "Hay importe debe.");
        if (hasSemantic(columns, "CREDIT_AMOUNT")) score.add(0.12d, "Hay importe haber.");
        if (hasSemantic(columns, "ENTRY_ID")) score.add(0.08d, "Hay identificador de asiento.");
        if (hasSemantic(columns, "DOCUMENT_ID") || hasSemantic(columns, "INVOICE_ID")) score.add(0.06d, "Hay documento o factura.");
        if (hasEntity(entities, "ACCOUNT") || hasEntity(entities, "ENTRY")) score.add(0.06d, "Se reconocen entidades contables.");
        if (headerAliasHits(headers, BUDGET_ALIASES) >= 4 && wideMonthHeaderHits(headers) >= 6) score.add(-0.18d, "Tambien parece presupuesto anual.");
        return score.clamp();
    }

    private static CandidateScore scoreCash(String filename,
                                            List<String> headers,
                                            List<UniversalColumnDto> columns,
                                            String rowGranularity,
                                            List<Map<String, String>> rows) {
        CandidateScore score = new CandidateScore("CASH_TRANSACTIONS", "Caja / movimientos bancarios", "TRANSACTIONS", "/imports?mode=transactions", "Usar modulo Caja");
        int cashHeaderHits = headerAliasHits(headers, CASH_ALIASES);
        int dateColumns = countAnalytical(columns, "TEMPORAL");
        int measureColumns = countAnalytical(columns, "MEASURE");
        int observedPeriods = observedPeriods(rows, columns);
        boolean looksAccounting = "ACCOUNTING_ENTRY_LINE".equalsIgnoreCase(safe(rowGranularity))
            || hasSemantic(columns, "ACCOUNT_CODE")
            || hasSemantic(columns, "DEBIT_AMOUNT")
            || hasSemantic(columns, "CREDIT_AMOUNT");

        if (dateColumns >= 1) score.add(0.16d, "Hay fecha operativa.");
        if (measureColumns >= 1) score.add(0.16d, "Hay al menos una magnitud monetaria.");
        if (cashHeaderHits >= 3) score.add(0.18d, "Las cabeceras se parecen a extracto, movimiento o saldo.");
        if (containsHeaderFragment(headers, "counterparty") || containsHeaderFragment(headers, "contraparte")
            || containsHeaderFragment(headers, "description") || containsHeaderFragment(headers, "concepto")) {
            score.add(0.10d, "Incluye texto o contraparte tipico de movimientos.");
        }
        if (observedPeriods >= 1 && observedPeriods <= 3) score.add(0.08d, "El calendario observado encaja con un cierre mensual o trimestral.");
        if (looksAccounting) score.add(-0.18d, "Tambien tiene rasgos de export contable y no solo de caja.");
        if (wideMonthHeaderHits(headers) >= 6) score.add(-0.12d, "La cabecera por meses se parece mas a un plan anual.");
        return score.clamp();
    }

    private static CandidateScore scoreTribunal(String filename, List<String> headers, List<UniversalColumnDto> columns) {
        CandidateScore score = new CandidateScore("TRIBUNAL_PORTFOLIO", "Cartera operativa / Tribunal", "TRIBUNAL", "/tribunal", "Abrir Tribunal");
        int tribunalHits = headerAliasHits(headers, TRIBUNAL_ALIASES);
        int statusCols = countAnalytical(columns, "STATUS");
        int booleanCols = countSemantic(columns, "BOOLEAN") + countSemantic(columns, "TRI_STATE_BOOLEAN");

        if (tribunalHits > 0) score.add(Math.min(0.46d, tribunalHits * 0.06d), "Las cabeceras se parecen a cartera, gestor, minutas o cumplimiento.");
        if (hasSemantic(columns, "TAX_IDENTIFIER")) score.add(0.10d, "Incluye NIF o CIF.");
        if ((statusCols + booleanCols) >= 2) score.add(0.08d, "Hay varios estados booleanos o de cumplimiento.");
        if (hasSemantic(columns, "ACCOUNT_CODE") || hasSemantic(columns, "DEBIT_AMOUNT")) score.add(-0.16d, "Tambien tiene rasgos contables.");
        return score.clamp();
    }

    private static CandidateScore scorePayroll(String filename, List<String> headers, List<UniversalColumnDto> columns) {
        CandidateScore score = new CandidateScore("PAYROLL_DATASET", "Nominas / coste laboral", "UNIVERSAL", "/universal", "Abrir Universal");
        int payrollHits = headerAliasHits(headers, PAYROLL_ALIASES);
        if (payrollHits > 0) score.add(Math.min(0.44d, payrollHits * 0.07d), "Las cabeceras se parecen a nomina, empleado o coste salarial.");
        if (containsHeaderFragment(headers, "employee") || containsHeaderFragment(headers, "empleado")) score.add(0.10d, "Incluye empleados.");
        if (containsHeaderFragment(headers, "gross") || containsHeaderFragment(headers, "net") || containsHeaderFragment(headers, "salary")) {
            score.add(0.10d, "Incluye importes de salario.");
        }
        if (countAnalytical(columns, "TEMPORAL") >= 1) score.add(0.06d, "Tiene fecha de pago o calendario.");
        return score.clamp();
    }

    private static CandidateScore scoreGeneric(String filename,
                                               List<String> headers,
                                               List<UniversalColumnDto> columns,
                                               String rowGranularity) {
        CandidateScore score = new CandidateScore("GENERIC_TABLE", "Tabla analitica generica", "UNIVERSAL", "/universal", "Seguir en Universal");
        int temporal = countAnalytical(columns, "TEMPORAL");
        int measures = countAnalytical(columns, "MEASURE");
        int categories = countAnalytical(columns, "CATEGORICAL") + countAnalytical(columns, "IDENTIFIER") + countAnalytical(columns, "STATUS");

        if (temporal >= 1 && measures >= 1 && categories >= 1) score.add(0.62d, "Hay fecha, medida y categoria para construir una lectura rapida.");
        else if (measures >= 1 && categories >= 1) score.add(0.48d, "Hay importes y categorias suficientes para explorar.");
        else if (columns.size() >= 3) score.add(0.28d, "Es al menos una tabla reutilizable aunque el dominio no sea claro.");
        if ("ROW".equalsIgnoreCase(safe(rowGranularity))) score.add(0.04d, "No hay una entidad dominante cerrada, asi que encaja mejor como tabla generica.");
        return score.clamp();
    }

    private static String canonicalStatus(String kind,
                                          double score,
                                          BudgetLongNormalizer.Result budgetResult,
                                          boolean convertedFromXlsx) {
        if ("ANNUAL_BUDGET".equals(kind)) {
            if (budgetResult.monthKeys() != null && budgetResult.monthKeys().size() >= 6 && !budgetResult.requiresConfirmation()) return "READY";
            if (budgetResult.monthKeys() != null && budgetResult.monthKeys().size() >= 6) return "NEEDS_CONFIRMATION";
            return convertedFromXlsx ? "NEEDS_SHEET_OR_HEADER" : "PARTIAL";
        }
        if ("GENERIC_TABLE".equals(kind)) return score >= 0.55d ? "READY_FOR_UNIVERSAL" : "UNCLEAR";
        return score >= 0.6d ? "READY" : "PARTIAL";
    }

    private static String canonicalDetail(String kind,
                                          BudgetLongNormalizer.Result budgetResult,
                                          boolean convertedFromXlsx,
                                          List<Map<String, String>> rows,
                                          List<UniversalColumnDto> columns) {
        if ("ANNUAL_BUDGET".equals(kind)) {
            if (budgetResult.monthKeys() != null && budgetResult.monthKeys().size() >= 6 && !budgetResult.requiresConfirmation()) {
                return "Ya hay base para llevarlo a Plan anual y compararlo contra real o forecast.";
            }
            if (budgetResult.monthKeys() != null && budgetResult.monthKeys().size() >= 6) {
                return "Detecta estructura anual, pero conviene confirmar hoja, cabecera o mapeo antes de cerrar la lectura.";
            }
            return convertedFromXlsx
                ? "Prueba otra hoja o cambia la fila de cabecera: aun no se detectan bien los meses del ejercicio."
                : "Falta una estructura anual clara por meses o por columnas de budget/actual/forecast.";
        }
        if ("CASH_TRANSACTIONS".equals(kind)) {
            int periods = observedPeriods(rows, columns);
            return periods > 1
                ? "Si el objetivo es cierre mensual, separa por periodos o subelo por Caja con un solo mes por fichero."
                : "Listo para Caja: fecha + importe + detalle operativo parecen suficientes.";
        }
        if ("ACCOUNTING_LEDGER".equals(kind)) {
            return "Encaja mejor en Universal: primero lectura por cuentas, terceros y meses; luego dashboards o comparativas.";
        }
        if ("TRIBUNAL_PORTFOLIO".equals(kind)) {
            return "Encaja en Tribunal: cartera, riesgos y carga operativa antes que un dashboard generico.";
        }
        if ("PAYROLL_DATASET".equals(kind)) {
            return "Encaja en Universal: usa una vista de coste mensual o ranking por empleado/rol.";
        }
        return "Universal puede abrir una vista rapida, pero aun no hay una familia de flujo cerrada del todo.";
    }

    private static String headline(String kind, boolean needsConfirmation) {
        String base = switch (kind) {
            case "ANNUAL_BUDGET" -> "Parece un plan anual";
            case "ACCOUNTING_LEDGER" -> "Parece un mayor contable";
            case "CASH_TRANSACTIONS" -> "Parece un fichero de caja";
            case "TRIBUNAL_PORTFOLIO" -> "Parece una cartera operativa";
            case "PAYROLL_DATASET" -> "Parece un dataset de nominas";
            default -> "Parece una tabla analitica";
        };
        return needsConfirmation ? base + " (confirmacion recomendada)" : base;
    }

    private static String detail(String kind,
                                 double score,
                                 BudgetLongNormalizer.Result budgetResult,
                                 List<Map<String, String>> rows,
                                 List<UniversalColumnDto> columns) {
        if ("ANNUAL_BUDGET".equals(kind)) {
            int months = budgetResult.monthKeys() == null ? 0 : budgetResult.monthKeys().size();
            return months >= 6
                ? "He detectado " + months + " meses y una lectura anual utilizable. El siguiente paso natural es Plan anual."
                : "Tiene rasgos de presupuesto, pero aun no detecto bien la estructura anual completa.";
        }
        if ("ACCOUNTING_LEDGER".equals(kind)) {
            return "Tiene cuentas, debe/haber y granularidad de asiento. Sirve para lectura contable, no para tratarlo como caja o presupuesto.";
        }
        if ("CASH_TRANSACTIONS".equals(kind)) {
            int periods = observedPeriods(rows, columns);
            return periods > 1
                ? "Tiene pinta de movimientos de caja, pero mezcla varios meses. Para cierre mensual conviene un periodo por fichero."
                : "Tiene fecha, importe y detalle operativo. Encaja en el flujo de caja y cierre mensual.";
        }
        if ("TRIBUNAL_PORTFOLIO".equals(kind)) {
            return "Las cabeceras se parecen a gestor, minutas, carga o cumplimiento. Encaja mejor en Tribunal.";
        }
        if ("PAYROLL_DATASET".equals(kind)) {
            return "Las columnas se parecen a coste salarial por empleado o rol. Universal puede leerlo bien como analisis operativo.";
        }
        return score >= 0.55d
            ? "No detecto un flujo consultivo cerrado, pero si una tabla util para explorar en Universal."
            : "La estructura aun es ambigua. Conviene revisar cabeceras, hoja o el objetivo antes de seguir.";
    }

    private static int wideMonthHeaderHits(List<String> headers) {
        int hits = 0;
        for (String header : headers) {
            String normalized = normalize(header);
            if (normalized.startsWith("enero") || normalized.startsWith("febrero") || normalized.startsWith("marzo")
                || normalized.startsWith("abril") || normalized.startsWith("mayo") || normalized.startsWith("junio")
                || normalized.startsWith("julio") || normalized.startsWith("agosto") || normalized.startsWith("septiembre")
                || normalized.startsWith("octubre") || normalized.startsWith("noviembre") || normalized.startsWith("diciembre")) {
                hits++;
            }
        }
        return hits;
    }

    private static int distinctBudgetSemanticKinds(BudgetLongNormalizer.Result result) {
        if (result == null || result.sampleRows() == null) return 0;
        return (int) result.sampleRows().stream()
            .map(BudgetLongNormalizer.LongRow::semanticKind)
            .filter(Objects::nonNull)
            .map(value -> value.trim().toUpperCase(Locale.ROOT))
            .filter(value -> Set.of("REVENUE", "OPEX", "CAPEX").contains(value))
            .distinct()
            .count();
    }

    private static int budgetAmountFamilyHits(List<String> headers) {
        int hits = 0;
        if (containsHeaderFragment(headers, "budget") || containsHeaderFragment(headers, "presupuesto") || containsHeaderFragment(headers, "plan")) hits++;
        if (containsHeaderFragment(headers, "actual") || containsHeaderFragment(headers, "real") || containsHeaderFragment(headers, "ejecutado")) hits++;
        if (containsHeaderFragment(headers, "forecast") || containsHeaderFragment(headers, "prevision") || containsHeaderFragment(headers, "estimate")) hits++;
        if (containsHeaderFragment(headers, "desviacion") || containsHeaderFragment(headers, "variance") || containsHeaderFragment(headers, "delta")) hits++;
        return hits;
    }

    private static boolean hasSemantic(List<UniversalColumnDto> columns, String semanticType) {
        return countSemantic(columns, semanticType) > 0;
    }

    private static int countSemantic(List<UniversalColumnDto> columns, String semanticType) {
        return (int) columns.stream()
            .filter(column -> semanticType.equalsIgnoreCase(safe(column.semanticType())))
            .count();
    }

    private static int countAnalytical(List<UniversalColumnDto> columns, String analyticalType) {
        return (int) columns.stream()
            .filter(column -> analyticalType.equalsIgnoreCase(safe(column.analyticalType())))
            .count();
    }

    private static boolean hasEntity(List<UniversalDetectedEntityDto> entities, String entityType) {
        if (entities == null) return false;
        return entities.stream().anyMatch(entity -> entityType.equalsIgnoreCase(safe(entity.entityType())));
    }

    private static int headerAliasHits(List<String> headers, Set<String> aliases) {
        int hits = 0;
        for (String header : headers) {
            String normalized = normalize(header);
            if (aliases.contains(normalized)) {
                hits++;
                continue;
            }
            for (String alias : aliases) {
                if (normalized.contains(alias)) {
                    hits++;
                    break;
                }
            }
        }
        return hits;
    }

    private static boolean containsHeaderFragment(List<String> headers, String fragment) {
        String normalizedFragment = normalize(fragment);
        for (String header : headers) {
            if (normalize(header).contains(normalizedFragment)) return true;
        }
        return false;
    }

    private static int observedPeriods(List<Map<String, String>> rows, List<UniversalColumnDto> columns) {
        List<String> temporalColumns = columns.stream()
            .filter(column -> "TEMPORAL".equalsIgnoreCase(safe(column.analyticalType())) || "DATE".equalsIgnoreCase(safe(column.semanticType())))
            .map(UniversalColumnDto::name)
            .filter(Objects::nonNull)
            .limit(3)
            .toList();
        if (temporalColumns.isEmpty()) return 0;

        Set<YearMonth> periods = new LinkedHashSet<>();
        for (Map<String, String> row : rows) {
            for (String column : temporalColumns) {
                YearMonth ym = parseYearMonth(clean(row.get(column)));
                if (ym != null) {
                    periods.add(ym);
                    break;
                }
            }
            if (periods.size() >= 12) break;
        }
        return periods.size();
    }

    private static YearMonth parseYearMonth(String raw) {
        if (raw == null || raw.isBlank()) return null;
        List<DateTimeFormatter> formatters = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM"),
            DateTimeFormatter.ofPattern("MM/yyyy")
        );
        for (DateTimeFormatter formatter : formatters) {
            try {
                if ("yyyy-MM".equals(formatter.toString()) || "Value(MonthOfYear,2)'/'Value(YearOfEra,4,19,EXCEEDS_PAD)".equals(formatter.toString())) {
                    return YearMonth.parse(raw, formatter);
                }
            } catch (Exception ignored) {
            }
        }
        if (raw.matches("^\\d{4}-\\d{2}$")) {
            try {
                return YearMonth.parse(raw);
            } catch (Exception ignored) {
            }
        }
        if (raw.matches("^\\d{2}/\\d{4}$")) {
            try {
                return YearMonth.parse(raw, DateTimeFormatter.ofPattern("MM/yyyy"));
            } catch (Exception ignored) {
            }
        }
        for (DateTimeFormatter formatter : Arrays.asList(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy")
        )) {
            try {
                LocalDate date = LocalDate.parse(raw, formatter);
                return YearMonth.from(date);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        return Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
    }

    private static Set<String> setOf(String... values) {
        return Arrays.stream(values)
            .map(UniversalIntakeDiagnosisService::normalize)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String clean(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isBlank() || "-".equals(trimmed) ? null : trimmed;
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

    private static String confidenceLabel(double score) {
        if (score >= 0.78d) return "HIGH";
        if (score >= 0.58d) return "MEDIUM";
        return "LOW";
    }

    private static final class CandidateScore {
        private final String kind;
        private final String label;
        private final String module;
        private final String route;
        private final String actionLabel;
        private double score;
        private final List<String> reasons = new ArrayList<>();

        private CandidateScore(String kind, String label, String module, String route, String actionLabel) {
            this.kind = kind;
            this.label = label;
            this.module = module;
            this.route = route;
            this.actionLabel = actionLabel;
            this.score = 0.05d;
        }

        private void add(double delta, String reason) {
            this.score += delta;
            if (reason != null && !reason.isBlank()) this.reasons.add(reason);
        }

        private CandidateScore clamp() {
            this.score = Math.max(0.05d, Math.min(0.99d, this.score));
            return this;
        }

        private double score() {
            return score;
        }
    }
}

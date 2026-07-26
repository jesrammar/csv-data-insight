package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.UniversalColumnDto;
import com.asecon.enterpriseiq.dto.UniversalDetectedEntityDto;
import com.asecon.enterpriseiq.dto.UniversalRelationshipDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class UniversalSemanticProfiler {
    private static final Set<String> TRUE_VALUES = Set.of("si", "sí", "yes", "true", "1", "y");
    private static final Set<String> FALSE_VALUES = Set.of("no", "false", "0", "n");
    private static final Set<String> EMPTY_NULL_SEMANTICS = Set.of("UNKNOWN");

    private static final Set<String> ACCOUNT_CODE_ALIASES = setOf(
        "cuenta_contable", "account_code", "gl_account", "ledger_account", "account_no", "chart_account"
    );
    private static final Set<String> ACCOUNT_NAME_ALIASES = setOf(
        "cuenta_nombre", "account_name", "gl_account_name", "ledger_account_name", "account_label"
    );
    private static final Set<String> DEBIT_ALIASES = setOf(
        "debe", "debit", "debit_amount", "importe_debe", "debit_eur"
    );
    private static final Set<String> CREDIT_ALIASES = setOf(
        "haber", "credit", "credit_amount", "importe_haber", "credit_eur"
    );
    private static final Set<String> SIGNED_AMOUNT_ALIASES = setOf(
        "importe", "amount", "amount_eur", "signed_amount", "importe_neto", "saldo_linea", "movement_amount"
    );
    private static final Set<String> INVOICE_ID_ALIASES = setOf(
        "factura_id", "invoice_id", "invoice_no", "invoice_number", "bill_id", "bill_number"
    );
    private static final Set<String> DOCUMENT_ID_ALIASES = setOf(
        "documento_id", "document_id", "doc_id", "document_no", "document_number"
    );
    private static final Set<String> ENTRY_ID_ALIASES = setOf(
        "asiento_id", "entry_id", "journal_entry_id", "posting_id", "seat_id"
    );
    private static final Set<String> PARTY_ID_ALIASES = setOf(
        "tercero_id", "party_id", "customer_id", "supplier_id", "vendor_id", "counterparty_id", "client_id"
    );
    private static final Set<String> PARTY_NAME_ALIASES = setOf(
        "tercero_nombre", "party_name", "customer_name", "supplier_name", "vendor_name", "client_name", "counterparty_name"
    );
    private static final Set<String> PARTY_TAX_ID_ALIASES = setOf(
        "tercero_nif", "party_tax_id", "tax_identifier", "vat_number", "nif", "cif"
    );
    private static final Set<String> PROJECT_ID_ALIASES = setOf(
        "proyecto_id", "project_id", "job_id"
    );
    private static final Set<String> PROJECT_NAME_ALIASES = setOf(
        "proyecto_nombre", "project_name", "job_name"
    );
    private static final Set<String> DATE_ISSUE_ALIASES = setOf(
        "fecha_emision", "issue_date", "invoice_date", "document_date"
    );
    private static final Set<String> DATE_DUE_ALIASES = setOf(
        "fecha_vencimiento", "due_date", "maturity_date", "payment_due_date"
    );
    private static final Set<String> DATE_POSTING_ALIASES = setOf(
        "fecha_contable", "posting_date", "accounting_date", "entry_date"
    );
    private static final Set<String> TAXABLE_BASE_ALIASES = setOf(
        "base_imponible_eur", "base_imponible", "taxable_base", "taxable_amount", "net_tax_base"
    );
    private static final Set<String> VAT_AMOUNT_ALIASES = setOf(
        "iva_eur", "vat_amount", "tax_amount", "vat_value"
    );
    private static final Set<String> DOCUMENT_TOTAL_ALIASES = setOf(
        "total_documento_eur", "document_total", "invoice_total", "gross_document_total"
    );
    private static final Set<String> BANK_RECONCILED_ALIASES = setOf(
        "conciliado_banco", "bank_reconciled", "reconciled_bank", "bank_matched"
    );
    private static final Set<String> COST_CENTER_ALIASES = setOf(
        "centro_coste", "cost_center", "cost_centre"
    );
    private static final Set<String> DEPARTMENT_ALIASES = setOf(
        "departamento", "department", "dept"
    );
    private static final Set<String> CITY_ALIASES = setOf(
        "ciudad_tercero", "party_city", "customer_city", "supplier_city", "city"
    );
    private static final Set<String> PRIMARY_DOC_LINE_ALIASES = setOf(
        "es_linea_documento_principal", "is_primary_document_line", "is_document_main_line"
    );
    private static final Set<String> DOCUMENT_TYPE_ALIASES = setOf(
        "tipo_documento", "document_type", "doc_type"
    );
    private static final Set<String> OPERATION_TYPE_ALIASES = setOf(
        "tipo_operacion", "operation_type", "movement_type"
    );

    public Profile enrich(List<UniversalColumnDto> baseColumns, List<Map<String, String>> sampleRows) {
        List<UniversalColumnDto> safeColumns = baseColumns == null ? List.of() : baseColumns;
        List<Map<String, String>> safeRows = sampleRows == null ? List.of() : sampleRows;
        Map<String, UniversalColumnDto> byName = safeColumns.stream()
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(UniversalColumnDto::name, c -> c, (a, b) -> a, LinkedHashMap::new));

        Map<String, ColumnProfileBuilder> builders = new LinkedHashMap<>();
        for (UniversalColumnDto column : safeColumns) {
            if (column == null || column.name() == null) continue;
            builders.put(column.name(), initialProfile(column, safeRows));
        }

        String rowGranularity = detectRowGranularity(builders.values());
        List<UniversalRelationshipDto> relationships = detectRelationships(builders, safeRows);
        applyRelationships(builders, relationships);
        List<UniversalDetectedEntityDto> entities = detectEntities(builders.values(), relationships);
        applyContextualSemantics(builders, safeRows, rowGranularity, entities, relationships);
        List<String> semanticWarnings = detectDatasetWarnings(builders.values(), safeRows, rowGranularity, entities);

        List<UniversalColumnDto> columns = builders.values().stream()
            .sorted(Comparator.comparing(b -> b.name.toLowerCase(Locale.ROOT)))
            .map(ColumnProfileBuilder::build)
            .collect(Collectors.toList());

        return new Profile(columns, rowGranularity, entities, relationships, semanticWarnings);
    }

    private static ColumnProfileBuilder initialProfile(UniversalColumnDto column, List<Map<String, String>> sampleRows) {
        String normalized = normalize(column.name());
        List<String> values = sampleValues(sampleRows, column.name(), 120);
        Set<String> distinctNormalizedValues = values.stream()
            .map(UniversalSemanticProfiler::normalizeValue)
            .filter(v -> !v.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean booleanLike = isBooleanLike(distinctNormalizedValues);
        String physicalType = inferPhysicalType(column, booleanLike);
        String semanticType = inferSemanticType(normalized, column, physicalType, distinctNormalizedValues);
        if (requiresStringPhysicalType(semanticType)) {
            physicalType = "STRING";
        }
        String analyticalType = inferAnalyticalType(semanticType, physicalType, column);
        String detectedType = inferDisplayType(semanticType, physicalType);
        String nullSemantics = inferInitialNullSemantics(normalized, semanticType, column);
        double confidence = inferConfidence(normalized, semanticType, column, distinctNormalizedValues);
        List<String> validAggregations = inferAggregations(semanticType, normalized);
        List<String> recommendedCharts = inferCharts(semanticType, analyticalType);
        List<String> warnings = new ArrayList<>();

        if ("ACCOUNT_CODE".equals(semanticType) && "number".equalsIgnoreCase(safe(column.detectedType()))) {
            warnings.add("Se trata como código contable y categoría; no se calculan medias ni percentiles.");
        }
        if ("BOOLEAN".equals(semanticType) || "TRI_STATE_BOOLEAN".equals(semanticType)) {
            warnings.add("Conviene leerlo como estado o proporción aplicable, no como importe.");
        }

        return new ColumnProfileBuilder(
            column,
            detectedType,
            physicalType,
            semanticType,
            analyticalType,
            nullSemantics,
            confidence,
            new ArrayList<>(validAggregations),
            new ArrayList<>(recommendedCharts),
            new ArrayList<>(),
            warnings
        );
    }

    private static String detectRowGranularity(Iterable<ColumnProfileBuilder> columns) {
        ColumnProfileBuilder entryId = findBySemantic(columns, "ENTRY_ID");
        ColumnProfileBuilder invoiceId = findBySemantic(columns, "INVOICE_ID");
        ColumnProfileBuilder documentId = findBySemantic(columns, "DOCUMENT_ID");
        ColumnProfileBuilder accountCode = findBySemantic(columns, "ACCOUNT_CODE");
        ColumnProfileBuilder debit = findBySemantic(columns, "DEBIT_AMOUNT");
        ColumnProfileBuilder credit = findBySemantic(columns, "CREDIT_AMOUNT");
        ColumnProfileBuilder partyId = findBySemantic(columns, "PARTY_ID");

        if (entryId != null && accountCode != null && (debit != null || credit != null)) {
            return "ACCOUNTING_ENTRY_LINE";
        }
        if (invoiceId != null && accountCode != null) {
            return "DOCUMENT_LINE";
        }
        if (invoiceId != null) {
            return "INVOICE";
        }
        if (documentId != null) {
            return "DOCUMENT";
        }
        if (partyId != null) {
            return "PARTY";
        }
        return "ROW";
    }

    private static List<UniversalRelationshipDto> detectRelationships(Map<String, ColumnProfileBuilder> builders, List<Map<String, String>> rows) {
        if (builders.isEmpty() || rows.isEmpty()) return List.of();

        List<ColumnProfileBuilder> sources = builders.values().stream()
            .filter(b -> Set.of("ACCOUNT_CODE", "ENTRY_ID", "DOCUMENT_ID", "INVOICE_ID", "PARTY_ID", "PROJECT_ID", "IDENTIFIER").contains(b.semanticType))
            .toList();
        List<ColumnProfileBuilder> targets = builders.values().stream()
            .filter(b -> !Set.of("ACCOUNT_CODE", "ENTRY_ID", "DOCUMENT_ID", "INVOICE_ID", "PARTY_ID", "PROJECT_ID").contains(b.semanticType))
            .toList();

        List<UniversalRelationshipDto> out = new ArrayList<>();
        for (ColumnProfileBuilder source : sources) {
            for (ColumnProfileBuilder target : targets) {
                if (source == target) continue;
                UniversalRelationshipDto rel = detectFunctionalDependency(source, target, rows);
                if (rel != null) out.add(rel);
            }
        }
        return out.stream()
            .sorted(Comparator.comparingDouble(UniversalRelationshipDto::confidence).reversed())
            .limit(16)
            .collect(Collectors.toList());
    }

    private static UniversalRelationshipDto detectFunctionalDependency(ColumnProfileBuilder source,
                                                                       ColumnProfileBuilder target,
                                                                       List<Map<String, String>> rows) {
        Map<String, Set<String>> valuesByKey = new HashMap<>();
        int observations = 0;
        for (Map<String, String> row : rows) {
            String key = clean(row.get(source.name));
            String value = clean(row.get(target.name));
            if (key == null || value == null) continue;
            observations++;
            Set<String> seen = valuesByKey.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
            if (seen.size() < 4) seen.add(value);
        }
        if (observations < 6 || valuesByKey.size() < 3) return null;

        long stableKeys = valuesByKey.values().stream().filter(v -> v.size() <= 1).count();
        double confidence = stableKeys / (double) Math.max(1, valuesByKey.size());
        if (confidence < 0.96d) return null;

        String relationType = relationType(source.semanticType, target.semanticType, target.name);
        String detail = relationDetail(source.name, target.name, relationType, confidence, valuesByKey.size());
        return new UniversalRelationshipDto(source.name, target.name, relationType, round(confidence), detail);
    }

    private static void applyRelationships(Map<String, ColumnProfileBuilder> builders, List<UniversalRelationshipDto> relationships) {
        for (UniversalRelationshipDto rel : relationships) {
            ColumnProfileBuilder source = builders.get(rel.sourceColumn());
            ColumnProfileBuilder target = builders.get(rel.targetColumn());
            if (source == null || target == null) continue;
            source.relatedColumns.add(target.name);
            target.relatedColumns.add(source.name);
        }
    }

    private static List<UniversalDetectedEntityDto> detectEntities(Iterable<ColumnProfileBuilder> columns,
                                                                   List<UniversalRelationshipDto> relationships) {
        Map<String, String> labelBySource = new HashMap<>();
        for (UniversalRelationshipDto rel : relationships) {
            if ("LABEL".equals(rel.relationType())) {
                labelBySource.putIfAbsent(rel.sourceColumn(), rel.targetColumn());
            }
        }

        List<UniversalDetectedEntityDto> out = new ArrayList<>();
        for (ColumnProfileBuilder column : columns) {
            String entityType = switch (column.semanticType) {
                case "ENTRY_ID" -> "ENTRY";
                case "DOCUMENT_ID" -> "DOCUMENT";
                case "INVOICE_ID" -> "INVOICE";
                case "PARTY_ID" -> "PARTY";
                case "PROJECT_ID" -> "PROJECT";
                case "ACCOUNT_CODE" -> "ACCOUNT";
                default -> null;
            };
            if (entityType == null) continue;
            out.add(new UniversalDetectedEntityDto(
                entityType,
                column.name,
                labelBySource.get(column.name),
                column.base.uniqueCount()
            ));
        }

        return out.stream()
            .sorted(Comparator.comparing(UniversalDetectedEntityDto::entityType))
            .toList();
    }

    private static void applyContextualSemantics(Map<String, ColumnProfileBuilder> builders,
                                                 List<Map<String, String>> rows,
                                                 String rowGranularity,
                                                 List<UniversalDetectedEntityDto> entities,
                                                 List<UniversalRelationshipDto> relationships) {
        ColumnProfileBuilder invoiceId = findBySemantic(builders.values(), "INVOICE_ID");
        ColumnProfileBuilder accountCode = findBySemantic(builders.values(), "ACCOUNT_CODE");
        ColumnProfileBuilder debit = findBySemantic(builders.values(), "DEBIT_AMOUNT");
        ColumnProfileBuilder credit = findBySemantic(builders.values(), "CREDIT_AMOUNT");
        ColumnProfileBuilder bankReconciled = findBySemantic(builders.values(), "TRI_STATE_BOOLEAN");

        for (ColumnProfileBuilder builder : builders.values()) {
            String normalized = normalize(builder.name);
            if (TAXABLE_BASE_ALIASES.contains(normalized) && "ACCOUNTING_ENTRY_LINE".equals(rowGranularity)) {
                long nonNullRows = rows.stream().map(r -> clean(r.get(builder.name))).filter(Objects::nonNull).count();
                double coverage = rows.isEmpty() ? 0.0 : nonNullRows / (double) rows.size();
                if (coverage <= 0.25d) {
                    builder.nullSemantics = "STRUCTURAL";
                    builder.validAggregations = ensure(builder.validAggregations, "SUM_VALUE", "SUM_DISTINCT_VALUE");
                    builder.warnings.add("Se informa solo en líneas principales de factura; la ausencia en otras líneas parece estructural.");
                }
            }

            if (DOCUMENT_TOTAL_ALIASES.contains(normalized) && invoiceId != null) {
                if (isRepeatedByKey(rows, invoiceId.name, builder.name)) {
                    builder.validAggregations = ensure(builder.validAggregations, "SUM_DISTINCT_VALUE", "DISTINCT_INVOICE_COUNT");
                    builder.warnings.add("Se repite en varias líneas de la misma factura; conviene deduplicar por factura antes de sumar.");
                }
            }

            if (DATE_ISSUE_ALIASES.contains(normalized) && invoiceId != null && "ACCOUNTING_ENTRY_LINE".equals(rowGranularity)) {
                builder.validAggregations = ensure(builder.validAggregations, "DISTINCT_INVOICE_COUNT", "DISTINCT_PARTY_COUNT");
                builder.warnings.add("La fecha se repite por línea contable; para facturación conviene contar facturas distintas.");
            }

            if (BANK_RECONCILED_ALIASES.contains(normalized)) {
                builder.semanticType = "TRI_STATE_BOOLEAN";
                builder.analyticalType = "STATUS";
                builder.detectedType = "text";
                builder.nullSemantics = "NOT_APPLICABLE";
                builder.validAggregations = ensure(builder.validAggregations,
                    "APPLICABLE_ROW_COUNT", "APPLICABLE_RATE", "SUM_DEBIT", "SUM_CREDIT", "NET_BALANCE");
                builder.warnings.add("La conciliación debe leerse solo sobre movimientos bancarios aplicables.");
            }

            if (ACCOUNT_CODE_ALIASES.contains(normalized)) {
                builder.validAggregations = ensure(builder.validAggregations,
                    "ROW_COUNT", "DISTINCT_ENTRY_COUNT", "SUM_DEBIT", "SUM_CREDIT", "NET_BALANCE");
                builder.warnings.add("Agrupa mejor por cuenta o por prefijo de cuenta que por medidas numéricas.");
            }
        }

        if (bankReconciled != null && accountCode != null) {
            bankReconciled.relatedColumns.add(accountCode.name);
            if (debit != null) bankReconciled.relatedColumns.add(debit.name);
            if (credit != null) bankReconciled.relatedColumns.add(credit.name);
        }

        for (UniversalRelationshipDto rel : relationships) {
            if (!"LABEL".equals(rel.relationType())) continue;
            ColumnProfileBuilder source = builders.get(rel.sourceColumn());
            ColumnProfileBuilder target = builders.get(rel.targetColumn());
            if (source == null || target == null) continue;
            source.relatedColumns.add(target.name);
            target.relatedColumns.add(source.name);
        }

        if ("ACCOUNTING_ENTRY_LINE".equals(rowGranularity)) {
            for (ColumnProfileBuilder builder : builders.values()) {
                if ("CURRENCY".equals(builder.semanticType) && builder.base.uniqueCount() > 0 && builder.base.uniqueCount() < Math.max(20, builder.base.totalCount() / 2)) {
                    builder.warnings.add("La lectura debe respetar la granularidad por línea; revisa si esta métrica pertenece a una entidad repetida.");
                }
            }
        }
    }

    private static List<String> detectDatasetWarnings(Iterable<ColumnProfileBuilder> columns,
                                                      List<Map<String, String>> rows,
                                                      String rowGranularity,
                                                      List<UniversalDetectedEntityDto> entities) {
        List<String> warnings = new ArrayList<>();
        if ("ACCOUNTING_ENTRY_LINE".equals(rowGranularity)) {
            warnings.add("El dataset parece estar a nivel de línea contable: una misma factura o documento puede repetirse en varias filas.");
        }

        ColumnProfileBuilder debit = findBySemantic(columns, "DEBIT_AMOUNT");
        ColumnProfileBuilder credit = findBySemantic(columns, "CREDIT_AMOUNT");
        if (debit != null && credit != null) {
            BigDecimal debitTotal = sumColumn(rows, debit.name);
            BigDecimal creditTotal = sumColumn(rows, credit.name);
            if (debitTotal != null && creditTotal != null) {
                BigDecimal delta = debitTotal.subtract(creditTotal).abs().setScale(2, RoundingMode.HALF_UP);
                if (delta.compareTo(new BigDecimal("0.01")) <= 0) {
                    warnings.add("Debe y haber están cuadrados en la muestra analizada.");
                } else {
                    warnings.add("Debe y haber no cuadran exactamente en la muestra analizada; revisa exportación o filtros.");
                }
            }
        }

        for (ColumnProfileBuilder builder : columns) {
            if ("STRUCTURAL".equals(builder.nullSemantics)) {
                warnings.add("Se detectan nulos estructurales en " + builder.name + ": no deberían leerse automáticamente como error de calidad.");
            }
            if ("TRI_STATE_BOOLEAN".equals(builder.semanticType) && BANK_RECONCILED_ALIASES.contains(normalize(builder.name))) {
                warnings.add("La conciliación bancaria debe calcularse solo sobre movimientos bancarios aplicables.");
            }
        }

        if (entities.stream().anyMatch(e -> "INVOICE".equals(e.entityType()))) {
            warnings.add("Para métricas de facturación conviene alternar entre filas, facturas distintas e importe para evitar dobles conteos.");
        }

        return warnings.stream().distinct().limit(8).toList();
    }

    private static String inferPhysicalType(UniversalColumnDto column, boolean booleanLike) {
        String detectedType = safe(column.detectedType()).toLowerCase(Locale.ROOT);
        if ("date".equals(detectedType)) return "DATE";
        if ("number".equals(detectedType)) return "NUMBER";
        if (booleanLike) return "BOOLEAN";
        return "STRING";
    }

    private static String inferSemanticType(String normalized,
                                            UniversalColumnDto column,
                                            String physicalType,
                                            Set<String> distinctValues) {
        if (ACCOUNT_CODE_ALIASES.contains(normalized)) return "ACCOUNT_CODE";
        if (ACCOUNT_NAME_ALIASES.contains(normalized)) return "ACCOUNT_NAME";
        if (DEBIT_ALIASES.contains(normalized)) return "DEBIT_AMOUNT";
        if (CREDIT_ALIASES.contains(normalized)) return "CREDIT_AMOUNT";
        if (SIGNED_AMOUNT_ALIASES.contains(normalized)) return "SIGNED_AMOUNT";
        if (INVOICE_ID_ALIASES.contains(normalized)) return "INVOICE_ID";
        if (DOCUMENT_ID_ALIASES.contains(normalized)) return "DOCUMENT_ID";
        if (ENTRY_ID_ALIASES.contains(normalized)) return "ENTRY_ID";
        if (PARTY_ID_ALIASES.contains(normalized)) return "PARTY_ID";
        if (PARTY_NAME_ALIASES.contains(normalized)) return "PARTY_NAME";
        if (PARTY_TAX_ID_ALIASES.contains(normalized)) return "TAX_IDENTIFIER";
        if (PROJECT_ID_ALIASES.contains(normalized)) return "PROJECT_ID";
        if (PROJECT_NAME_ALIASES.contains(normalized)) return "PROJECT_NAME";
        if (DATE_ISSUE_ALIASES.contains(normalized)) return "DATE";
        if (DATE_DUE_ALIASES.contains(normalized)) return "DATE";
        if (DATE_POSTING_ALIASES.contains(normalized)) return "DATE";
        if (TAXABLE_BASE_ALIASES.contains(normalized) || VAT_AMOUNT_ALIASES.contains(normalized) || DOCUMENT_TOTAL_ALIASES.contains(normalized)) return "CURRENCY";
        if (BANK_RECONCILED_ALIASES.contains(normalized)) return "TRI_STATE_BOOLEAN";
        if (COST_CENTER_ALIASES.contains(normalized) || DEPARTMENT_ALIASES.contains(normalized) || CITY_ALIASES.contains(normalized)) return "CATEGORICAL_DIMENSION";
        if (PRIMARY_DOC_LINE_ALIASES.contains(normalized)) return "BOOLEAN";
        if (DOCUMENT_TYPE_ALIASES.contains(normalized) || OPERATION_TYPE_ALIASES.contains(normalized)) return "STATUS";

        if ("DATE".equals(physicalType)) return "DATE";
        if ("BOOLEAN".equals(physicalType)) {
            return distinctValues.size() > 2 ? "TRI_STATE_BOOLEAN" : "BOOLEAN";
        }

        if ("NUMBER".equals(physicalType)) {
            if (normalized.contains("percent") || normalized.contains("porcentaje") || normalized.contains("ratio")) return "PERCENTAGE";
            if (normalized.contains("qty") || normalized.contains("quantity") || normalized.contains("cantidad")) return "QUANTITY";
            if (normalized.endsWith("_eur") || normalized.contains("importe") || normalized.contains("amount") || normalized.contains("saldo")) return "CURRENCY";
        }

        double uniqueRatio = column.totalCount() <= 0 ? 0.0 : column.uniqueCount() / (double) Math.max(1L, column.totalCount() - column.nullCount());
        if (normalized.endsWith("_id") || normalized.equals("id")) return "IDENTIFIER";
        if (normalized.contains("iban") || normalized.contains("bank_account")) return "BANK_ACCOUNT";
        if (uniqueRatio >= 0.90d && column.uniqueCount() > 8) return "IDENTIFIER";
        if (column.uniqueCount() <= 24) return "CATEGORICAL_DIMENSION";
        return "FREE_TEXT";
    }

    private static String inferAnalyticalType(String semanticType, String physicalType, UniversalColumnDto column) {
        return switch (semanticType) {
            case "ACCOUNT_CODE", "ACCOUNT_NAME", "PARTY_NAME", "PROJECT_NAME", "CATEGORICAL_DIMENSION" -> "CATEGORICAL";
            case "ENTRY_ID", "DOCUMENT_ID", "INVOICE_ID", "PARTY_ID", "PROJECT_ID", "IDENTIFIER", "TAX_IDENTIFIER", "BANK_ACCOUNT" -> "IDENTIFIER";
            case "BOOLEAN", "TRI_STATE_BOOLEAN", "STATUS" -> "STATUS";
            case "DATE", "DATETIME" -> "TEMPORAL";
            case "DEBIT_AMOUNT", "CREDIT_AMOUNT", "SIGNED_AMOUNT", "CURRENCY", "PERCENTAGE", "QUANTITY" -> "MEASURE";
            default -> "STRING".equals(physicalType) && column.uniqueCount() <= 40 ? "CATEGORICAL" : "TEXT";
        };
    }

    private static boolean requiresStringPhysicalType(String semanticType) {
        return Set.of(
            "ACCOUNT_CODE",
            "ACCOUNT_NAME",
            "ENTRY_ID",
            "DOCUMENT_ID",
            "INVOICE_ID",
            "PARTY_ID",
            "PARTY_NAME",
            "PROJECT_ID",
            "PROJECT_NAME",
            "IDENTIFIER",
            "TAX_IDENTIFIER",
            "BANK_ACCOUNT",
            "CATEGORICAL_DIMENSION",
            "STATUS"
        ).contains(semanticType);
    }

    private static String inferDisplayType(String semanticType, String physicalType) {
        if ("DATE".equals(semanticType) || "DATETIME".equals(semanticType) || "DATE".equals(physicalType)) return "date";
        if (Set.of("DEBIT_AMOUNT", "CREDIT_AMOUNT", "SIGNED_AMOUNT", "CURRENCY", "PERCENTAGE", "QUANTITY").contains(semanticType)) return "number";
        return "text";
    }

    private static String inferInitialNullSemantics(String normalized, String semanticType, UniversalColumnDto column) {
        if (Set.of("ENTRY_ID", "DOCUMENT_ID", "INVOICE_ID", "PARTY_ID", "PROJECT_ID", "ACCOUNT_CODE", "DATE").contains(semanticType)) {
            return column.nullCount() == 0 ? "REQUIRED" : "MISSING";
        }
        if (BANK_RECONCILED_ALIASES.contains(normalized)) return "NOT_APPLICABLE";
        return "UNKNOWN";
    }

    private static double inferConfidence(String normalized,
                                          String semanticType,
                                          UniversalColumnDto column,
                                          Set<String> distinctValues) {
        if (aliasHit(normalized)) return 0.98d;
        if ("BOOLEAN".equals(semanticType) || "TRI_STATE_BOOLEAN".equals(semanticType)) return 0.92d;
        if ("IDENTIFIER".equals(semanticType) && column.uniqueCount() > 8) return 0.75d;
        if ("CURRENCY".equals(semanticType) && "number".equalsIgnoreCase(safe(column.detectedType()))) return 0.78d;
        if ("CATEGORICAL_DIMENSION".equals(semanticType) && distinctValues.size() <= 24) return 0.72d;
        return 0.55d;
    }

    private static List<String> inferAggregations(String semanticType, String normalized) {
        if ("ACCOUNT_CODE".equals(semanticType)) {
            return new ArrayList<>(List.of("ROW_COUNT", "DISTINCT_ENTRY_COUNT", "SUM_DEBIT", "SUM_CREDIT", "NET_BALANCE"));
        }
        if (Set.of("ENTRY_ID", "DOCUMENT_ID", "INVOICE_ID", "PARTY_ID", "PROJECT_ID", "IDENTIFIER").contains(semanticType)) {
            return new ArrayList<>(List.of("DISTINCT_COUNT", "ROW_COUNT"));
        }
        if (Set.of("DEBIT_AMOUNT", "CREDIT_AMOUNT", "SIGNED_AMOUNT", "CURRENCY").contains(semanticType)) {
            return new ArrayList<>(List.of("SUM_VALUE", "AVG_VALUE", "MEDIAN", "TIME_SERIES"));
        }
        if ("DATE".equals(semanticType)) {
            return new ArrayList<>(List.of("ROW_COUNT", "TIME_SERIES"));
        }
        if (Set.of("BOOLEAN", "TRI_STATE_BOOLEAN", "STATUS", "CATEGORICAL_DIMENSION", "PARTY_NAME", "PROJECT_NAME", "ACCOUNT_NAME").contains(semanticType)) {
            return new ArrayList<>(List.of("ROW_COUNT", "DISTINCT_COUNT", "SHARE"));
        }
        if ("PERCENTAGE".equals(semanticType)) {
            return new ArrayList<>(List.of("AVG_VALUE", "MEDIAN", "TIME_SERIES"));
        }
        if (normalized.contains("texto") || normalized.contains("description")) {
            return new ArrayList<>(List.of("TABLE_ONLY"));
        }
        return new ArrayList<>(List.of("ROW_COUNT"));
    }

    private static List<String> inferCharts(String semanticType, String analyticalType) {
        if ("TEMPORAL".equals(analyticalType)) return List.of("TIME_SERIES");
        if ("MEASURE".equals(analyticalType)) return List.of("KPI_CARDS", "TIME_SERIES", "HISTOGRAM");
        if ("CATEGORICAL".equals(analyticalType) || "IDENTIFIER".equals(analyticalType) || "STATUS".equals(analyticalType)) {
            return List.of("CATEGORY_BAR", "PIVOT_MONTHLY");
        }
        return List.of("TABLE");
    }

    private static ColumnProfileBuilder findBySemantic(Iterable<ColumnProfileBuilder> columns, String semanticType) {
        for (ColumnProfileBuilder column : columns) {
            if (semanticType.equals(column.semanticType)) return column;
        }
        return null;
    }

    private static List<String> sampleValues(List<Map<String, String>> rows, String column, int max) {
        if (rows == null || rows.isEmpty() || column == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String value = clean(row.get(column));
            if (value == null) continue;
            out.add(value);
            if (out.size() >= max) break;
        }
        return out;
    }

    private static boolean isBooleanLike(Set<String> distinctValues) {
        if (distinctValues.isEmpty() || distinctValues.size() > 3) return false;
        for (String value : distinctValues) {
            if (!TRUE_VALUES.contains(value) && !FALSE_VALUES.contains(value)) return false;
        }
        return true;
    }

    private static boolean aliasHit(String normalized) {
        return StreamAliases.ALL.contains(normalized);
    }

    private static String relationType(String sourceSemanticType, String targetSemanticType, String targetName) {
        String normalizedTarget = normalize(targetName);
        if (Set.of("ACCOUNT_CODE", "PARTY_ID", "PROJECT_ID", "IDENTIFIER").contains(sourceSemanticType)) {
            if (targetSemanticType.endsWith("_NAME") || normalizedTarget.contains("nombre") || normalizedTarget.contains("name")) return "LABEL";
            if ("TAX_IDENTIFIER".equals(targetSemanticType)) return "TAX_ID";
        }
        if (Set.of("ENTRY_ID", "DOCUMENT_ID", "INVOICE_ID").contains(sourceSemanticType) && "DATE".equals(targetSemanticType)) return "DATE_OF";
        return "FUNCTIONAL_DEPENDENCY";
    }

    private static String relationDetail(String sourceName,
                                         String targetName,
                                         String relationType,
                                         double confidence,
                                         int keys) {
        String prefix = switch (relationType) {
            case "LABEL" -> "Etiqueta estable";
            case "TAX_ID" -> "Identificador fiscal estable";
            case "DATE_OF" -> "Fecha estable por entidad";
            default -> "Dependencia funcional casi exacta";
        };
        return prefix + " entre " + sourceName + " y " + targetName + " (" + keys + " claves, conf. " + Math.round(confidence * 100) + "%).";
    }

    private static boolean isRepeatedByKey(List<Map<String, String>> rows, String keyColumn, String valueColumn) {
        Map<String, Set<String>> valuesByKey = new HashMap<>();
        Map<String, Integer> rowCountByKey = new HashMap<>();
        for (Map<String, String> row : rows) {
            String key = clean(row.get(keyColumn));
            String value = clean(row.get(valueColumn));
            if (key == null || value == null) continue;
            rowCountByKey.merge(key, 1, Integer::sum);
            valuesByKey.computeIfAbsent(key, ignored -> new HashSet<>()).add(value);
        }
        long repeated = rowCountByKey.entrySet().stream()
            .filter(e -> e.getValue() > 1)
            .filter(e -> valuesByKey.getOrDefault(e.getKey(), Set.of()).size() == 1)
            .count();
        return repeated >= 3;
    }

    private static BigDecimal sumColumn(List<Map<String, String>> rows, String column) {
        BigDecimal total = BigDecimal.ZERO;
        boolean any = false;
        for (Map<String, String> row : rows) {
            BigDecimal value = parseDecimal(clean(row.get(column)));
            if (value == null) continue;
            total = total.add(value);
            any = true;
        }
        return any ? total : null;
    }

    private static BigDecimal parseDecimal(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replace(" ", "");
        int comma = cleaned.lastIndexOf(',');
        int dot = cleaned.lastIndexOf('.');
        if (comma > dot) {
            cleaned = cleaned.replace(".", "").replace(",", ".");
        } else {
            cleaned = cleaned.replace(",", "");
        }
        try {
            return new BigDecimal(cleaned);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String clean(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        return value.isEmpty() || "-".equals(value) ? null : value;
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
        return normalized;
    }

    private static String normalizeValue(String raw) {
        if (raw == null) return "";
        return normalize(raw).replace("_", "");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

    @SafeVarargs
    private static <T> List<T> ensure(List<T> base, T... values) {
        LinkedHashSet<T> out = new LinkedHashSet<>(base == null ? List.of() : base);
        out.addAll(Arrays.asList(values));
        return new ArrayList<>(out);
    }

    private static Set<String> setOf(String... values) {
        return Arrays.stream(values).map(UniversalSemanticProfiler::normalize).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public record Profile(
        List<UniversalColumnDto> columns,
        String rowGranularity,
        List<UniversalDetectedEntityDto> detectedEntities,
        List<UniversalRelationshipDto> relationships,
        List<String> semanticWarnings
    ) {}

    private static final class ColumnProfileBuilder {
        private final UniversalColumnDto base;
        private final String name;
        private String detectedType;
        private String physicalType;
        private String semanticType;
        private String analyticalType;
        private String nullSemantics;
        private double confidence;
        private List<String> validAggregations;
        private List<String> recommendedCharts;
        private final List<String> relatedColumns;
        private final List<String> warnings;

        private ColumnProfileBuilder(UniversalColumnDto base,
                                     String detectedType,
                                     String physicalType,
                                     String semanticType,
                                     String analyticalType,
                                     String nullSemantics,
                                     double confidence,
                                     List<String> validAggregations,
                                     List<String> recommendedCharts,
                                     List<String> relatedColumns,
                                     List<String> warnings) {
            this.base = base;
            this.name = base.name();
            this.detectedType = detectedType;
            this.physicalType = physicalType;
            this.semanticType = semanticType;
            this.analyticalType = analyticalType;
            this.nullSemantics = nullSemantics;
            this.confidence = confidence;
            this.validAggregations = validAggregations;
            this.recommendedCharts = recommendedCharts;
            this.relatedColumns = relatedColumns;
            this.warnings = warnings;
        }

        private UniversalColumnDto build() {
            return new UniversalColumnDto(
                base.name(),
                detectedType,
                physicalType,
                semanticType,
                analyticalType,
                nullSemantics,
                confidence,
                base.totalCount(),
                base.nullCount(),
                base.uniqueCount(),
                "number".equals(detectedType) ? base.min() : null,
                "number".equals(detectedType) ? base.max() : null,
                "number".equals(detectedType) ? base.mean() : null,
                "number".equals(detectedType) ? base.median() : null,
                "number".equals(detectedType) ? base.p90() : null,
                "date".equals(detectedType) ? base.dateMin() : null,
                "date".equals(detectedType) ? base.dateMax() : null,
                relatedDedup(validAggregations),
                relatedDedup(recommendedCharts),
                relatedDedup(relatedColumns),
                relatedDedup(warnings),
                base.topValues(),
                "number".equals(detectedType) ? safeList(base.histogram()) : List.of(),
                "date".equals(detectedType) ? safeList(base.dateSeries()) : List.of()
            );
        }

        private static <T> List<T> safeList(List<T> values) {
            return values == null ? List.of() : values;
        }

        private static List<String> relatedDedup(List<String> values) {
            if (values == null || values.isEmpty()) return List.of();
            return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .distinct()
                .toList();
        }
    }

    private static final class StreamAliases {
        private static final Set<String> ALL;
        static {
            Set<String> values = new LinkedHashSet<>();
            values.addAll(ACCOUNT_CODE_ALIASES);
            values.addAll(ACCOUNT_NAME_ALIASES);
            values.addAll(DEBIT_ALIASES);
            values.addAll(CREDIT_ALIASES);
            values.addAll(SIGNED_AMOUNT_ALIASES);
            values.addAll(INVOICE_ID_ALIASES);
            values.addAll(DOCUMENT_ID_ALIASES);
            values.addAll(ENTRY_ID_ALIASES);
            values.addAll(PARTY_ID_ALIASES);
            values.addAll(PARTY_NAME_ALIASES);
            values.addAll(PARTY_TAX_ID_ALIASES);
            values.addAll(PROJECT_ID_ALIASES);
            values.addAll(PROJECT_NAME_ALIASES);
            values.addAll(DATE_ISSUE_ALIASES);
            values.addAll(DATE_DUE_ALIASES);
            values.addAll(DATE_POSTING_ALIASES);
            values.addAll(TAXABLE_BASE_ALIASES);
            values.addAll(VAT_AMOUNT_ALIASES);
            values.addAll(DOCUMENT_TOTAL_ALIASES);
            values.addAll(BANK_RECONCILED_ALIASES);
            values.addAll(COST_CENTER_ALIASES);
            values.addAll(DEPARTMENT_ALIASES);
            values.addAll(CITY_ALIASES);
            values.addAll(PRIMARY_DOC_LINE_ALIASES);
            values.addAll(DOCUMENT_TYPE_ALIASES);
            values.addAll(OPERATION_TYPE_ALIASES);
            ALL = Collections.unmodifiableSet(values);
        }
    }
}

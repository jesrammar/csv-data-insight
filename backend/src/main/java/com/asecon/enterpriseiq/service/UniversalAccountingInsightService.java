package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.UniversalColumnDto;
import com.asecon.enterpriseiq.dto.UniversalDetectedEntityDto;
import com.asecon.enterpriseiq.dto.UniversalInsightDto;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

final class UniversalAccountingInsightService {
    private UniversalAccountingInsightService() {
    }

    static List<UniversalInsightDto> buildInsights(
        List<UniversalColumnDto> columns,
        String rowGranularity,
        List<UniversalDetectedEntityDto> detectedEntities,
        byte[] bytes,
        Charset charset,
        char delimiter
    ) {
        AccountingSchema schema = AccountingSchema.detect(columns, rowGranularity);
        if (!schema.accountingLike()) {
            return List.of();
        }

        AccountingSnapshot snapshot = parseSnapshot(schema, bytes, charset, delimiter);
        if (!snapshot.hasSignal()) {
            return List.of();
        }

        List<UniversalInsightDto> insights = new ArrayList<>();
        UniversalDetectedEntityDto invoiceEntity = findEntity(detectedEntities, "INVOICE");

        if (snapshot.revenueTotal > 0d && snapshot.revenueByParty.size() >= 2) {
            List<Map.Entry<String, Double>> topCustomers = topEntries(snapshot.revenueByParty, 3);
            double topShare = shareOf(topCustomers, snapshot.revenueTotal);
            String topLabels = topCustomers.stream()
                .map(entry -> entry.getKey() + " (" + formatPercent(entry.getValue() / snapshot.revenueTotal) + ")")
                .collect(Collectors.joining(", "));
            String invoiceText = invoiceEntity == null
                ? ""
                : " En este corte se han detectado " + invoiceEntity.distinctCount() + " facturas distintas.";
            insights.add(new UniversalInsightDto(
                topShare >= 0.45d ? "warning" : "advisor",
                "Concentración comercial",
                "Los " + topCustomers.size() + " principales clientes concentran " + formatPercent(topShare)
                    + " del ingreso detectado. " + topLabels + "." + invoiceText
            ));
        }

        if (!snapshot.monthlyRevenue.isEmpty() || !snapshot.monthlyExpense.isEmpty()) {
            List<YearMonth> months = snapshot.allMonths();
            if (!months.isEmpty()) {
                YearMonth bestMonth = null;
                double bestNet = Double.NEGATIVE_INFINITY;
                YearMonth worstMonth = null;
                double worstNet = Double.POSITIVE_INFINITY;
                int negativeMonths = 0;
                for (YearMonth month : months) {
                    double net = snapshot.monthlyRevenue.getOrDefault(month, 0d) - snapshot.monthlyExpense.getOrDefault(month, 0d);
                    if (net < 0d) {
                        negativeMonths++;
                    }
                    if (net > bestNet) {
                        bestNet = net;
                        bestMonth = month;
                    }
                    if (net < worstNet) {
                        worstNet = net;
                        worstMonth = month;
                    }
                }
                if (bestMonth != null && worstMonth != null) {
                    insights.add(new UniversalInsightDto(
                        negativeMonths >= Math.max(2, months.size() / 3) ? "warning" : "advisor",
                        "Pulso mensual",
                        "La lectura operativa deja " + negativeMonths + " mes(es) en negativo sobre " + months.size()
                            + ". Mejor mes: " + formatMonth(bestMonth) + " (" + formatCurrency(bestNet) + ")"
                            + ". Peor mes: " + formatMonth(worstMonth) + " (" + formatCurrency(worstNet) + ")."
                    ));
                }
            }
        }

        if (snapshot.expenseTotal > 0d && !snapshot.expenseByAccount.isEmpty()) {
            List<Map.Entry<String, Double>> topExpenses = topEntries(snapshot.expenseByAccount, 3);
            Map.Entry<String, Double> first = topExpenses.get(0);
            double dominantShare = first.getValue() / snapshot.expenseTotal;
            String topText = topExpenses.stream()
                .map(entry -> entry.getKey() + " (" + formatPercent(entry.getValue() / snapshot.expenseTotal) + ")")
                .collect(Collectors.joining(", "));
            insights.add(new UniversalInsightDto(
                dominantShare >= 0.35d ? "advisor" : "info",
                "Estructura de gasto",
                "El gasto se concentra sobre todo en " + topText
                    + ". Esto ayuda a separar coste fijo, compras y partidas a vigilar en el cierre mensual."
            ));
        }

        if (snapshot.bankMovementRows > 0 && (snapshot.documentRows > 0 || snapshot.revenueTotal > 0d || snapshot.expenseTotal > 0d)) {
            insights.add(new UniversalInsightDto(
                "advisor",
                "Devengo y tesorería",
                "Aquí conviven facturas/asientos y movimientos de banco. Úsalo para separar lectura operativa, cobro real y conciliación antes de sacar conclusiones de caja."
            ));
        }

        return insights;
    }

    private static AccountingSnapshot parseSnapshot(
        AccountingSchema schema,
        byte[] bytes,
        Charset charset,
        char delimiter
    ) {
        AccountingSnapshot snapshot = new AccountingSnapshot();
        try {
            CSVParser parser = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setAllowMissingColumnNames(true)
                .setIgnoreEmptyLines(true)
                .build()
                .parse(new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), charset)));

            for (CSVRecord record : parser) {
                String accountCode = clean(value(record, schema.accountCodeColumn));
                String accountName = clean(value(record, schema.accountNameColumn));
                String party = Optional.ofNullable(clean(value(record, schema.partyNameColumn)))
                    .orElse(clean(value(record, schema.partyIdColumn)));
                LocalDate postingDate = parseDate(clean(value(record, schema.postingDateColumn)));
                Double debit = parseNumber(clean(value(record, schema.debitColumn)));
                Double credit = parseNumber(clean(value(record, schema.creditColumn)));
                Double signedAmount = parseNumber(clean(value(record, schema.signedAmountColumn)));
                String documentType = clean(value(record, schema.documentTypeColumn));
                String operationType = clean(value(record, schema.operationTypeColumn));

                double revenueAmount = revenueAmount(accountCode, accountName, credit, debit, signedAmount);
                if (revenueAmount > 0d) {
                    snapshot.revenueTotal += revenueAmount;
                    if (party != null) {
                        snapshot.revenueByParty.merge(party, revenueAmount, Double::sum);
                    }
                    if (postingDate != null) {
                        snapshot.monthlyRevenue.merge(YearMonth.from(postingDate), revenueAmount, Double::sum);
                    }
                }

                double expenseAmount = expenseAmount(accountCode, accountName, debit, credit, signedAmount);
                if (expenseAmount > 0d) {
                    snapshot.expenseTotal += expenseAmount;
                    snapshot.expenseByAccount.merge(accountLabel(accountCode, accountName), expenseAmount, Double::sum);
                    if (postingDate != null) {
                        snapshot.monthlyExpense.merge(YearMonth.from(postingDate), expenseAmount, Double::sum);
                    }
                }

                if (isBankAccount(accountCode, accountName)) {
                    double movement = Math.abs(signedAmount != null ? signedAmount : (coalesce(debit) - coalesce(credit)));
                    if (movement > 0d) {
                        snapshot.bankMovementRows++;
                    }
                }

                if (looksLikeOperationalDocument(documentType, operationType)) {
                    snapshot.documentRows++;
                }
            }
        } catch (Exception ignored) {
            return snapshot;
        }
        return snapshot;
    }

    private static UniversalDetectedEntityDto findEntity(List<UniversalDetectedEntityDto> entities, String entityType) {
        if (entities == null || entityType == null) {
            return null;
        }
        return entities.stream()
            .filter(Objects::nonNull)
            .filter(entity -> entityType.equalsIgnoreCase(clean(entity.entityType())))
            .findFirst()
            .orElse(null);
    }

    private static List<Map.Entry<String, Double>> topEntries(Map<String, Double> values, int limit) {
        return values.entrySet().stream()
            .filter(entry -> entry.getValue() != null && entry.getValue() > 0d)
            .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
            .limit(limit)
            .toList();
    }

    private static double shareOf(List<Map.Entry<String, Double>> entries, double total) {
        if (entries == null || entries.isEmpty() || total <= 0d) {
            return 0d;
        }
        return entries.stream().mapToDouble(Map.Entry::getValue).sum() / total;
    }

    private static double revenueAmount(String accountCode, String accountName, Double credit, Double debit, Double signedAmount) {
        if (!isRevenueAccount(accountCode, accountName)) {
            return 0d;
        }
        if (credit != null && credit > 0d) {
            return credit;
        }
        if (signedAmount != null && signedAmount > 0d) {
            return signedAmount;
        }
        return Math.max(0d, coalesce(credit) - coalesce(debit));
    }

    private static double expenseAmount(String accountCode, String accountName, Double debit, Double credit, Double signedAmount) {
        if (!isExpenseAccount(accountCode, accountName)) {
            return 0d;
        }
        if (debit != null && debit > 0d) {
            return debit;
        }
        if (signedAmount != null && signedAmount < 0d) {
            return Math.abs(signedAmount);
        }
        return Math.max(0d, coalesce(debit) - coalesce(credit));
    }

    private static boolean isRevenueAccount(String accountCode, String accountName) {
        return startsWith(accountCode, "7")
            || containsAny(accountName, "venta", "ventas", "ingreso", "ingresos", "prestacion", "prestación");
    }

    private static boolean isExpenseAccount(String accountCode, String accountName) {
        return startsWith(accountCode, "6")
            || containsAny(accountName, "compra", "compras", "gasto", "gastos", "sueldo", "salario", "alquiler", "servicio");
    }

    private static boolean isBankAccount(String accountCode, String accountName) {
        return startsWith(accountCode, "57")
            || containsAny(accountName, "banco", "bancos", "caja");
    }

    private static boolean looksLikeOperationalDocument(String documentType, String operationType) {
        return containsAny(documentType, "factura", "pago", "cobro", "asiento")
            || containsAny(operationType, "emision", "emisión", "registro", "tesoreria", "tesorería");
    }

    private static boolean startsWith(String value, String prefix) {
        return value != null && prefix != null && value.trim().startsWith(prefix);
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null || needles == null) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (needle != null && normalized.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String accountLabel(String accountCode, String accountName) {
        if (accountName != null && accountCode != null) {
            return accountCode + " · " + accountName;
        }
        return accountName != null ? accountName : Optional.ofNullable(accountCode).orElse("Cuenta sin clasificar");
    }

    private static String value(CSVRecord record, String column) {
        if (record == null || column == null || !record.isMapped(column)) {
            return null;
        }
        return record.get(column);
    }

    private static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        return value.isEmpty() || "-".equals(value) ? null : value;
    }

    private static Double parseNumber(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replace(" ", "");
        int comma = cleaned.lastIndexOf(',');
        int dot = cleaned.lastIndexOf('.');
        if (comma > dot) {
            cleaned = cleaned.replace(".", "").replace(",", ".");
        } else {
            cleaned = cleaned.replace(",", "");
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null) {
            return null;
        }
        List<DateTimeFormatter> formats = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM"),
            DateTimeFormatter.ofPattern("dd-MM-yy")
        );
        for (DateTimeFormatter format : formats) {
            try {
                return LocalDate.parse(raw, format);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private static double coalesce(Double value) {
        return value == null ? 0d : value;
    }

    private static String formatCurrency(double amount) {
        return String.format(Locale.ROOT, "%,.0f €", amount);
    }

    private static String formatPercent(double ratio) {
        return String.format(Locale.ROOT, "%.0f%%", ratio * 100d);
    }

    private static String formatMonth(YearMonth month) {
        return month == null ? "n/d" : month.toString();
    }

    private record AccountingSchema(
        String accountCodeColumn,
        String accountNameColumn,
        String partyIdColumn,
        String partyNameColumn,
        String postingDateColumn,
        String debitColumn,
        String creditColumn,
        String signedAmountColumn,
        String documentTypeColumn,
        String operationTypeColumn
    ) {
        boolean accountingLike() {
            return accountCodeColumn != null
                && (debitColumn != null || creditColumn != null || signedAmountColumn != null);
        }

        static AccountingSchema detect(List<UniversalColumnDto> columns, String rowGranularity) {
            if (columns == null || columns.isEmpty()) {
                return new AccountingSchema(null, null, null, null, null, null, null, null, null, null);
            }
            boolean accountingGranularity = "ACCOUNTING_ENTRY_LINE".equalsIgnoreCase(rowGranularity)
                || "ACCOUNTING_ENTRY".equalsIgnoreCase(rowGranularity);
            String accountCodeColumn = semantic(columns, "ACCOUNT_CODE");
            String debitColumn = semantic(columns, "DEBIT_AMOUNT");
            String creditColumn = semantic(columns, "CREDIT_AMOUNT");
            String signedAmountColumn = semantic(columns, "SIGNED_AMOUNT");
            if (!accountingGranularity && accountCodeColumn == null) {
                return new AccountingSchema(null, null, null, null, null, null, null, null, null, null);
            }
            return new AccountingSchema(
                accountCodeColumn,
                semantic(columns, "ACCOUNT_NAME"),
                semantic(columns, "PARTY_ID"),
                semantic(columns, "PARTY_NAME"),
                firstPresent(semantic(columns, "POSTING_DATE"), semantic(columns, "ISSUE_DATE"), semantic(columns, "DATE")),
                debitColumn,
                creditColumn,
                signedAmountColumn,
                semantic(columns, "DOCUMENT_TYPE"),
                semantic(columns, "OPERATION_TYPE")
            );
        }
    }

    private static String semantic(List<UniversalColumnDto> columns, String semanticType) {
        return columns.stream()
            .filter(Objects::nonNull)
            .filter(column -> semanticType.equalsIgnoreCase(clean(column.semanticType())))
            .map(UniversalColumnDto::name)
            .findFirst()
            .orElse(null);
    }

    private static String firstPresent(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static final class AccountingSnapshot {
        private final Map<String, Double> revenueByParty = new LinkedHashMap<>();
        private final Map<String, Double> expenseByAccount = new LinkedHashMap<>();
        private final Map<YearMonth, Double> monthlyRevenue = new LinkedHashMap<>();
        private final Map<YearMonth, Double> monthlyExpense = new LinkedHashMap<>();
        private double revenueTotal;
        private double expenseTotal;
        private int bankMovementRows;
        private int documentRows;

        boolean hasSignal() {
            return revenueTotal > 0d || expenseTotal > 0d || bankMovementRows > 0;
        }

        List<YearMonth> allMonths() {
            return java.util.stream.Stream.concat(monthlyRevenue.keySet().stream(), monthlyExpense.keySet().stream())
                .distinct()
                .sorted()
                .toList();
        }
    }
}

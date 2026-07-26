package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.UniversalColumnDto;
import com.asecon.enterpriseiq.dto.UniversalDetectedEntityDto;
import com.asecon.enterpriseiq.dto.UniversalRelationshipDto;
import com.asecon.enterpriseiq.dto.UniversalSummaryDto;
import com.asecon.enterpriseiq.metrics.ErrorTagger;
import com.asecon.enterpriseiq.model.Plan;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UniversalCsvServiceSemanticTest {
    @Test
    void profiles_accounting_dataset_semantically() throws Exception {
        UniversalCsvService service = new UniversalCsvService(
            null,
            null,
            null,
            null,
            null,
            50_000,
            15_000,
            50_000,
            100_000,
            25,
            15,
            25,
            45,
            new SimpleMeterRegistry(),
            new ErrorTagger(40, "other")
        );

        UniversalSummaryDto summary = service.analyzePreview(
            "dataset_contable_consultoria_ficticia_2025.csv",
            buildAccountingDataset().getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8,
            Plan.PLATINUM,
            Instant.parse("2026-07-20T10:00:00Z")
        );

        assertThat(summary.rowCount()).isEqualTo(1132);
        assertThat(summary.rowGranularity()).isEqualTo("ACCOUNTING_ENTRY_LINE");

        UniversalDetectedEntityDto invoiceEntity = entity(summary, "INVOICE");
        UniversalDetectedEntityDto entryEntity = entity(summary, "ENTRY");
        UniversalDetectedEntityDto accountEntity = entity(summary, "ACCOUNT");
        assertThat(invoiceEntity).isNotNull();
        assertThat(invoiceEntity.distinctCount()).isEqualTo(187);
        assertThat(entryEntity).isNotNull();
        assertThat(entryEntity.distinctCount()).isEqualTo(437);
        assertThat(accountEntity).isNotNull();
        assertThat(accountEntity.distinctCount()).isEqualTo(32);

        UniversalColumnDto accountCode = column(summary, "cuenta_contable");
        assertThat(accountCode).isNotNull();
        assertThat(accountCode.detectedType()).isEqualTo("text");
        assertThat(accountCode.physicalType()).isEqualTo("STRING");
        assertThat(accountCode.semanticType()).isEqualTo("ACCOUNT_CODE");
        assertThat(accountCode.analyticalType()).isEqualTo("CATEGORICAL");
        assertThat(accountCode.uniqueCount()).isEqualTo(32);
        assertThat(accountCode.mean()).isNull();
        assertThat(accountCode.median()).isNull();
        assertThat(accountCode.p90()).isNull();
        assertThat(accountCode.validAggregations()).contains("SUM_DEBIT", "SUM_CREDIT", "NET_BALANCE");
        assertThat(accountCode.relatedColumns()).contains("cuenta_nombre");

        UniversalColumnDto issueDate = column(summary, "fecha_emision");
        assertThat(issueDate.validAggregations()).contains("DISTINCT_INVOICE_COUNT");
        assertThat(issueDate.warnings()).anyMatch(text -> text.contains("facturas distintas"));

        UniversalColumnDto documentTotal = column(summary, "total_documento_eur");
        assertThat(documentTotal.validAggregations()).contains("SUM_DISTINCT_VALUE");
        assertThat(documentTotal.warnings()).anyMatch(text -> text.contains("deduplicar"));

        UniversalColumnDto taxableBase = column(summary, "base_imponible_eur");
        assertThat(taxableBase.nullCount()).isEqualTo(945);
        assertThat(taxableBase.nullSemantics()).isEqualTo("STRUCTURAL");

        UniversalColumnDto reconciled = column(summary, "conciliado_banco");
        assertThat(reconciled.semanticType()).isEqualTo("TRI_STATE_BOOLEAN");
        assertThat(reconciled.nullSemantics()).isEqualTo("NOT_APPLICABLE");

        assertThat(summary.relationships())
            .extracting(UniversalRelationshipDto::sourceColumn, UniversalRelationshipDto::targetColumn)
            .contains(org.assertj.core.groups.Tuple.tuple("cuenta_contable", "cuenta_nombre"));

        assertThat(summary.semanticWarnings()).anyMatch(text -> text.contains("línea contable"));
        assertThat(summary.semanticWarnings()).anyMatch(text -> text.contains("Debe y haber"));
        assertThat(summary.insights()).anyMatch(insight -> insight.message().contains("facturas distintas"));
        assertThat(summary.insights()).anyMatch(insight -> insight.title().contains("Concentración comercial"));
        assertThat(summary.insights()).anyMatch(insight -> insight.title().contains("Pulso mensual"));
        assertThat(summary.insights()).anyMatch(insight -> insight.title().contains("Estructura de gasto"));
        assertThat(summary.insights()).anyMatch(insight -> insight.title().contains("Devengo y tesorería"));
    }

    private static UniversalDetectedEntityDto entity(UniversalSummaryDto summary, String entityType) {
        return summary.detectedEntities().stream()
            .filter(entity -> entityType.equalsIgnoreCase(entity.entityType()))
            .findFirst()
            .orElse(null);
    }

    private static UniversalColumnDto column(UniversalSummaryDto summary, String name) {
        return summary.columns().stream()
            .filter(column -> name.equalsIgnoreCase(column.name()))
            .findFirst()
            .orElse(null);
    }

    static String buildAccountingDataset() {
        Map<String, String> accountNames = new LinkedHashMap<>();
        accountNames.put("0100", "Capital social");
        accountNames.put("0213", "Maquinaria");
        accountNames.put("0216", "Mobiliario");
        accountNames.put("0400", "Proveedores");
        accountNames.put("0430", "Clientes");
        accountNames.put("0472", "HP IVA soportado");
        accountNames.put("0475", "HP acreedora");
        accountNames.put("0476", "Organismos Seguridad Social");
        accountNames.put("0477", "HP IVA repercutido");
        accountNames.put("0478", "HP retenciones");
        accountNames.put("0555", "Partidas pendientes");
        accountNames.put("0560", "Fianzas");
        accountNames.put("0570", "Caja");
        accountNames.put("0571", "Caja moneda extranjera");
        accountNames.put("0572", "Bancos");
        accountNames.put("0600", "Compras mercaderias");
        accountNames.put("0620", "Arrendamientos");
        accountNames.put("0621", "Reparaciones");
        accountNames.put("0623", "Servicios profesionales");
        accountNames.put("0626", "Servicios bancarios");
        accountNames.put("0628", "Suministros");
        accountNames.put("0629", "Otros servicios");
        accountNames.put("0631", "Otros tributos");
        accountNames.put("0632", "IVA no deducible");
        accountNames.put("0640", "Sueldos y salarios");
        accountNames.put("0642", "Seguridad social a cargo empresa");
        accountNames.put("0659", "Otras pérdidas");
        accountNames.put("0681", "Amortización inmovilizado");
        accountNames.put("0682", "Amortización inversiones");
        accountNames.put("0700", "Ventas");
        accountNames.put("0705", "Prestación de servicios");
        accountNames.put("0759", "Ingresos varios");

        String[] costCenters = {"CC-GEN", "CC-DEV", "CC-ERP", "CC-RRHH"};
        String[] departments = {"Administracion", "Operaciones", "Consultoria"};
        String[] cities = {"Sevilla", "Madrid", "Valencia", "Malaga"};
        StringBuilder csv = new StringBuilder();
        csv.append(String.join(",",
            "asiento_id",
            "documento_id",
            "factura_id",
            "fecha_contable",
            "fecha_emision",
            "fecha_vencimiento",
            "tipo_documento",
            "tipo_operacion",
            "es_linea_documento_principal",
            "cuenta_contable",
            "cuenta_nombre",
            "tercero_id",
            "tercero_nombre",
            "tercero_nif",
            "proyecto_id",
            "proyecto_nombre",
            "ciudad_tercero",
            "centro_coste",
            "departamento",
            "debe",
            "haber",
            "base_imponible_eur",
            "iva_eur",
            "total_documento_eur",
            "conciliado_banco"
        )).append('\n');

        int entryNumber = 1;
        for (int invoiceIndex = 1; invoiceIndex <= 187; invoiceIndex++) {
            boolean sale = invoiceIndex <= 76;
            String entryId = entry(entryNumber++);
            String invoiceId = sale ? "FV" + pad(invoiceIndex) : "FC" + pad(invoiceIndex - 76);
            String documentId = "DOC" + pad(invoiceIndex);
            LocalDate issueDate = LocalDate.of(2025, ((invoiceIndex - 1) % 12) + 1, ((invoiceIndex - 1) % 26) + 1);
            LocalDate dueDate = issueDate.plusDays(30);
            String thirdId = sale ? "CLI" + pad((invoiceIndex % 48) + 1) : "PRO" + pad((invoiceIndex % 39) + 1);
            String thirdName = sale ? "Cliente " + ((invoiceIndex % 48) + 1) : "Proveedor " + ((invoiceIndex % 39) + 1);
            String thirdTax = sale ? "B" + pad(invoiceIndex) : "A" + pad(invoiceIndex);
            String projectId = "PRJ" + pad((invoiceIndex % 12) + 1);
            String projectName = "Proyecto " + ((invoiceIndex % 12) + 1);
            String city = cities[invoiceIndex % cities.length];
            String costCenter = costCenters[invoiceIndex % costCenters.length];
            String department = departments[invoiceIndex % departments.length];
            double base = 800 + (invoiceIndex % 7) * 45;
            double vat = round(base * 0.21d);
            double total = round(base + vat);

            if (sale) {
                appendRow(csv, entryId, documentId, invoiceId, issueDate, dueDate, "FACTURA_VENTA", "EMISION", "NO", "0430", accountNames.get("0430"), thirdId, thirdName, thirdTax, projectId, projectName, city, costCenter, department, total, 0, null, null, total, "NO");
                appendRow(csv, entryId, documentId, invoiceId, issueDate, dueDate, "FACTURA_VENTA", "EMISION", "SI", "0705", accountNames.get("0705"), thirdId, thirdName, thirdTax, projectId, projectName, city, costCenter, department, 0, base, base, null, total, "NO");
                appendRow(csv, entryId, documentId, invoiceId, issueDate, dueDate, "FACTURA_VENTA", "EMISION", "NO", "0477", accountNames.get("0477"), thirdId, thirdName, thirdTax, projectId, projectName, city, costCenter, department, 0, vat, null, vat, total, "NO");
            } else {
                appendRow(csv, entryId, documentId, invoiceId, issueDate, dueDate, "FACTURA_COMPRA", "REGISTRO", "SI", "0600", accountNames.get("0600"), thirdId, thirdName, thirdTax, projectId, projectName, city, costCenter, department, base, 0, base, null, total, "NO");
                appendRow(csv, entryId, documentId, invoiceId, issueDate, dueDate, "FACTURA_COMPRA", "REGISTRO", "NO", "0472", accountNames.get("0472"), thirdId, thirdName, thirdTax, projectId, projectName, city, costCenter, department, vat, 0, null, vat, total, "NO");
                appendRow(csv, entryId, documentId, invoiceId, issueDate, dueDate, "FACTURA_COMPRA", "REGISTRO", "NO", "0400", accountNames.get("0400"), thirdId, thirdName, thirdTax, projectId, projectName, city, costCenter, department, 0, total, null, null, total, "NO");
            }
        }

        List<String> extraAccounts = accountNames.keySet().stream()
            .filter(code -> !List.of("0430", "0400", "0472", "0477", "0600", "0705", "0572").contains(code))
            .toList();

        for (int paymentIndex = 1; paymentIndex <= 250; paymentIndex++) {
            String entryId = entry(entryNumber++);
            LocalDate date = LocalDate.of(2025, ((paymentIndex - 1) % 12) + 1, ((paymentIndex - 1) % 25) + 1);
            String documentId = "PMT" + pad(paymentIndex);
            String thirdId = "SUP" + pad((paymentIndex % 37) + 1);
            String thirdName = "Proveedor pago " + ((paymentIndex % 37) + 1);
            String thirdTax = "P" + pad(paymentIndex);
            String projectId = "PRJ" + pad((paymentIndex % 12) + 1);
            String projectName = "Proyecto " + ((paymentIndex % 12) + 1);
            String city = cities[paymentIndex % cities.length];
            String costCenter = costCenters[paymentIndex % costCenters.length];
            String department = departments[paymentIndex % departments.length];
            double bankOut = 300 + (paymentIndex % 9) * 20;
            double fee = paymentIndex <= 71 ? 10 : 0;
            double supplierDebit = round(bankOut - fee);
            String status = paymentIndex % 4 == 0 ? "SI" : "NO";
            appendRow(csv, entryId, documentId, "", date, date.plusDays(1), "PAGO", "TESORERIA", "NO", "0572", accountNames.get("0572"), thirdId, thirdName, thirdTax, projectId, projectName, city, costCenter, department, 0, bankOut, null, null, null, status);
            appendRow(csv, entryId, documentId, "", date, date.plusDays(1), "PAGO", "TESORERIA", "NO", "0400", accountNames.get("0400"), thirdId, thirdName, thirdTax, projectId, projectName, city, costCenter, department, supplierDebit, 0, null, null, null, "NO");
            if (paymentIndex <= 71) {
                String expenseCode = extraAccounts.get((paymentIndex - 1) % extraAccounts.size());
                appendRow(csv, entryId, documentId, "", date, date.plusDays(1), "PAGO", "TESORERIA", "NO", expenseCode, accountNames.get(expenseCode), thirdId, thirdName, thirdTax, projectId, projectName, city, costCenter, department, fee, 0, null, null, null, "NO");
            }
        }

        return csv.toString();
    }

    private static void appendRow(StringBuilder csv,
                                  String entryId,
                                  String documentId,
                                  String invoiceId,
                                  LocalDate postingDate,
                                  LocalDate dueDate,
                                  String documentType,
                                  String operationType,
                                  String primaryLine,
                                  String accountCode,
                                  String accountName,
                                  String thirdId,
                                  String thirdName,
                                  String thirdTax,
                                  String projectId,
                                  String projectName,
                                  String city,
                                  String costCenter,
                                  String department,
                                  double debit,
                                  double credit,
                                  Double taxBase,
                                  Double vat,
                                  Double documentTotal,
                                  String reconciled) {
        csv.append(String.join(",",
            entryId,
            documentId,
            invoiceId,
            postingDate.toString(),
            invoiceId.isBlank() ? "" : postingDate.toString(),
            invoiceId.isBlank() ? "" : dueDate.toString(),
            documentType,
            operationType,
            primaryLine,
            accountCode,
            accountName,
            thirdId,
            thirdName,
            thirdTax,
            projectId,
            projectName,
            city,
            costCenter,
            department,
            amount(debit),
            amount(credit),
            taxBase == null ? "" : amount(taxBase),
            vat == null ? "" : amount(vat),
            documentTotal == null ? "" : amount(documentTotal),
            reconciled
        )).append('\n');
    }

    private static String entry(int number) {
        return "ASI" + pad(number);
    }

    private static String pad(int value) {
        return String.format("%04d", value);
    }

    private static double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private static String amount(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}

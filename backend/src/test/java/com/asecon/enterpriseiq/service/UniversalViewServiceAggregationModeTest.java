package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.UniversalChartDataDto;
import com.asecon.enterpriseiq.dto.UniversalSummaryDto;
import com.asecon.enterpriseiq.dto.UniversalViewRequest;
import com.asecon.enterpriseiq.metrics.ErrorTagger;
import com.asecon.enterpriseiq.model.Plan;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UniversalViewServiceAggregationModeTest {

    @Test
    void previewBytes_kpiCards_distinctEntryCount_usesSemanticKey() throws Exception {
        String csv = UniversalCsvServiceSemanticTest.buildAccountingDataset();
        UniversalChartDataDto out = service().previewBytes(csv.getBytes(StandardCharsets.UTF_8), request("KPI_CARDS", null, null, "DISTINCT_ENTRY_COUNT"), summaryFor(csv));

        Map<String, BigDecimal> kpis = labeledSeries(out);
        assertThat(out.type()).isEqualTo("KPI_CARDS");
        assertThat(out.meta()).containsEntry("aggregationMode", "DISTINCT_ENTRY_COUNT");
        assertThat(kpis.get("asientos")).isEqualByComparingTo("437");
    }

    @Test
    void previewBytes_timeSeries_distinctInvoiceCount_worksWithoutValueColumn() throws Exception {
        String csv = UniversalCsvServiceSemanticTest.buildAccountingDataset();
        UniversalViewRequest req = request("TIME_SERIES", "fecha_emision", null, "DISTINCT_INVOICE_COUNT");

        UniversalChartDataDto out = service().previewBytes(csv.getBytes(StandardCharsets.UTF_8), req, summaryFor(csv));

        assertThat(out.type()).isEqualTo("TIME_SERIES");
        assertThat(out.meta()).containsEntry("aggregationMode", "DISTINCT_INVOICE_COUNT");
        assertThat(out.meta().get("valueColumn")).isNull();
        assertThat(sumFirstSeries(out)).isEqualByComparingTo("187");
    }

    @Test
    void previewBytes_categoryBar_rowCount_doesNotRequireValueColumn() throws Exception {
        String csv = UniversalCsvServiceSemanticTest.buildAccountingDataset();
        UniversalViewRequest req = request("CATEGORY_BAR", null, "tipo_documento", "ROW_COUNT");

        UniversalChartDataDto out = service().previewBytes(csv.getBytes(StandardCharsets.UTF_8), req, summaryFor(csv));

        assertThat(out.type()).isEqualTo("CATEGORY_BAR");
        assertThat(out.meta()).containsEntry("aggregationMode", "ROW_COUNT");
        assertThat(out.meta().get("valueColumn")).isNull();
        assertThat(sumFirstSeries(out)).isEqualByComparingTo("1132");
    }

    @Test
    void previewBytes_categoryBar_sumAmount_deduplicatesInvoiceTotals() throws Exception {
        String csv = UniversalCsvServiceSemanticTest.buildAccountingDataset();
        UniversalViewRequest req = request("CATEGORY_BAR", null, "tipo_documento", "SUM_AMOUNT");
        req.setValueColumn("total_documento_eur");

        UniversalChartDataDto out = service().previewBytes(csv.getBytes(StandardCharsets.UTF_8), req, summaryFor(csv));
        Map<String, BigDecimal> values = labeledSeries(out);
        Map<String, BigDecimal> expected = expectedInvoiceTotalsByType(csv);

        assertThat(out.meta()).containsEntry("aggregationMode", "SUM_AMOUNT");
        assertThat(out.meta()).containsEntry("dedupKeyColumn", "factura_id");
        assertThat(values.get("FACTURA_VENTA")).isEqualByComparingTo(expected.get("FACTURA_VENTA"));
        assertThat(values.get("FACTURA_COMPRA")).isEqualByComparingTo(expected.get("FACTURA_COMPRA"));
    }

    @Test
    void canonicalizeRequest_setsExplicitAggregationModeForLegacyRequest() throws Exception {
        String csv = UniversalCsvServiceSemanticTest.buildAccountingDataset();
        UniversalViewRequest req = new UniversalViewRequest();
        req.setType("TIME_SERIES");
        req.setDateColumn("fecha_emision");
        req.setAggregation("sum");

        UniversalViewRequest normalized = service().canonicalizeRequest(req, summaryFor(csv));

        assertThat(normalized.getAggregationMode()).isEqualTo("DISTINCT_INVOICE_COUNT");
        assertThat(normalized.getAggregation()).isEqualTo("sum");
        assertThat(normalized.getValueColumn()).isNull();
    }

    private static UniversalViewService service() {
        return new UniversalViewService(null, new ObjectMapper());
    }

    private static UniversalViewRequest request(String type, String dateColumn, String categoryColumn, String aggregationMode) {
        UniversalViewRequest req = new UniversalViewRequest();
        req.setType(type);
        req.setDateColumn(dateColumn);
        req.setCategoryColumn(categoryColumn);
        req.setAggregationMode(aggregationMode);
        req.setAggregation("AVG_VALUE".equals(aggregationMode) ? "avg" : "sum");
        return req;
    }

    private static UniversalSummaryDto summaryFor(String csv) throws Exception {
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
        return service.analyzePreview(
            "dataset_contable_consultoria_ficticia_2025.csv",
            csv.getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8,
            Plan.PLATINUM,
            Instant.parse("2026-07-20T10:00:00Z")
        );
    }

    private static BigDecimal sumFirstSeries(UniversalChartDataDto out) {
        if (out.series() == null || out.series().isEmpty()) return BigDecimal.ZERO;
        Object raw = out.series().get(0).get("data");
        if (!(raw instanceof List<?> data)) return BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (Object item : data) total = total.add(asBigDecimal(item));
        return total.stripTrailingZeros();
    }

    private static Map<String, BigDecimal> labeledSeries(UniversalChartDataDto out) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        if (out.labels() == null || out.series() == null || out.series().isEmpty()) return values;
        Object raw = out.series().get(0).get("data");
        if (!(raw instanceof List<?> data)) return values;
        for (int i = 0; i < Math.min(out.labels().size(), data.size()); i++) {
            values.put(out.labels().get(i), asBigDecimal(data.get(i)).stripTrailingZeros());
        }
        return values;
    }

    private static BigDecimal asBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
        return new BigDecimal(String.valueOf(value));
    }

    private static Map<String, BigDecimal> expectedInvoiceTotalsByType(String csv) throws Exception {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        Map<String, BigDecimal> seenInvoices = new LinkedHashMap<>();
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .build()
            .parse(new StringReader(csv))) {
            for (CSVRecord record : parser) {
                String invoiceId = record.get("factura_id");
                if (invoiceId == null || invoiceId.isBlank()) continue;
                if (seenInvoices.containsKey(invoiceId)) continue;
                BigDecimal total = new BigDecimal(record.get("total_documento_eur"));
                seenInvoices.put(invoiceId, total);
                String type = record.get("tipo_documento");
                totals.put(type, totals.getOrDefault(type, BigDecimal.ZERO).add(total));
            }
        }
        return totals;
    }
}

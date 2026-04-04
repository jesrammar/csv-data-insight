package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.UniversalChartDataDto;
import com.asecon.enterpriseiq.dto.UniversalViewRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

public class UniversalViewServiceBuilderTest {

    @Test
    void parseDecimal_handlesEuropeanAndUsFormatsAndCurrency() {
        assertEquals(new BigDecimal("1234.56"), UniversalViewService.parseDecimal("1.234,56"));
        assertEquals(new BigDecimal("1234.56"), UniversalViewService.parseDecimal("1,234.56"));
        assertEquals(new BigDecimal("1234.56"), UniversalViewService.parseDecimal("\u20AC 1.234,56"));
        assertEquals(new BigDecimal("-1234.56"), UniversalViewService.parseDecimal("(1.234,56)"));
        assertNull(UniversalViewService.parseDecimal("abc"));
    }

    @Test
    void parseYearMonth_handlesIsoAndSpanishDates() {
        assertEquals(YearMonth.of(2025, 4), UniversalViewService.parseYearMonth("2025-04"));
        assertEquals(YearMonth.of(2025, 4), UniversalViewService.parseYearMonth("2025-04-03"));
        assertEquals(YearMonth.of(2025, 4), UniversalViewService.parseYearMonth("03/04/2025"));
        assertEquals(YearMonth.of(2025, 4), UniversalViewService.parseYearMonth("3-4-2025"));
        assertNull(UniversalViewService.parseYearMonth("no-date"));
    }

    @Test
    void previewBytes_timeSeries_givesHelpfulErrorOnBadDates() {
        String csv = "fecha,importe\n" +
            "foo,10\n" +
            "bar,20\n";
        byte[] bytes = csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        UniversalViewRequest req = new UniversalViewRequest();
        req.setType("TIME_SERIES");
        req.setDateColumn("fecha");
        req.setValueColumn("importe");
        req.setAggregation("sum");

        UniversalViewService svc = new UniversalViewService(null, new ObjectMapper());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> svc.previewBytes(bytes, req));
        assertTrue(ex.getReason() != null && ex.getReason().toLowerCase().contains("no pude parsear fechas"), ex.getReason());
        assertTrue(ex.getReason().contains("fecha"), ex.getReason());
    }

    @Test
    void previewBytes_handlesIrregularCsvRows() {
        // second row is truncated (missing column)
        String csv = "fecha,importe\n" +
            "2025-01,10\n" +
            "2025-02\n";
        byte[] bytes = csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        UniversalViewRequest req = new UniversalViewRequest();
        req.setType("TIME_SERIES");
        req.setDateColumn("fecha");
        req.setValueColumn("importe");
        req.setAggregation("sum");

        UniversalViewService svc = new UniversalViewService(null, new ObjectMapper());
        UniversalChartDataDto out = svc.previewBytes(bytes, req);
        assertEquals("TIME_SERIES", out.type());
        assertFalse(out.labels().isEmpty());
        assertEquals(1, out.series().size());
    }
}

package com.asecon.enterpriseiq.service;

import java.io.ByteArrayOutputStream;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TabularFileServiceLimitsTest {

    @Test
    void toCsv_rejectsHugeXlsxByRowCount() throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        var sheet = wb.createSheet("Data");
        var header = sheet.createRow(0);
        header.createCell(0).setCellValue("col_a");
        header.createCell(1).setCellValue("col_b");
        for (int i = 1; i <= 25; i++) {
            var r = sheet.createRow(i);
            r.createCell(0).setCellValue("v" + i);
            r.createCell(1).setCellValue(i);
        }

        byte[] bytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            wb.write(baos);
            bytes = baos.toByteArray();
        } finally {
            wb.close();
        }

        MockMultipartFile file = new MockMultipartFile(
            "file",
            "data.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            bytes
        );

        TabularFileService svc = new TabularFileService(10, 25, new SimpleMeterRegistry());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> svc.toCsv(file, null));
        assertEquals(413, ex.getStatusCode().value());
    }
}

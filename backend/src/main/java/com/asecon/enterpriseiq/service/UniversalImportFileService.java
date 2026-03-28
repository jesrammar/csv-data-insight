package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.UniversalRowsDto;
import com.asecon.enterpriseiq.model.UniversalImport;
import com.asecon.enterpriseiq.repo.UniversalImportRepository;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UniversalImportFileService {
    private final UniversalImportRepository importRepository;
    private final UniversalStorageService universalStorageService;

    public UniversalImportFileService(UniversalImportRepository importRepository, UniversalStorageService universalStorageService) {
        this.importRepository = importRepository;
        this.universalStorageService = universalStorageService;
    }

    public Optional<UniversalImport> latest(Long companyId) {
        return importRepository.findFirstByCompanyIdOrderByCreatedAtDesc(companyId);
    }

    public byte[] latestNormalizedCsv(Long companyId) {
        UniversalImport imp = latest(companyId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No hay imports universales"));
        try {
            return universalStorageService.readBytes(imp.getStorageRef());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CSV normalizado no disponible");
        }
    }

    public UniversalRowsDto latestRows(Long companyId, int limit) {
        if (limit < 1) limit = 1;
        if (limit > 200) limit = 200;
        UniversalImport imp = latest(companyId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No hay imports universales"));

        byte[] bytes;
        try {
            bytes = universalStorageService.readBytes(imp.getStorageRef());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CSV normalizado no disponible");
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setAllowMissingColumnNames(true)
                .setIgnoreEmptyLines(true)
                .build()
                .parse(reader);

            List<String> headers = new ArrayList<>(parser.getHeaderMap().keySet());
            List<List<String>> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                List<String> row = new ArrayList<>(headers.size());
                for (String h : headers) {
                    row.add(record.isMapped(h) ? record.get(h) : "");
                }
                rows.add(row);
                if (rows.size() >= limit) break;
            }
            return new UniversalRowsDto(imp.getFilename(), headers, rows);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo leer el CSV normalizado");
        }
    }
}

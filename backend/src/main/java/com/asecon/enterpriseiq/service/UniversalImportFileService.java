package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.UniversalImportAnalysisDto;
import com.asecon.enterpriseiq.dto.UniversalRowsDto;
import com.asecon.enterpriseiq.model.UniversalImport;
import com.asecon.enterpriseiq.repo.UniversalImportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;

    public UniversalImportFileService(UniversalImportRepository importRepository,
                                      UniversalStorageService universalStorageService,
                                      ObjectMapper objectMapper) {
        this.importRepository = importRepository;
        this.universalStorageService = universalStorageService;
        this.objectMapper = objectMapper;
    }

    public Optional<UniversalImport> latest(Long companyId) {
        return importRepository.findFirstByCompanyIdOrderByCreatedAtDesc(companyId);
    }

    public List<UniversalImport> latestList(Long companyId, int limit) {
        if (limit <= 0) return List.of();
        List<UniversalImport> all = importRepository.findByCompanyIdOrderByCreatedAtDesc(companyId);
        if (all.size() <= limit) return all;
        return all.subList(0, limit);
    }

    public Optional<UniversalImport> latestAnnualBudget(Long companyId) {
        List<UniversalImport> list = latestAnnualBudgetList(companyId, 1);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<UniversalImport> latestAnnualBudgetList(Long companyId, int limit) {
        if (limit <= 0) return List.of();
        List<UniversalImport> all = importRepository.findByCompanyIdOrderByCreatedAtDesc(companyId);
        List<UniversalImport> matches = new ArrayList<>();
        for (UniversalImport imp : all) {
            if (imp == null) continue;
            if (!looksLikeAnnualBudget(imp)) continue;
            matches.add(imp);
            if (matches.size() >= limit) break;
        }
        return matches;
    }

    public Optional<UniversalImport> find(Long companyId, Long importId) {
        if (importId == null) return Optional.empty();
        return importRepository.findByIdAndCompanyId(importId, companyId);
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

    public byte[] normalizedCsv(Long companyId, Long importId) {
        if (importId == null) return latestNormalizedCsv(companyId);
        UniversalImport imp = importRepository.findByIdAndCompanyId(importId, companyId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Import universal no encontrado"));
        try {
            return universalStorageService.readBytes(imp.getStorageRef());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CSV normalizado no disponible");
        }
    }

    public UniversalRowsDto latestRows(Long companyId, int limit) {
        return rows(companyId, null, limit);
    }

    public UniversalRowsDto rows(Long companyId, Long importId, int limit) {
        if (limit < 1) limit = 1;
        if (limit > 200) limit = 200;

        UniversalImport imp = importId == null
            ? latest(companyId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No hay imports universales"))
            : importRepository.findByIdAndCompanyId(importId, companyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Import universal no encontrado"));

        byte[] bytes = normalizedCsv(companyId, imp.getId());

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

    private boolean looksLikeAnnualBudget(UniversalImport imp) {
        UniversalImportAnalysisDto analysis = parseAnalysis(imp);
        String diagnosisKind = analysis == null || analysis.intakeDiagnosis() == null ? null : analysis.intakeDiagnosis().kind();
        if ("ANNUAL_BUDGET".equalsIgnoreCase(String.valueOf(diagnosisKind))) return true;

        if (imp.getStorageRef() == null || imp.getStorageRef().isBlank()) return false;
        try {
            byte[] bytes = universalStorageService.readBytes(imp.getStorageRef());
            BudgetLongNormalizer.Result result = BudgetLongNormalizer.normalizeToLongCsv(bytes, 5_000, 0);
            return result.longCsvBytes() != null
                && result.longCsvBytes().length > 0
                && result.labelHeader() != null
                && result.monthKeys() != null
                && !result.monthKeys().isEmpty();
        } catch (Exception ex) {
            return false;
        }
    }

    private UniversalImportAnalysisDto parseAnalysis(UniversalImport imp) {
        if (imp == null || imp.getAnalysisJson() == null || imp.getAnalysisJson().isBlank()) return null;
        try {
            return objectMapper.readValue(imp.getAnalysisJson(), UniversalImportAnalysisDto.class);
        } catch (Exception ex) {
            return null;
        }
    }
}

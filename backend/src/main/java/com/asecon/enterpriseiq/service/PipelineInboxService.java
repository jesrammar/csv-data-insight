package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.PipelineFileDto;
import com.asecon.enterpriseiq.dto.PipelineScanResultDto;
import com.asecon.enterpriseiq.dto.PipelineSummaryDto;
import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.ImportJob;
import com.asecon.enterpriseiq.model.IngestionFileKind;
import com.asecon.enterpriseiq.model.IngestionFileStatus;
import com.asecon.enterpriseiq.model.IngestionInboxFile;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.asecon.enterpriseiq.repo.IngestionInboxFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PipelineInboxService {
    private record PipelineFilePresentation(String title, String detail, String badgeTone, String actionLabel) {}
    private record PipelineSummaryPresentation(String headline, String detail, String badgeTone, String actionLabel) {}

    private static final Logger log = LoggerFactory.getLogger(PipelineInboxService.class);
    private static final Pattern PERIOD = Pattern.compile("(\\d{4}-\\d{2})");

    private final CompanyRepository companyRepository;
    private final ImportService importService;
    private final IngestionInboxFileRepository ingestionInboxFileRepository;
    private final Path inboxRoot;
    private final CompanySavedMappingService companySavedMappingService;
    private final ObjectMapper objectMapper;

    public PipelineInboxService(CompanyRepository companyRepository,
                                ImportService importService,
                                IngestionInboxFileRepository ingestionInboxFileRepository,
                                CompanySavedMappingService companySavedMappingService,
                                ObjectMapper objectMapper,
                                @Value("${app.storage.inbox}") String inboxRoot) {
        this.companyRepository = companyRepository;
        this.importService = importService;
        this.ingestionInboxFileRepository = ingestionInboxFileRepository;
        this.companySavedMappingService = companySavedMappingService;
        this.objectMapper = objectMapper;
        this.inboxRoot = Path.of(inboxRoot).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.inboxRoot);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot create inbox storage dir: " + this.inboxRoot, ex);
        }
    }

    public void pollInbox() {
        if (!Files.exists(inboxRoot) || !Files.isDirectory(inboxRoot)) return;
        try (DirectoryStream<Path> companies = Files.newDirectoryStream(inboxRoot)) {
            for (Path companyDir : companies) {
                if (!Files.isDirectory(companyDir)) continue;
                Long companyId = parseCompanyId(companyDir.getFileName().toString());
                if (companyId == null || companyRepository.findById(companyId).isEmpty()) continue;
                try {
                    scanCompanyInbox(companyId);
                } catch (Exception ex) {
                    log.warn("pipeline inbox scan failed company={} err={}", companyId, ex.getMessage());
                }
            }
        } catch (IOException ex) {
            log.warn("pipeline inbox poll failed: {}", ex.getMessage());
        }
    }

    public PipelineScanResultDto scanCompanyInbox(Long companyId) {
        Company company = companyRepository.findById(companyId).orElseThrow();
        Path companyDir = inboxRoot.resolve(String.valueOf(company.getId())).toAbsolutePath().normalize();
        prepareCompanyDirs(companyDir);

        long discovered = 0;
        long imported = 0;
        long failed = 0;
        long skipped = 0;

        try (var files = Files.walk(companyDir)) {
            for (Path file : files
                .filter(Files::isRegularFile)
                .filter(this::isSupportedFile)
                .filter(path -> !isArchivePath(companyDir, path))
                .sorted(Comparator.comparing(Path::toString))
                .toList()) {
                discovered++;
                IngestionFileStatus result = processFile(company, companyDir, file);
                if (result == IngestionFileStatus.DONE) {
                    imported++;
                } else if (result == IngestionFileStatus.SKIPPED) {
                    skipped++;
                } else {
                    failed++;
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot scan inbox for company " + companyId, ex);
        }

        return new PipelineScanResultDto(Instant.now(), discovered, imported, failed, skipped);
    }

    public List<PipelineFileDto> listFiles(Long companyId) {
        return ingestionInboxFileRepository.findTop200ByCompanyIdOrderByDetectedAtDesc(companyId).stream()
            .map(this::toDto)
            .toList();
    }

    public PipelineSummaryDto summary(Long companyId) {
        long totalFiles = ingestionInboxFileRepository.countByCompanyId(companyId);
        long doneFiles = ingestionInboxFileRepository.countByCompanyIdAndStatus(companyId, IngestionFileStatus.DONE);
        long errorFiles = ingestionInboxFileRepository.countByCompanyIdAndStatus(companyId, IngestionFileStatus.ERROR);
        long pendingFiles = ingestionInboxFileRepository.countByCompanyIdAndStatus(companyId, IngestionFileStatus.PENDING);
        long processingFiles = ingestionInboxFileRepository.countByCompanyIdAndStatus(companyId, IngestionFileStatus.PROCESSING);
        long skippedFiles = ingestionInboxFileRepository.countByCompanyIdAndStatus(companyId, IngestionFileStatus.SKIPPED);
        Instant lastDetectedAt = ingestionInboxFileRepository.findFirstByCompanyIdOrderByDetectedAtDesc(companyId)
            .map(IngestionInboxFile::getDetectedAt)
            .orElse(null);
        PipelineSummaryPresentation presentation = presentSummary(totalFiles, doneFiles, errorFiles, pendingFiles, processingFiles, skippedFiles);
        return new PipelineSummaryDto(
            Instant.now(),
            totalFiles,
            doneFiles,
            errorFiles,
            pendingFiles,
            processingFiles,
            skippedFiles,
            lastDetectedAt,
            presentation.headline(),
            presentation.detail(),
            presentation.badgeTone(),
            presentation.actionLabel()
        );
    }

    public PipelineFileDto retryFile(Long companyId, Long fileId) {
        IngestionInboxFile file = ingestionInboxFileRepository.findById(fileId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fichero de pipeline no encontrado."));
        if (!file.getCompany().getId().equals(companyId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fichero de pipeline no encontrado en esta empresa.");
        }

        if (file.getDetectedKind() != IngestionFileKind.TRANSACTIONS) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Este fichero requiere revisión manual. Por ahora solo se puede reprocesar caja/transacciones desde Pipeline Center."
            );
        }
        if (file.getPeriod() == null || file.getPeriod().isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "No se puede reprocesar sin periodo. Corrige el nombre del fichero para incluir YYYY-MM."
            );
        }

        Path source = resolveStoredPath(file);
        if (!Files.exists(source) || !Files.isRegularFile(source)) {
            throw new ResponseStatusException(
                HttpStatus.GONE,
                "El fichero original ya no está disponible en storage. Vuelve a copiarlo a la bandeja."
            );
        }

        file.setStatus(IngestionFileStatus.PROCESSING);
        file.setMessage("Reprocesando desde Pipeline Center.");
        file.setUpdatedAt(Instant.now());
        ingestionInboxFileRepository.save(file);

        try {
            ImportJob importJob = importService.createImportFromPath(companyId, file.getPeriod(), source);
            file.setImportJob(importJob);
            file.setStatus(IngestionFileStatus.DONE);
            file.setMessage("Fichero reprocesado y reenviado a cola de importación.");
            file.setProcessedAt(Instant.now());
            file.setUpdatedAt(file.getProcessedAt());
            ingestionInboxFileRepository.save(file);
            return toDto(file);
        } catch (Exception ex) {
            file.setStatus(IngestionFileStatus.ERROR);
            file.setMessage(shortMessage(ex));
            file.setProcessedAt(Instant.now());
            file.setUpdatedAt(file.getProcessedAt());
            ingestionInboxFileRepository.save(file);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, shortMessage(ex));
        }
    }

    public PipelineFileDto updatePeriod(Long companyId, Long fileId, String period) {
        IngestionInboxFile file = ingestionInboxFileRepository.findById(fileId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fichero de pipeline no encontrado."));
        if (!file.getCompany().getId().equals(companyId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fichero de pipeline no encontrado en esta empresa.");
        }

        String normalized = normalizePeriod(period);
        file.setPeriod(normalized);
        file.setUpdatedAt(Instant.now());
        if (file.getStatus() == IngestionFileStatus.ERROR || file.getStatus() == IngestionFileStatus.SKIPPED) {
            file.setMessage("Periodo actualizado. Ya puedes reprocesar este fichero desde Pipeline Center.");
        } else {
            file.setMessage("Periodo actualizado manualmente desde Pipeline Center.");
        }
        ingestionInboxFileRepository.save(file);
        return toDto(file);
    }

    public PipelineFileDto updateKind(Long companyId, Long fileId, String kind) {
        IngestionInboxFile file = ingestionInboxFileRepository.findById(fileId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fichero de pipeline no encontrado."));
        if (!file.getCompany().getId().equals(companyId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fichero de pipeline no encontrado en esta empresa.");
        }

        IngestionFileKind normalized = normalizeKind(kind);
        file.setDetectedKind(normalized);
        file.setUpdatedAt(Instant.now());
        if (normalized == IngestionFileKind.TRANSACTIONS) {
            file.setMessage("Tipo actualizado a Caja. Si el periodo es correcto, ya puedes reprocesar este fichero.");
        } else if (normalized == IngestionFileKind.TRIBUNAL) {
            file.setMessage("Tipo actualizado a Tribunal. Queda clasificado y listo para siguientes automatizaciones.");
        } else if (normalized == IngestionFileKind.UNIVERSAL) {
            file.setMessage("Tipo actualizado a Universal. Queda clasificado y listo para siguientes automatizaciones.");
        } else {
            file.setMessage("Tipo actualizado manualmente desde Pipeline Center.");
        }
        ingestionInboxFileRepository.save(file);
        return toDto(file);
    }

    private IngestionFileStatus processFile(Company company, Path companyDir, Path file) {
        String filename = file.getFileName().toString();
        String period = extractPeriod(filename);
        String relativeInboxPath = relativePath(file);
        IngestionFileKind detectedKind = detectKind(company.getId(), filename, relativeInboxPath);
        IngestionInboxFile tracked = new IngestionInboxFile();
        tracked.setCompany(company);
        tracked.setSourceType("WATCHED_FOLDER");
        tracked.setDetectedKind(detectedKind);
        tracked.setFilename(filename);
        tracked.setInboxPath(relativeInboxPath);
        tracked.setPeriod(period);
        tracked.setStatus(IngestionFileStatus.PROCESSING);
        tracked.setDetectedAt(Instant.now());
        tracked.setUpdatedAt(tracked.getDetectedAt());
        tracked.setMessage("Procesando fichero detectado en la bandeja.");
        tracked = ingestionInboxFileRepository.save(tracked);

        Path processedDir = companyDir.resolve("processed");
        Path errorsDir = companyDir.resolve("errors");
        try {
            if (period == null) {
                return finish(tracked, IngestionFileStatus.ERROR, "No se detecta periodo YYYY-MM en el nombre del fichero.", file, errorsDir);
            }

            if (detectedKind != IngestionFileKind.TRANSACTIONS) {
                return finish(tracked, IngestionFileStatus.SKIPPED, "Automatización disponible por ahora solo para caja/transacciones.", file, errorsDir);
            }

            ImportJob importJob = importService.createImportFromPath(company.getId(), period, file);
            tracked.setImportJob(importJob);
            return finish(tracked, IngestionFileStatus.DONE, "Fichero registrado en cola de importación.", file, processedDir);
        } catch (Exception ex) {
            log.warn("pipeline inbox import failed company={} file={} err={}", company.getId(), filename, ex.getMessage());
            return finish(tracked, IngestionFileStatus.ERROR, shortMessage(ex), file, errorsDir);
        }
    }

    private IngestionFileStatus finish(IngestionInboxFile tracked,
                                       IngestionFileStatus status,
                                       String message,
                                       Path originalFile,
                                       Path archiveDir) {
        Path archived = archiveDir.resolve(stamp(originalFile.getFileName().toString()));
        move(originalFile, archived);
        tracked.setStatus(status);
        tracked.setMessage(message);
        tracked.setArchivedPath(relativePath(archived));
        tracked.setProcessedAt(Instant.now());
        tracked.setUpdatedAt(tracked.getProcessedAt());
        ingestionInboxFileRepository.save(tracked);
        return status;
    }

    private PipelineFileDto toDto(IngestionInboxFile file) {
        PipelineFilePresentation presentation = presentFile(file);
        return new PipelineFileDto(
            file.getId(),
            file.getCompany().getId(),
            file.getSourceType(),
            file.getDetectedKind(),
            file.getFilename(),
            file.getInboxPath(),
            file.getArchivedPath(),
            file.getPeriod(),
            file.getStatus(),
            presentation.title(),
            presentation.detail(),
            presentation.badgeTone(),
            presentation.actionLabel(),
            file.getMessage(),
            file.getImportJob() == null ? null : file.getImportJob().getId(),
            file.getDetectedAt(),
            file.getProcessedAt(),
            file.getUpdatedAt()
        );
    }

    private PipelineFilePresentation presentFile(IngestionInboxFile file) {
        IngestionFileStatus status = file == null ? null : file.getStatus();
        IngestionFileKind kind = file == null ? null : file.getDetectedKind();
        String period = file == null ? null : file.getPeriod();
        return switch (status == null ? IngestionFileStatus.PENDING : status) {
            case DONE -> new PipelineFilePresentation(
                "Enviado a cola",
                nonBlank(file.getMessage(), "El fichero ya quedo registrado en el circuito de importacion."),
                "ok",
                kind == IngestionFileKind.TRANSACTIONS ? "Abrir Caja" : "Abrir modulo"
            );
            case PROCESSING -> new PipelineFilePresentation(
                "Procesando bandeja",
                nonBlank(file.getMessage(), "El fichero esta siendo clasificado y movido por la bandeja operativa."),
                "warn",
                "Esperar procesamiento"
            );
            case ERROR -> new PipelineFilePresentation(
                "Bloqueado en pipeline",
                nonBlank(file.getMessage(), "El fichero necesita correccion manual antes de volver a cola."),
                "err",
                period == null || period.isBlank() ? "Corregir periodo" : "Reprocesar"
            );
            case SKIPPED -> new PipelineFilePresentation(
                "Pendiente de clasificacion",
                nonBlank(file.getMessage(), "El fichero quedo fuera de la automatizacion actual y necesita criterio operativo."),
                "warn",
                kind == IngestionFileKind.TRANSACTIONS ? "Reprocesar" : "Corregir clasificacion"
            );
            case PENDING -> new PipelineFilePresentation(
                "Pendiente de entrada",
                nonBlank(file.getMessage(), "El fichero ya se detecto, pero aun no ha terminado de entrar en el circuito."),
                "",
                "Escanear bandeja"
            );
        };
    }

    private PipelineSummaryPresentation presentSummary(long totalFiles,
                                                       long doneFiles,
                                                       long errorFiles,
                                                       long pendingFiles,
                                                       long processingFiles,
                                                       long skippedFiles) {
        if (errorFiles > 0) {
            return new PipelineSummaryPresentation(
                "Pipeline con incidencias",
                "Hay ficheros bloqueados en la bandeja. Conviene corregir periodo, clasificacion o formato antes de seguir.",
                "err",
                "Resolver incidencias"
            );
        }
        if (processingFiles > 0 || pendingFiles > 0) {
            return new PipelineSummaryPresentation(
                "Pipeline en marcha",
                "La bandeja ya esta procesando entradas y todavia quedan ficheros avanzando por el circuito.",
                "warn",
                "Seguir pipeline"
            );
        }
        if (skippedFiles > 0) {
            return new PipelineSummaryPresentation(
                "Pipeline con ficheros pendientes de criterio",
                "Hay entradas clasificadas pero fuera de la automatizacion actual. Conviene afinarlas para quitar trabajo manual.",
                "warn",
                "Revisar clasificacion"
            );
        }
        if (doneFiles > 0) {
            return new PipelineSummaryPresentation(
                "Bandeja operativa al dia",
                "Los ficheros recientes han entrado bien en el circuito y ya alimentan la operativa.",
                "ok",
                "Ver trazabilidad"
            );
        }
        return new PipelineSummaryPresentation(
            totalFiles > 0 ? "Sin actividad reciente" : "Bandeja sin actividad",
            totalFiles > 0
                ? "No quedan ficheros recientes en curso. El siguiente paso es dejar nueva entrada o relanzar un escaneo."
                : "Todavia no se han detectado ficheros en la bandeja de esta empresa.",
            "",
            "Escanear bandeja"
        );
    }

    private String nonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private void prepareCompanyDirs(Path companyDir) {
        try {
            Files.createDirectories(companyDir);
            Files.createDirectories(companyDir.resolve("processed"));
            Files.createDirectories(companyDir.resolve("errors"));
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot prepare inbox dir " + companyDir, ex);
        }
    }

    private IngestionFileKind detectKind(Long companyId, String filename, String relativePath) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String lowerPath = relativePath == null ? "" : relativePath.toLowerCase(Locale.ROOT);
        RulesEnvelope envelope = loadRules(companyId);
        for (PipelineRule rule : envelope.rules()) {
            String textProbe = normalizeProbe(rule.matchText);
            String pathProbe = normalizeProbe(rule.pathContains);
            boolean textMatches = textProbe.isEmpty() || lower.contains(textProbe);
            boolean pathMatches = pathProbe.isEmpty() || lowerPath.contains(pathProbe);
            if (textMatches && pathMatches && (!textProbe.isEmpty() || !pathProbe.isEmpty())) {
                try {
                    return normalizeKind(rule.kind);
                } catch (ResponseStatusException ignored) {
                    // ignore invalid persisted rule and fall back to defaults
                }
            }
        }
        if (lower.contains("tribunal")) return IngestionFileKind.TRIBUNAL;
        if (lower.contains("universal")) return IngestionFileKind.UNIVERSAL;
        if (envelope.defaultKind != null && !envelope.defaultKind.isBlank()) {
            try {
                return normalizeKind(envelope.defaultKind);
            } catch (ResponseStatusException ignored) {
                // ignore invalid persisted default kind and fall back to platform default
            }
        }
        return IngestionFileKind.TRANSACTIONS;
    }

    private RulesEnvelope loadRules(Long companyId) {
        try {
            String payload = companySavedMappingService.getPayload(companyId, CompanySavedMappingService.KEY_PIPELINE_RULES);
            if (payload == null || payload.isBlank()) return RulesEnvelope.empty();
            RulesEnvelope envelope = objectMapper.readValue(payload, RulesEnvelope.class);
            return envelope == null ? RulesEnvelope.empty() : envelope.normalized();
        } catch (Exception ex) {
            log.warn("pipeline rules load failed company={} err={}", companyId, ex.getMessage());
            return RulesEnvelope.empty();
        }
    }

    private boolean isSupportedFile(Path file) {
        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".csv") || lower.endsWith(".xlsx");
    }

    private boolean isArchivePath(Path companyDir, Path file) {
        Path normalizedCompanyDir = companyDir.toAbsolutePath().normalize();
        Path normalizedFile = file.toAbsolutePath().normalize();
        return normalizedFile.startsWith(normalizedCompanyDir.resolve("processed"))
            || normalizedFile.startsWith(normalizedCompanyDir.resolve("errors"));
    }

    private String relativePath(Path path) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            if (!normalized.startsWith(inboxRoot)) return normalized.toString().replace('\\', '/');
            return inboxRoot.relativize(normalized).toString().replace('\\', '/');
        } catch (Exception ex) {
            return path.toString().replace('\\', '/');
        }
    }

    private Path resolveStoredPath(IngestionInboxFile file) {
        String candidate = file.getArchivedPath();
        if (candidate == null || candidate.isBlank()) candidate = file.getInboxPath();
        if (candidate == null || candidate.isBlank()) {
            throw new ResponseStatusException(HttpStatus.GONE, "No hay ruta de storage asociada a este fichero.");
        }
        Path path = inboxRoot.resolve(candidate).toAbsolutePath().normalize();
        if (!path.startsWith(inboxRoot)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ruta de storage inválida.");
        }
        return path;
    }

    private static String normalizeProbe(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private static Long parseCompanyId(String folder) {
        try {
            return Long.parseLong(folder.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String extractPeriod(String filename) {
        Matcher matcher = PERIOD.matcher(filename == null ? "" : filename);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static IngestionFileKind normalizeKind(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        try {
            return IngestionFileKind.valueOf(value);
        } catch (Exception ex) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "El tipo debe ser TRANSACTIONS, TRIBUNAL, UNIVERSAL o UNKNOWN."
            );
        }
    }

    private static String normalizePeriod(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!value.matches("\\d{4}-\\d{2}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El periodo debe tener formato YYYY-MM.");
        }
        int month = Integer.parseInt(value.substring(5, 7));
        if (month < 1 || month > 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El mes del periodo debe estar entre 01 y 12.");
        }
        return value;
    }

    private static String stamp(String filename) {
        return Instant.now().toString().replace(":", "-") + "-" + filename;
    }

    private static void move(Path from, Path to) {
        try {
            Files.move(from, to);
        } catch (IOException ex) {
            try {
                Files.copy(from, to);
                Files.deleteIfExists(from);
            } catch (IOException ignored) {
            }
        }
    }

    private static String shortMessage(Exception ex) {
        if (ex == null || ex.getMessage() == null || ex.getMessage().isBlank()) return "Error al procesar el fichero.";
        String msg = ex.getMessage().trim();
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }

    private static class RulesEnvelope {
        public String defaultKind;
        public List<PipelineRule> rules;

        RulesEnvelope normalized() {
            this.rules = this.rules == null ? List.of() : this.rules;
            return this;
        }

        List<PipelineRule> rules() {
            return rules == null ? List.of() : rules;
        }

        static RulesEnvelope empty() {
            RulesEnvelope envelope = new RulesEnvelope();
            envelope.rules = List.of();
            return envelope;
        }
    }

    private static class PipelineRule {
        public String matchText;
        public String pathContains;
        public String kind;
    }
}

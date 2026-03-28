package com.asecon.enterpriseiq.service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class InboxImportScheduler {
    private static final Logger log = LoggerFactory.getLogger(InboxImportScheduler.class);
    private static final Pattern PERIOD = Pattern.compile("(\\d{4}-\\d{2})");

    private final ImportService importService;
    private final Path inboxRoot;

    public InboxImportScheduler(ImportService importService, @Value("${app.storage.inbox}") String inboxRoot) {
        this.importService = importService;
        this.inboxRoot = Path.of(inboxRoot);
    }

    @Scheduled(fixedDelayString = "${app.scheduler.inbox-fixed-delay-ms:30000}")
    public void pollInbox() {
        if (!Files.exists(inboxRoot) || !Files.isDirectory(inboxRoot)) return;
        try (DirectoryStream<Path> companies = Files.newDirectoryStream(inboxRoot)) {
            for (Path companyDir : companies) {
                if (!Files.isDirectory(companyDir)) continue;
                Long companyId = parseCompanyId(companyDir.getFileName().toString());
                if (companyId == null) continue;
                processCompany(companyId, companyDir);
            }
        } catch (IOException ex) {
            log.warn("inbox poll failed: {}", ex.getMessage());
        }
    }

    private void processCompany(Long companyId, Path companyDir) {
        Path processed = companyDir.resolve("processed");
        Path errors = companyDir.resolve("errors");
        try {
            Files.createDirectories(processed);
            Files.createDirectories(errors);
        } catch (IOException ignored) {}

        try (DirectoryStream<Path> files = Files.newDirectoryStream(companyDir)) {
            for (Path file : files) {
                if (!Files.isRegularFile(file)) continue;
                String lower = file.getFileName().toString().toLowerCase();
                if (!(lower.endsWith(".csv") || lower.endsWith(".xlsx"))) continue;
                String period = extractPeriod(file.getFileName().toString());
                if (period == null) {
                    move(file, errors.resolve(stamp(file.getFileName().toString())));
                    continue;
                }
                try {
                    importService.createImportFromPath(companyId, period, file);
                    move(file, processed.resolve(stamp(file.getFileName().toString())));
                } catch (Exception ex) {
                    log.warn("inbox import failed company={} file={} err={}", companyId, file.getFileName(), ex.getMessage());
                    move(file, errors.resolve(stamp(file.getFileName().toString())));
                }
            }
        } catch (IOException ex) {
            log.warn("inbox scan failed company={} err={}", companyId, ex.getMessage());
        }
    }

    private static Long parseCompanyId(String folder) {
        try {
            return Long.parseLong(folder.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String extractPeriod(String filename) {
        Matcher m = PERIOD.matcher(filename);
        return m.find() ? m.group(1) : null;
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
            } catch (IOException ignored) {}
        }
    }
}

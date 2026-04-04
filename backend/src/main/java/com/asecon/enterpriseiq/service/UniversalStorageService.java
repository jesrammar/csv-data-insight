package com.asecon.enterpriseiq.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class UniversalStorageService {
    private final Path universalRoot;

    public UniversalStorageService(@Value("${app.storage.universal}") String universalRoot) {
        this.universalRoot = Path.of(universalRoot).toAbsolutePath().normalize();
    }

    public Path writeNormalizedCsv(long importId, byte[] bytes) throws IOException {
        Files.createDirectories(universalRoot);
        Path target = universalRoot.resolve("universal-" + importId + ".csv");
        Files.write(target, bytes);
        return target;
    }

    public byte[] readBytes(String storageRef) throws IOException {
        if (storageRef == null || storageRef.isBlank()) {
            throw new IOException("Missing storageRef");
        }
        Path candidate = Path.of(storageRef);
        Path absFile = candidate.isAbsolute()
            ? candidate.toAbsolutePath().normalize()
            : universalRoot.resolve(storageRef).toAbsolutePath().normalize();
        if (!absFile.startsWith(universalRoot)) {
            throw new IOException("Invalid storageRef (path traversal)");
        }
        return Files.readAllBytes(absFile);
    }
}


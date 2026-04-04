package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.Plan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UploadLimitService {
    private final long defaultMaxBytes;
    private final long bronzeMaxBytes;
    private final long goldMaxBytes;
    private final long platinumMaxBytes;

    public UploadLimitService(@Value("${app.upload.max-bytes:20971520}") long defaultMaxBytes,
                              @Value("${app.upload.max-bytes-by-plan.bronze:0}") long bronzeMaxBytes,
                              @Value("${app.upload.max-bytes-by-plan.gold:0}") long goldMaxBytes,
                              @Value("${app.upload.max-bytes-by-plan.platinum:0}") long platinumMaxBytes) {
        this.defaultMaxBytes = defaultMaxBytes;
        this.bronzeMaxBytes = bronzeMaxBytes;
        this.goldMaxBytes = goldMaxBytes;
        this.platinumMaxBytes = platinumMaxBytes;
    }

    public void requireAllowed(MultipartFile file) {
        requireAllowed(file, null);
    }

    public void requireAllowed(MultipartFile file, Plan plan) {
        if (file == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta archivo");
        }
        long maxBytes = resolveMaxBytes(plan);
        if (maxBytes > 0 && file.getSize() > maxBytes) {
            long mb = Math.max(1L, maxBytes / (1024L * 1024L));
            throw new ResponseStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Archivo demasiado grande (límite " + mb + "MB). Recomendación: exporta un solo periodo, elimina hojas/tablas extra o usa Universal."
            );
        }
    }

    long resolveMaxBytes(Plan plan) {
        if (plan == null) return defaultMaxBytes;
        return switch (plan) {
            case BRONZE -> bronzeMaxBytes > 0 ? bronzeMaxBytes : defaultMaxBytes;
            case GOLD -> goldMaxBytes > 0 ? goldMaxBytes : defaultMaxBytes;
            case PLATINUM -> platinumMaxBytes > 0 ? platinumMaxBytes : defaultMaxBytes;
        };
    }
}


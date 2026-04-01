package com.asecon.enterpriseiq.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UploadLimitService {
    private final long maxBytes;

    public UploadLimitService(@Value("${app.upload.max-bytes:20971520}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public void requireAllowed(MultipartFile file) {
        if (file == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta archivo");
        }
        if (maxBytes > 0 && file.getSize() > maxBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Archivo demasiado grande");
        }
    }
}


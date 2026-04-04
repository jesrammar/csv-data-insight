package com.asecon.enterpriseiq.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class UploadLimitServiceTest {

    @Test
    void requireAllowed_returnsHelpfulMessage() {
        UploadLimitService svc = new UploadLimitService(5);
        MockMultipartFile file = new MockMultipartFile("file", "big.csv", "text/csv", "0123456789".getBytes());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> svc.requireAllowed(file));
        assertEquals(413, ex.getStatusCode().value());
        assertTrue(String.valueOf(ex.getReason()).toLowerCase().contains("límite"));
    }
}


package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.Plan;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class UploadLimitServicePlanTest {
    @Test
    void resolves_plan_specific_limits_with_default_fallback() {
        UploadLimitService s = new UploadLimitService(
            20 * 1024 * 1024L,
            10 * 1024 * 1024L,
            30 * 1024 * 1024L,
            60 * 1024 * 1024L
        );

        assertThat(s.resolveMaxBytes(null)).isEqualTo(20 * 1024 * 1024L);
        assertThat(s.resolveMaxBytes(Plan.BRONZE)).isEqualTo(10 * 1024 * 1024L);
        assertThat(s.resolveMaxBytes(Plan.GOLD)).isEqualTo(30 * 1024 * 1024L);
        assertThat(s.resolveMaxBytes(Plan.PLATINUM)).isEqualTo(60 * 1024 * 1024L);
    }

    @Test
    void blocks_uploads_above_plan_limit() {
        UploadLimitService s = new UploadLimitService(
            20 * 1024 * 1024L,
            10 * 1024 * 1024L,
            0,
            0
        );
        byte[] payload = new byte[11 * 1024 * 1024];
        MockMultipartFile f = new MockMultipartFile("file", "x.csv", "text/csv", payload);

        assertThatThrownBy(() -> s.requireAllowed(f, Plan.BRONZE))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    }
}


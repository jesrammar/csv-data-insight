package com.asecon.enterpriseiq.dto;

import java.time.Instant;

public record AlertDto(
    Long id,
    Long companyId,
    String period,
    String type,
    String message,
    Instant createdAt
) {}


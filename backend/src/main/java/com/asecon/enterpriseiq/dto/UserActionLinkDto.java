package com.asecon.enterpriseiq.dto;

import java.time.Instant;

public record UserActionLinkDto(
    String path,
    Instant expiresAt
) {}


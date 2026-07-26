package com.asecon.enterpriseiq.dto;

import com.asecon.enterpriseiq.model.PortfolioWorkflowStatus;
import java.time.Instant;

public record PortfolioWorkflowStepDto(
    boolean applicable,
    PortfolioWorkflowStatus status,
    String title,
    String detail,
    Instant updatedAt,
    String badgeTone,
    String actionLabel,
    String shortLabel
) {}

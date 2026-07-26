package com.asecon.enterpriseiq.model;

public enum PeriodWorkflowStatus {
    PENDING_DATA,
    INGESTING,
    EXCEPTIONS,
    READY_FOR_REVIEW,
    REVIEWED,
    REPORT_GENERATING,
    REPORT_READY,
    CLOSED
}

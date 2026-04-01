package com.asecon.enterpriseiq.dto;

import java.time.Instant;

public class StorageCleanupResultDto {
    private Instant startedAt;
    private Instant finishedAt;
    private boolean enabled;
    private long errors;
    private Counts imports;
    private Counts reports;
    private Counts universal;

    public StorageCleanupResultDto(Instant startedAt,
                                  Instant finishedAt,
                                  boolean enabled,
                                  long errors,
                                  Counts imports,
                                  Counts reports,
                                  Counts universal) {
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.enabled = enabled;
        this.errors = errors;
        this.imports = imports;
        this.reports = reports;
        this.universal = universal;
    }

    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public boolean isEnabled() { return enabled; }
    public long getErrors() { return errors; }
    public Counts getImports() { return imports; }
    public Counts getReports() { return reports; }
    public Counts getUniversal() { return universal; }

    public static class Counts {
        private long refsCleared;

        public Counts(long refsCleared) {
            this.refsCleared = refsCleared;
        }

        public long getRefsCleared() { return refsCleared; }
    }
}


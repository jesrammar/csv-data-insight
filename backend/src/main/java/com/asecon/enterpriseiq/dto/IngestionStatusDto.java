package com.asecon.enterpriseiq.dto;

import java.time.Instant;

public class IngestionStatusDto {
    private Instant now;
    private ImportDto lastImport;
    private ImportDto lastProcessedImport;
    private ImportDto nextScheduledImport;

    public IngestionStatusDto() {}

    public IngestionStatusDto(Instant now,
                              ImportDto lastImport,
                              ImportDto lastProcessedImport,
                              ImportDto nextScheduledImport) {
        this.now = now;
        this.lastImport = lastImport;
        this.lastProcessedImport = lastProcessedImport;
        this.nextScheduledImport = nextScheduledImport;
    }

    public Instant getNow() { return now; }
    public ImportDto getLastImport() { return lastImport; }
    public ImportDto getLastProcessedImport() { return lastProcessedImport; }
    public ImportDto getNextScheduledImport() { return nextScheduledImport; }
}


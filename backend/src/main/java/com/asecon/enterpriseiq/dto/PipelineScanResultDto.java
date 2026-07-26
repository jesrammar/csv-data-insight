package com.asecon.enterpriseiq.dto;

import java.time.Instant;

public class PipelineScanResultDto {
    private Instant scannedAt;
    private long discovered;
    private long imported;
    private long failed;
    private long skipped;

    public PipelineScanResultDto(Instant scannedAt, long discovered, long imported, long failed, long skipped) {
        this.scannedAt = scannedAt;
        this.discovered = discovered;
        this.imported = imported;
        this.failed = failed;
        this.skipped = skipped;
    }

    public Instant getScannedAt() { return scannedAt; }
    public long getDiscovered() { return discovered; }
    public long getImported() { return imported; }
    public long getFailed() { return failed; }
    public long getSkipped() { return skipped; }
}

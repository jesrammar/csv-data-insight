package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.repo.RefreshTokenRepository;
import com.asecon.enterpriseiq.repo.RevokedTokenRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TokenCleanupScheduler {
    private final RefreshTokenRepository refreshTokenRepository;
    private final RevokedTokenRepository revokedTokenRepository;
    private final long revokedRetentionDays;

    public TokenCleanupScheduler(RefreshTokenRepository refreshTokenRepository,
                                 RevokedTokenRepository revokedTokenRepository,
                                 @Value("${app.scheduler.refresh-revoked-retention-days}") long revokedRetentionDays) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.revokedTokenRepository = revokedTokenRepository;
        this.revokedRetentionDays = revokedRetentionDays;
    }

    @Scheduled(fixedDelayString = "${app.scheduler.token-cleanup-fixed-delay-ms}")
    public void cleanup() {
        Instant now = Instant.now();
        revokedTokenRepository.deleteByExpiresAtBefore(now);
        refreshTokenRepository.deleteByExpiresAtBefore(now);
        if (revokedRetentionDays > 0) {
            Instant cutoff = now.minus(revokedRetentionDays, ChronoUnit.DAYS);
            refreshTokenRepository.deleteByRevokedAtBefore(cutoff);
        }
    }
}

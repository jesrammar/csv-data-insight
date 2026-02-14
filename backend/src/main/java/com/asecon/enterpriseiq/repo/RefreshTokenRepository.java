package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.RefreshToken;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    long deleteByExpiresAtBefore(Instant cutoff);
    long deleteByRevokedAtBefore(Instant cutoff);
}

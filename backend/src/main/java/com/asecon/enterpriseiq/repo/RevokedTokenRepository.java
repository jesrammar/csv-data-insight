package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.RevokedToken;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RevokedTokenRepository extends JpaRepository<RevokedToken, String> {
    boolean existsByJti(String jti);
    long deleteByExpiresAtBefore(Instant cutoff);
}

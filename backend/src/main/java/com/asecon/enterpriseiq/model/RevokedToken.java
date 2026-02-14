package com.asecon.enterpriseiq.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "revoked_tokens")
public class RevokedToken {
    @Id
    @Column(length = 64)
    private String jti;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at", nullable = false)
    private Instant revokedAt;

    public RevokedToken() {}

    public RevokedToken(String jti, Instant expiresAt, Instant revokedAt) {
        this.jti = jti;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
    }

    public String getJti() { return jti; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
}

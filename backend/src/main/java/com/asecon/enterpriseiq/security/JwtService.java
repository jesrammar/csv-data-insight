package com.asecon.enterpriseiq.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final Key key;
    private final long accessExpirationMinutes;
    private final long refreshExpirationDays;
    private final String issuer;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.access-expiration-minutes}") long accessExpirationMinutes,
                      @Value("${app.jwt.refresh-expiration-days}") long refreshExpirationDays,
                      @Value("${app.jwt.issuer}") String issuer) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpirationMinutes = accessExpirationMinutes;
        this.refreshExpirationDays = refreshExpirationDays;
        this.issuer = issuer;
    }

    public String generateAccessToken(String subject, String role, Long userId) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(accessExpirationMinutes * 60);
        String jti = UUID.randomUUID().toString();
        return Jwts.builder()
            .setSubject(subject)
            .setIssuer(issuer)
            .claim("role", role)
            .claim("userId", userId)
            .claim("typ", "access")
            .setId(jti)
            .setIssuedAt(Date.from(now))
            .setExpiration(Date.from(exp))
            .signWith(key, SignatureAlgorithm.HS256)
            .compact();
    }

    public String extractSubject(String token) {
        return parseClaims(token).getSubject();
    }

    public String extractJti(String token) {
        return parseClaims(token).getId();
    }

    public long getAccessExpirationSeconds() {
        return accessExpirationMinutes * 60;
    }

    public long getRefreshExpirationDays() {
        return refreshExpirationDays;
    }

    public Claims parseAccessToken(String token) {
        Claims claims = parseClaims(token);
        Object typ = claims.get("typ");
        if (typ == null || !"access".equals(typ.toString())) {
            throw new IllegalArgumentException("Invalid token type");
        }
        return claims;
    }

    private Claims parseClaims(String token) {
        return Jwts.parserBuilder()
            .setSigningKey(key)
            .requireIssuer(issuer)
            .build()
            .parseClaimsJws(token)
            .getBody();
    }
}

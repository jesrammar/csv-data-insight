package com.asecon.enterpriseiq.security;

import com.asecon.enterpriseiq.model.RefreshToken;
import com.asecon.enterpriseiq.model.RevokedToken;
import com.asecon.enterpriseiq.model.User;
import com.asecon.enterpriseiq.repo.RefreshTokenRepository;
import com.asecon.enterpriseiq.repo.RevokedTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
    private final RefreshTokenRepository refreshTokenRepository;
    private final RevokedTokenRepository revokedTokenRepository;
    private final JwtService jwtService;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenService(RefreshTokenRepository refreshTokenRepository,
                        RevokedTokenRepository revokedTokenRepository,
                        JwtService jwtService) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.revokedTokenRepository = revokedTokenRepository;
        this.jwtService = jwtService;
    }

    public AuthTokens issueTokens(User user) {
        String accessToken = jwtService.generateAccessToken(user.getEmail(), user.getRole().name(), user.getId());
        RefreshTokenWithRaw refresh = createRefreshToken(user);
        return new AuthTokens(accessToken, refresh.rawToken(), jwtService.getAccessExpirationSeconds());
    }

    public AuthResult refreshTokens(String refreshTokenRaw) {
        String hash = hash(refreshTokenRaw);
        RefreshToken token = refreshTokenRepository.findByTokenHash(hash)
            .orElseThrow(() -> new IllegalArgumentException("Refresh token inválido"));
        if (token.getRevokedAt() != null || token.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Refresh token expirado o revocado");
        }

        if (!token.getUser().isEnabled()) {
            throw new IllegalArgumentException("Usuario deshabilitado");
        }
        RefreshTokenWithRaw rotated = rotateRefreshToken(token);
        String accessToken = jwtService.generateAccessToken(
            token.getUser().getEmail(),
            token.getUser().getRole().name(),
            token.getUser().getId()
        );
        AuthTokens tokens = new AuthTokens(accessToken, rotated.rawToken(), jwtService.getAccessExpirationSeconds());
        return new AuthResult(token.getUser(), tokens);
    }

    public void revokeRefreshToken(String refreshTokenRaw) {
        if (refreshTokenRaw == null || refreshTokenRaw.isBlank()) {
            return;
        }
        String hash = hash(refreshTokenRaw);
        refreshTokenRepository.findByTokenHash(hash).ifPresent(token -> {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(Instant.now());
                refreshTokenRepository.save(token);
            }
        });
    }

    public void revokeAccessToken(String accessTokenRaw) {
        if (accessTokenRaw == null || accessTokenRaw.isBlank()) {
            return;
        }
        var claims = jwtService.parseAccessToken(accessTokenRaw);
        String jti = claims.getId();
        if (jti == null || jti.isBlank()) {
            return;
        }
        if (!revokedTokenRepository.existsByJti(jti)) {
            revokedTokenRepository.save(new RevokedToken(jti, claims.getExpiration().toInstant(), Instant.now()));
        }
    }

    public boolean isAccessTokenRevoked(String jti) {
        return revokedTokenRepository.existsByJti(jti);
    }

    private RefreshTokenWithRaw createRefreshToken(User user) {
        String raw = generateRefreshToken();
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(jwtService.getRefreshExpirationDays() * 24 * 60 * 60);

        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(hash(raw));
        token.setCreatedAt(now);
        token.setExpiresAt(exp);
        refreshTokenRepository.save(token);

        return new RefreshTokenWithRaw(token, raw);
    }

    private RefreshTokenWithRaw rotateRefreshToken(RefreshToken existing) {
        String raw = generateRefreshToken();
        String hash = hash(raw);
        existing.setRevokedAt(Instant.now());
        existing.setReplacedByHash(hash);
        refreshTokenRepository.save(existing);

        RefreshToken token = new RefreshToken();
        token.setUser(existing.getUser());
        token.setTokenHash(hash);
        token.setCreatedAt(Instant.now());
        token.setExpiresAt(Instant.now().plusSeconds(jwtService.getRefreshExpirationDays() * 24 * 60 * 60));
        refreshTokenRepository.save(token);
        return new RefreshTokenWithRaw(token, raw);
    }

    private String generateRefreshToken() {
        byte[] bytes = new byte[48];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashed) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo generar hash", ex);
        }
    }

    public record AuthTokens(String accessToken, String refreshToken, long accessTokenExpiresInSeconds) {}
    public record AuthResult(User user, AuthTokens tokens) {}
    private record RefreshTokenWithRaw(RefreshToken token, String rawToken) {}
}

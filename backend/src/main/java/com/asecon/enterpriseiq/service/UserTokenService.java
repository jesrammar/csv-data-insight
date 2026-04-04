package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.User;
import com.asecon.enterpriseiq.model.UserToken;
import com.asecon.enterpriseiq.model.UserTokenPurpose;
import com.asecon.enterpriseiq.repo.UserTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserTokenService {
    private final UserTokenRepository userTokenRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public UserTokenService(UserTokenRepository userTokenRepository) {
        this.userTokenRepository = userTokenRepository;
    }

    public IssuedToken issue(User actor, User target, UserTokenPurpose purpose, Duration ttl) {
        if (target == null || target.getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Usuario inválido");
        }
        Duration safeTtl = ttl == null ? Duration.ofMinutes(30) : ttl;
        if (safeTtl.isNegative() || safeTtl.isZero()) safeTtl = Duration.ofMinutes(30);
        if (safeTtl.compareTo(Duration.ofDays(30)) > 0) safeTtl = Duration.ofDays(30);

        Instant now = Instant.now();
        userTokenRepository.invalidateActiveForUser(target.getId(), purpose, now);

        String raw = generateRawToken();
        String hash = sha256Hex(raw);

        UserToken token = new UserToken();
        token.setUser(target);
        token.setPurpose(purpose);
        token.setTokenHash(hash);
        token.setCreatedAt(now);
        token.setExpiresAt(now.plus(safeTtl));
        token.setCreatedByUserId(actor == null ? null : actor.getId());

        userTokenRepository.save(token);

        return new IssuedToken(raw, token.getExpiresAt());
    }

    public UserToken consumeValid(String rawToken, UserTokenPurpose purpose) {
        String raw = rawToken == null ? "" : rawToken.trim();
        if (raw.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta token");

        Instant now = Instant.now();
        String hash = sha256Hex(raw);
        UserToken token = userTokenRepository.findValidByHashWithUser(hash, purpose, now)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token inválido o caducado"));

        token.setUsedAt(now);
        return userTokenRepository.save(token);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo hashear token", ex);
        }
    }

    public record IssuedToken(String token, Instant expiresAt) {}
}


package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.LoginRequest;
import com.asecon.enterpriseiq.dto.LoginResponse;
import com.asecon.enterpriseiq.dto.LogoutRequest;
import com.asecon.enterpriseiq.dto.RefreshRequest;
import com.asecon.enterpriseiq.repo.UserRepository;
import com.asecon.enterpriseiq.security.TokenService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final String REFRESH_COOKIE_NAME = "enterpriseiq_refresh";

    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean cookieSecure;
    private final String cookieSameSite;

    public AuthController(TokenService tokenService,
                          UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          @Value("${app.cookies.secure:false}") boolean cookieSecure,
                          @Value("${app.cookies.same-site:Lax}") String cookieSameSite) {
        this.tokenService = tokenService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.cookieSecure = cookieSecure;
        this.cookieSameSite = cookieSameSite;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletResponse response,
                                               @RequestHeader(value = "X-Auth-Mode", required = false) String authMode) {
        var user = userRepository.findByEmail(request.getEmail())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales incorrectas"));
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales incorrectas");
        }
        var tokens = tokenService.issueTokens(user);
        ResponseCookie refreshCookie = buildRefreshCookie(tokens.refreshToken());
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());

        boolean allowBodyRefreshToken = "api".equalsIgnoreCase(authMode);
        return ResponseEntity.ok(new LoginResponse(
            tokens.accessToken(),
            allowBodyRefreshToken ? tokens.refreshToken() : null,
            user.getRole().name(),
            user.getId(),
            tokens.accessTokenExpiresInSeconds()
        ));
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@RequestBody(required = false) RefreshRequest request,
                                                 @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshCookieValue,
                                                 HttpServletResponse response,
                                                 @RequestHeader(value = "X-Auth-Mode", required = false) String authMode) {
        String refreshToken = request != null ? request.getRefreshToken() : null;
        if (refreshToken == null || refreshToken.isBlank()) {
            refreshToken = refreshCookieValue;
        }
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token invalido");
        }

        boolean cameFromBody = request != null && request.getRefreshToken() != null && !request.getRefreshToken().isBlank();
        try {
            var result = tokenService.refreshTokens(refreshToken);
            var user = result.user();
            var tokens = result.tokens();
            ResponseCookie rotatedCookie = buildRefreshCookie(tokens.refreshToken());
            response.addHeader(HttpHeaders.SET_COOKIE, rotatedCookie.toString());

            boolean allowBodyRefreshToken = cameFromBody || "api".equalsIgnoreCase(authMode);
            return ResponseEntity.ok(new LoginResponse(
                tokens.accessToken(),
                allowBodyRefreshToken ? tokens.refreshToken() : null,
                user.getRole().name(),
                user.getId(),
                tokens.accessTokenExpiresInSeconds()
            ));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token invalido");
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestBody(required = false) LogoutRequest request,
                       @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                       @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshCookieValue,
                       HttpServletResponse response) {
        String refreshToken = request != null ? request.getRefreshToken() : null;
        if (refreshToken == null || refreshToken.isBlank()) {
            refreshToken = refreshCookieValue;
        }
        if (refreshToken != null && !refreshToken.isBlank()) {
            tokenService.revokeRefreshToken(refreshToken);
        }
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                tokenService.revokeAccessToken(authorization.substring(7));
            } catch (Exception ignored) {
                // ignore invalid/expired access token on logout
            }
        }
        response.addHeader(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString());
    }

    private ResponseCookie buildRefreshCookie(String refreshToken) {
        long days = tokenService.getRefreshExpirationDays();
        Duration maxAge = days <= 0 ? Duration.ofDays(30) : Duration.ofDays(days);
        return ResponseCookie.from(REFRESH_COOKIE_NAME, refreshToken)
            .httpOnly(true)
            .secure(cookieSecure)
            .path("/api/auth")
            .maxAge(maxAge)
            .sameSite(cookieSameSite)
            .build();
    }

    private ResponseCookie clearRefreshCookie() {
        return ResponseCookie.from(REFRESH_COOKIE_NAME, "")
            .httpOnly(true)
            .secure(cookieSecure)
            .path("/api/auth")
            .maxAge(Duration.ZERO)
            .sameSite(cookieSameSite)
            .build();
    }
}

package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.LoginRequest;
import com.asecon.enterpriseiq.dto.LoginResponse;
import com.asecon.enterpriseiq.dto.LogoutRequest;
import com.asecon.enterpriseiq.dto.RefreshRequest;
import com.asecon.enterpriseiq.repo.UserRepository;
import com.asecon.enterpriseiq.security.TokenService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthController(TokenService tokenService, UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.tokenService = tokenService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        var user = userRepository.findByEmail(request.getEmail())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales incorrectas"));
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales incorrectas");
        }
        var tokens = tokenService.issueTokens(user);
        return new LoginResponse(
            tokens.accessToken(),
            tokens.refreshToken(),
            user.getRole().name(),
            user.getId(),
            tokens.accessTokenExpiresInSeconds()
        );
    }

    @PostMapping("/refresh")
    public LoginResponse refresh(@Valid @RequestBody RefreshRequest request) {
        try {
            var result = tokenService.refreshTokens(request.getRefreshToken());
            var user = result.user();
            var tokens = result.tokens();
            return new LoginResponse(
                tokens.accessToken(),
                tokens.refreshToken(),
                user.getRole().name(),
                user.getId(),
                tokens.accessTokenExpiresInSeconds()
            );
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token invalido");
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestBody(required = false) LogoutRequest request,
                       @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (request != null && request.getRefreshToken() != null) {
            tokenService.revokeRefreshToken(request.getRefreshToken());
        }
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                tokenService.revokeAccessToken(authorization.substring(7));
            } catch (Exception ignored) {
                // ignore invalid/expired access token on logout
            }
        }
    }
}

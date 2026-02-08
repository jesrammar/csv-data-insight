package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.LoginRequest;
import com.asecon.enterpriseiq.dto.LoginResponse;
import com.asecon.enterpriseiq.repo.UserRepository;
import com.asecon.enterpriseiq.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthController(JwtService jwtService, UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        var user = userRepository.findByEmail(request.getEmail()).orElseThrow();
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Credenciales erróneas");
        }
        String token = jwtService.generateToken(user.getEmail(), user.getRole().name());
        return new LoginResponse(token, user.getRole().name(), user.getId());
    }
}

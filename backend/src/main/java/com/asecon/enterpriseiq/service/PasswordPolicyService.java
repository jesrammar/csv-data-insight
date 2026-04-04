package com.asecon.enterpriseiq.service;

import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PasswordPolicyService {
    private final int minLength;
    private final boolean requireUpper;
    private final boolean requireLower;
    private final boolean requireDigit;

    public PasswordPolicyService(
        @Value("${app.password.min-length:10}") int minLength,
        @Value("${app.password.require-upper:true}") boolean requireUpper,
        @Value("${app.password.require-lower:true}") boolean requireLower,
        @Value("${app.password.require-digit:true}") boolean requireDigit
    ) {
        this.minLength = Math.max(8, minLength);
        this.requireUpper = requireUpper;
        this.requireLower = requireLower;
        this.requireDigit = requireDigit;
    }

    public void requireValid(String password) {
        String p = password == null ? "" : password;
        if (p.length() < minLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La contraseña debe tener al menos " + minLength + " caracteres");
        }
        boolean hasUpper = p.chars().anyMatch(Character::isUpperCase);
        boolean hasLower = p.chars().anyMatch(Character::isLowerCase);
        boolean hasDigit = p.chars().anyMatch(Character::isDigit);

        if (requireUpper && !hasUpper) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La contraseña debe incluir una mayúscula");
        if (requireLower && !hasLower) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La contraseña debe incluir una minúscula");
        if (requireDigit && !hasDigit) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La contraseña debe incluir un número");

        String lower = p.toLowerCase(Locale.ROOT);
        if (lower.contains("password") || lower.contains("1234")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La contraseña es demasiado débil");
        }
    }

    public int getMinLength() { return minLength; }
    public boolean isRequireUpper() { return requireUpper; }
    public boolean isRequireLower() { return requireLower; }
    public boolean isRequireDigit() { return requireDigit; }
}


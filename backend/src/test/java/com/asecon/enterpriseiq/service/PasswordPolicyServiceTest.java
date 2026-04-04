package com.asecon.enterpriseiq.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PasswordPolicyServiceTest {

    @Test
    void requireValid_acceptsStrongPassword() {
        PasswordPolicyService svc = new PasswordPolicyService(10, true, true, true);
        svc.requireValid("Seguro123A");
    }

    @Test
    void requireValid_rejectsTooShort() {
        PasswordPolicyService svc = new PasswordPolicyService(10, true, true, true);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> svc.requireValid("Aa1"));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void requireValid_rejectsMissingUpperLowerDigit() {
        PasswordPolicyService svc = new PasswordPolicyService(10, true, true, true);
        assertThrows(ResponseStatusException.class, () -> svc.requireValid("sinmayuscula1"));
        assertThrows(ResponseStatusException.class, () -> svc.requireValid("SINMINUSCULA1"));
        assertThrows(ResponseStatusException.class, () -> svc.requireValid("SinNumeroAA"));
    }

    @Test
    void requireValid_rejectsWeakPatterns() {
        PasswordPolicyService svc = new PasswordPolicyService(10, true, true, true);
        assertThrows(ResponseStatusException.class, () -> svc.requireValid("Password123A"));
        assertThrows(ResponseStatusException.class, () -> svc.requireValid("Az1234xxxx"));
    }
}


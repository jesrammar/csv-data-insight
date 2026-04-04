package com.asecon.enterpriseiq.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class AuditLogFilterActionTest {
    @Test
    void resolves_password_change_action() {
        var a = AuditLogFilter.resolveAction("POST", "/api/auth/password/change");
        assertThat(a).isNotNull();
        assertThat(a.action()).isEqualTo("AUTH_PASSWORD_CHANGE");
    }
}


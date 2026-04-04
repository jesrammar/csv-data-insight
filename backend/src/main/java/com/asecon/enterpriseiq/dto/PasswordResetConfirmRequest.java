package com.asecon.enterpriseiq.dto;

import jakarta.validation.constraints.NotBlank;

public class PasswordResetConfirmRequest {
    @NotBlank
    private String token;

    @NotBlank
    private String newPassword;

    // "invite" | "reset" (defaults to "reset")
    private String action;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getNewPassword() { return newPassword; }
    public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
}

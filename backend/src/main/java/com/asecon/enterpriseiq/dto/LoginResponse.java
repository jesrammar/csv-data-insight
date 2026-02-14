package com.asecon.enterpriseiq.dto;

public class LoginResponse {
    private String accessToken;
    private String refreshToken;
    private String role;
    private Long userId;
    private long accessTokenExpiresInSeconds;

    public LoginResponse(String accessToken, String refreshToken, String role, Long userId, long accessTokenExpiresInSeconds) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.role = role;
        this.userId = userId;
        this.accessTokenExpiresInSeconds = accessTokenExpiresInSeconds;
    }

    public String getAccessToken() { return accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public String getRole() { return role; }
    public Long getUserId() { return userId; }
    public long getAccessTokenExpiresInSeconds() { return accessTokenExpiresInSeconds; }
}

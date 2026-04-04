package com.asecon.enterpriseiq;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void login_refresh_rotates_refresh_token() throws Exception {
        LoginResult login = login("admin@asecon.local", "password");
        String accessToken = (String) login.body.get("accessToken");
        assertThat(login.refreshCookie).isNotBlank();

        mockMvc.perform(get("/api/companies/mine")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk());

        RefreshResult refreshed = refreshWithCookie(login.refreshCookiePair);
        assertThat(refreshed.refreshCookie).isNotBlank();
        assertThat(refreshed.refreshCookiePair).isNotEqualTo(login.refreshCookiePair);
        assertThat(refreshed.body.get("refreshToken")).isNull();

        mockMvc.perform(post("/api/auth/refresh")
                .cookie(cookieFromPair(login.refreshCookiePair, "enterpriseiq_refresh"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_revokes_access_token() throws Exception {
        LoginResult login = login("admin@asecon.local", "password");
        String accessToken = (String) login.body.get("accessToken");

        mockMvc.perform(post("/api/auth/logout")
                .header("Authorization", "Bearer " + accessToken)
                .cookie(cookieFromPair(login.refreshCookiePair, "enterpriseiq_refresh")))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/companies/mine")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isUnauthorized());
    }

    private record LoginResult(Map<String, Object> body, String refreshCookie, String refreshCookiePair) {}
    private record RefreshResult(Map<String, Object> body, String refreshCookie, String refreshCookiePair) {}

    private LoginResult login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
            .andExpect(status().isOk())
            .andReturn();

        String response = result.getResponse().getContentAsString();
        Map<String, Object> body = objectMapper.readValue(response, new TypeReference<>() {});
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        String pair = cookiePair(setCookie, "enterpriseiq_refresh");
        return new LoginResult(body, setCookie, pair);
    }

    private RefreshResult refreshWithCookie(String refreshCookiePair) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/refresh")
                .cookie(cookieFromPair(refreshCookiePair, "enterpriseiq_refresh"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andReturn();

        String response = result.getResponse().getContentAsString();
        Map<String, Object> body = objectMapper.readValue(response, new TypeReference<>() {});
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        String pair = cookiePair(setCookie, "enterpriseiq_refresh");
        return new RefreshResult(body, setCookie, pair);
    }

    private static String cookiePair(String setCookie, String cookieName) {
        assertThat(setCookie).isNotNull();
        String prefix = cookieName + "=";
        int start = setCookie.indexOf(prefix);
        assertThat(start).isGreaterThanOrEqualTo(0);
        int valueStart = start + prefix.length();
        int end = setCookie.indexOf(';', valueStart);
        if (end < 0) end = setCookie.length();
        String value = setCookie.substring(valueStart, end);
        assertThat(value).isNotBlank();
        return cookieName + "=" + value;
    }

    private static Cookie cookieFromPair(String cookiePair, String cookieName) {
        String prefix = cookieName + "=";
        int start = cookiePair.indexOf(prefix);
        assertThat(start).isGreaterThanOrEqualTo(0);
        String value = cookiePair.substring(start + prefix.length());
        assertThat(value).isNotBlank();
        return new Cookie(cookieName, value);
    }
}

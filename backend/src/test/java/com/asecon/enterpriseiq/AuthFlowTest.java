package com.asecon.enterpriseiq;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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
        Map<String, Object> login = login("admin@asecon.local", "password");
        String accessToken = (String) login.get("accessToken");
        String refreshToken = (String) login.get("refreshToken");

        mockMvc.perform(get("/api/companies/mine")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk());

        Map<String, Object> refreshed = refresh(refreshToken);
        String newRefreshToken = (String) refreshed.get("refreshToken");
        assertThat(newRefreshToken).isNotEqualTo(refreshToken);

        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_revokes_access_token() throws Exception {
        Map<String, Object> login = login("admin@asecon.local", "password");
        String accessToken = (String) login.get("accessToken");
        String refreshToken = (String) login.get("refreshToken");

        mockMvc.perform(post("/api/auth/logout")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/companies/mine")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isUnauthorized());
    }

    private Map<String, Object> login(String email, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readValue(response, new TypeReference<>() {});
    }

    private Map<String, Object> refresh(String refreshToken) throws Exception {
        String response = mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readValue(response, new TypeReference<>() {});
    }
}

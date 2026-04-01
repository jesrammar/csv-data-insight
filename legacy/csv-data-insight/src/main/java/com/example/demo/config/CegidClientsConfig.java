package com.example.demo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class CegidClientsConfig {

    @Bean
    public RestClient cegidRestClient(
            @Value("${cegid.base-url}") String baseUrl,
            @Value("${cegid.subscription-key}") String apiKey
    ) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Ocp-Apim-Subscription-Key", apiKey)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }

    @Bean
    public WebClient cegidWebClient(
            @Value("${cegid.base-url}") String baseUrl,
            @Value("${cegid.subscription-key}") String apiKey
    ) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Ocp-Apim-Subscription-Key", apiKey)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }
}

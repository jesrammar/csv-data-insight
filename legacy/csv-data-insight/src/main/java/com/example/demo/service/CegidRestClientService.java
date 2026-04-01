package com.example.demo.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class CegidRestClientService {

    private final RestClient restClient;

    public CegidRestClientService(RestClient restClient) {
        this.restClient = restClient;
    }

    public String getTokenProvider() {
        return restClient.get()
                .uri("/tokenprovider/Token")
                .retrieve()
                .body(String.class);
    }
}

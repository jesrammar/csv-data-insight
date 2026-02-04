package com.example.demo.service;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class CegidWebClientService {

    private final WebClient webClient;

    public CegidWebClientService(WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<String> getTokenProvider() {
        return webClient.get()
                .uri("/tokenprovider/Token")
                .retrieve()
                .bodyToMono(String.class);
    }
}

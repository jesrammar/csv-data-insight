package com.example.demo.controller;

import com.example.demo.service.CegidRestClientService;
import com.example.demo.service.CegidWebClientService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class CegidTestController {

    private final CegidRestClientService restService;
    private final CegidWebClientService webService;

    public CegidTestController(CegidRestClientService restService, CegidWebClientService webService) {
        this.restService = restService;
        this.webService = webService;
    }

    @GetMapping("/test/restclient")
    public String testRestClient() {
        return restService.getTokenProvider();
    }

    @GetMapping("/test/webclient")
    public Mono<String> testWebClient() {
        return webService.getTokenProvider();
    }
}

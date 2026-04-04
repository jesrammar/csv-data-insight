package com.asecon.enterpriseiq;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResponseErrorHandler;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "management.server.port=0",
        "management.server.address=127.0.0.1",
        "management.endpoints.web.exposure.include=health,info,prometheus",
        "management.metrics.export.prometheus.enabled=true",
        "management.prometheus.metrics.export.enabled=true"
    }
)
class ActuatorSmokeTest {
    @LocalServerPort
    int serverPort;

    @LocalManagementPort
    int managementPort;

    private final RestTemplate restTemplate = new RestTemplate();

    ActuatorSmokeTest() {
        this.restTemplate.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return false; // let the test assert status codes
            }

            @Override
            public void handleError(ClientHttpResponse response) throws IOException {}
        });
    }

    @Test
    void actuator_health_is_exposed_on_management_port_only() {
        ResponseEntity<String> mgmt = restTemplate.getForEntity("http://localhost:" + managementPort + "/actuator/health", String.class);
        assertThat(mgmt.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mgmt.getBody()).contains("\"status\"");

        ResponseEntity<String> app = restTemplate.getForEntity("http://localhost:" + serverPort + "/actuator/health", String.class);
        assertThat(app.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void prometheus_endpoint_is_reachable_on_management_port() {
        ResponseEntity<String> mgmt = restTemplate.getForEntity("http://localhost:" + managementPort + "/actuator/prometheus", String.class);
        assertThat(mgmt.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mgmt.getBody()).contains("jvm");
    }
}

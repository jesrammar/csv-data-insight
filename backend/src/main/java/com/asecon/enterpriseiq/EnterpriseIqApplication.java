package com.asecon.enterpriseiq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EnterpriseIqApplication {
    public static void main(String[] args) {
        SpringApplication.run(EnterpriseIqApplication.class, args);
    }
}

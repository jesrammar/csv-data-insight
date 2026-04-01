package com.asecon.enterpriseiq.config;

import com.asecon.enterpriseiq.repo.AuditEventRepository;
import com.asecon.enterpriseiq.security.AuditLogFilter;
import com.asecon.enterpriseiq.service.AccessService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuditConfig {
    @Bean
    public AuditLogFilter auditLogFilter(AuditEventRepository auditEventRepository,
                                         AccessService accessService,
                                         @Value("${app.audit.enabled:true}") boolean enabled) {
        return new AuditLogFilter(auditEventRepository, accessService, enabled);
    }

    @Bean
    public FilterRegistrationBean<AuditLogFilter> auditLogFilterRegistration(AuditLogFilter filter) {
        FilterRegistrationBean<AuditLogFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}


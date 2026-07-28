package com.techcrm.crm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;

@Configuration
public class WebConfig {

    // Without this, a client-supplied ?size=999999 would return an
    // unbounded result set instead of clamping — applies to every
    // Pageable-bound endpoint (leads search, audit log, login history).
    @Bean
    public PageableHandlerMethodArgumentResolverCustomizer pageableCustomizer() {
        return resolver -> resolver.setMaxPageSize(100);
    }
}

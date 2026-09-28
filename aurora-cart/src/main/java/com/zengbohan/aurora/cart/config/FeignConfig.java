package com.zengbohan.aurora.cart.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeignConfig {

    /** Outgoing service-to-service calls carry the internal secret (ADR-0006). */
    @Bean
    public RequestInterceptor internalSecretInterceptor(
            @Value("${aurora.internal.secret}") String internalSecret) {
        return template -> template.header("X-Internal-Secret", internalSecret);
    }
}

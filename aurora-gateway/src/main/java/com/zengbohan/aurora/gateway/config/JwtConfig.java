package com.zengbohan.aurora.gateway.config;

import com.zengbohan.aurora.common.auth.JwtCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtConfig {

    @Bean
    public JwtCodec jwtCodec(@Value("${aurora.jwt.secret}") String secret) {
        return new JwtCodec(secret);
    }
}

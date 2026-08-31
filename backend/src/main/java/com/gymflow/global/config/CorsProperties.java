package com.gymflow.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cors")
public record CorsProperties(String allowedOrigins) {
}

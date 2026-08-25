package com.gymflow.global.websocket;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "websocket")
public record WebSocketProperties(String allowedOrigins) {
}

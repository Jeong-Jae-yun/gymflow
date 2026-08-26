package com.gymflow.domain.user.dto.response;

public record LoginResponse(String accessToken, String tokenType) {

    private static final String DEFAULT_TOKEN_TYPE = "Bearer";

    public static LoginResponse of(String accessToken) {
        return new LoginResponse(accessToken, DEFAULT_TOKEN_TYPE);
    }
}

package com.gymflow.domain.user.dto.response;

import com.gymflow.domain.user.domain.enumtype.UserRole;
import com.gymflow.domain.user.domain.enumtype.UserStatus;

import java.time.LocalDateTime;

public record UserResponse(
        Long id,
        String email,
        String name,
        UserRole role,
        UserStatus status,
        LocalDateTime createdAt
) {
}
